package com.hyperionflatsender.service

import android.app.Activity
import android.app.Notification
import android.content.Context
import android.view.WindowManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.hyperionflatsender.HyperionApp
import com.hyperionflatsender.MainActivity
import com.hyperionflatsender.R
import com.hyperionflatsender.capture.FrameProcessor
import com.hyperionflatsender.data.Adjustment
import com.hyperionflatsender.data.Settings
import com.hyperionflatsender.network.ConnectionState
import com.hyperionflatsender.network.HyperionClient
import com.hyperionflatsender.network.HyperionJsonClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "CaptureService"
private const val NOTIF_ID = 1
private const val CHANNEL_ID = "capture"

// Gated-mirror mode: how long the mirror surface may stay attached waiting for a frame
// before we give up and close it for this cycle (the frame normally arrives in 1–2 vsync).
private const val GATE_MAX_OPEN_MS = 60L

// Gated-mirror mode: the minimum time the mirror surface stays DETACHED each cycle. Re-attaching
// costs ~45–50ms on the MT5895, so without a floor the reopen delay collapses to 0 at ≳22fps and
// the surface never stays detached long enough for SurfaceFlinger to drop GPU-compose back to the
// hardware overlay — the very composite-mode switch that drives the YouTube frame drops. The floor
// guarantees that recovery window each cycle (and at high requested fps it is what caps the cadence).
private const val GATE_MIN_CLOSED_MS = 40L

// When the screen holds a static image the compositor stops delivering frames, so we send nothing
// and Hyperion eventually times out our priority / closes the idle socket — the LEDs drop to its
// background colour. While connected-but-idle we re-send the last frame this often to keep the
// priority (and the TCP connection) alive. Must stay well under Hyperion's timeout (~20s observed).
private const val KEEPALIVE_INTERVAL_MS = 2_000L

sealed class CaptureState {
    data object Stopped : CaptureState()
    data object WaitingPermission : CaptureState()
    data object Running : CaptureState()
}

data class Stats(val capturedFps: Float, val sentFps: Float, val bytesSent: Long) {
    companion object { val EMPTY = Stats(0f, 0f, 0L) }
}

class CaptureService : LifecycleService() {

    companion object {
        val captureState: MutableStateFlow<CaptureState> = MutableStateFlow(CaptureState.Stopped)
        val connectionState: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
        val stats: MutableStateFlow<Stats> = MutableStateFlow(Stats.EMPTY)

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_WAITING = "waiting_permission"
    }

    private val client = HyperionClient(lifecycleScope)
    private val flatbuffers by lazy { com.hyperionflatsender.network.HyperionFlatbuffers() }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameProcessor: FrameProcessor? = null
    private var connectionJob: Job? = null
    private var senderJob: Job? = null
    private var statsJob: Job? = null
    private var keepAliveJob: Job? = null

    // Most-recently-sent frame and when it went out, for the idle keepalive. Written by the sender
    // coroutine, read by the keepalive loop — @Volatile so the cross-thread read sees the latest.
    @Volatile private var lastFrame: ByteArray? = null
    @Volatile private var lastSentAtMs = 0L

    // Capture thread → sender hand-off. CONFLATED = keeps only the most recent frame, so a
    // slow network drops stale frames instead of back-pressuring the capture/mirror pipeline.
    private val frameChannel = Channel<ByteArray>(Channel.CONFLATED)

    // Dedicated HandlerThread for ImageReader callbacks — zero coroutine overhead per frame
    private var imageThread: HandlerThread? = null

    // Set true while tearing down so an in-flight image callback exits before touching the
    // reader we're about to close. Read on imageThread, written on the main thread.
    @Volatile private var releasing = false

    // Experimental gated-mirror mode. When on, the VirtualDisplay's output surface is attached
    // only briefly each cycle to grab one frame, then detached so SurfaceFlinger can fall back
    // to the hardware video-overlay path the rest of the time. All gate fields/runnables are
    // touched only on imageThread (via gateHandler).
    private var gated = false
    @Volatile private var gateOpen = false
    private var gateHandler: Handler? = null
    private var captureSurface: Surface? = null
    private var frameIntervalMs = 40L
    private var gateOpenedAt = 0L

    // Frame accounting. nextFrameDeadline (continuous-mode throttle) is touched only on imageThread.
    // The running totals are @Volatile so the stats ticker can sample them off-thread:
    // capturedCount on imageThread, sentCount/totalBytesSent on the sender coroutine.
    private var nextFrameDeadline = 0L
    @Volatile private var capturedCount = 0L
    @Volatile private var sentCount = 0L
    @Volatile private var totalBytesSent = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val waiting = intent?.getBooleanExtra(EXTRA_WAITING, false) ?: false
        if (waiting) {
            startForeground(NOTIF_ID, buildWaitingNotification())
            captureState.value = CaptureState.WaitingPermission
            return Service.START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return Service.START_NOT_STICKY
        }

        // startForeground MUST precede getMediaProjection on API 29+
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildRunningNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, buildRunningNotification())
        }

        val projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(resultCode, data)
        mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Log.i(TAG, "MediaProjection stopped"); stopSelf() }
        }, null)

        lifecycleScope.launch {
            val settings = (application as HyperionApp).settingsRepository.settingsFlow.first()
            setupCapture(settings)
            startConnectionLoop(settings)
        }

        return Service.START_NOT_STICKY
    }

    private fun setupCapture(settings: Settings) {
        releasing = false
        // Effective dimensions = base resolution × multiplier
        val effWidth  = settings.effectiveWidth
        val effHeight = settings.effectiveHeight

        // Snap the requested resolution to the nearest pair that matches the screen's real
        // aspect ratio (auto-nearest, only one axis nudged). Capture AND output then share
        // these dimensions, so there is NO vertical resampling — the previous capture→output
        // squish (e.g. 79×44 → 79×43) is what made the output look bad. The image sent to
        // Hyperion is now geometrically correct, whatever resolution the user enters.
        val screenBounds = (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .currentWindowMetrics.bounds
        val (capWidth, capHeight) = Settings.snapToAspect(
            effWidth, effHeight, screenBounds.width(), screenBounds.height()
        )
        Log.i(TAG, "Requested ${effWidth}×${effHeight}; capturing & sending at " +
            "${capWidth}×${capHeight} to honour screen aspect " +
            "${screenBounds.width()}×${screenBounds.height()}")

        val reader = ImageReader.newInstance(
            capWidth, capHeight,
            android.graphics.PixelFormat.RGBA_8888, 2
        )
        imageReader = reader
        // Output matches capture exactly → FrameProcessor performs no scaling.
        frameProcessor = FrameProcessor(capWidth, capHeight)

        // Tell the compositor to produce frames only at our target rate.
        // This is the key fix for YouTube dropped frames: the compositor no longer
        // needs to composite this surface on every vsync (60fps → 25fps burden).
        // DEFAULT lets SurfaceFlinger skip GPU compositing on vsyncs our consumer doesn't need yet.
        // FIXED_SOURCE would force it to GPU-composite every single vsync — wrong at 60fps video.
        reader.surface.setFrameRate(
            settings.frameRate.toFloat(),
            Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
        )

        // THREAD_PRIORITY_VIDEO (-16) competes directly with YouTube's hardware decoder threads.
        // THREAD_PRIORITY_DEFAULT (0) lets media threads keep priority; we still get scheduled
        // frequently enough for ambient lighting (40ms budget at 25fps).
        val thread = HandlerThread("HyperionCapture", android.os.Process.THREAD_PRIORITY_DEFAULT)
            .also { it.start() }
        imageThread = thread

        gated = settings.gateMirror
        frameIntervalMs = settings.frameIntervalMs
        nextFrameDeadline = 0L
        captureSurface = reader.surface
        val handler = Handler(thread.looper)
        gateHandler = handler

        reader.setOnImageAvailableListener(
            buildImageListener(capWidth, capHeight),
            handler
        )

        // In gated mode start DETACHED (null surface): openGate() attaches the mirror only once
        // connected, so we never composite-and-discard frames while disconnected, and "detached" is
        // the clean initial state for the gate cycle. Continuous mode attaches immediately.
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "HyperionCapture",
            capWidth, capHeight, DisplayMetrics.DENSITY_DEFAULT,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            if (gated) null else reader.surface, null, null
        )

        // In gated mode kick off the open/close cycle. The surface starts detached (null above), so
        // the first openGate() attaches it once connected — or parks the cycle if not yet connected.
        if (gated) handler.post { openGate() }

        startSenderLoop()
        startKeepAliveLoop()
        if (settings.showStats) startStatsLoop()
        captureState.value = CaptureState.Running
    }

    private fun buildImageListener(width: Int, height: Int): ImageReader.OnImageAvailableListener {
        return ImageReader.OnImageAvailableListener { ir ->
            // Bail before touching the reader once teardown has started — it may be closing.
            if (releasing) return@OnImageAvailableListener

            if (gated) {
                // Gated mode: the gate controls the rate. Ignore frames arriving outside the
                // open window (mirror latency after we already closed). On the first frame
                // while open, close the gate (detach surface) and process it.
                if (!gateOpen) {
                    try { ir.acquireLatestImage()?.close() } catch (_: Exception) {}
                    return@OnImageAvailableListener
                }
                val image = try { ir.acquireLatestImage() } catch (_: Exception) { null }
                    ?: return@OnImageAvailableListener
                closeGate()
                processAndSend(image, width, height)
                return@OnImageAvailableListener
            }

            // Continuous mode: resample the compositor's frame stream to the target rate with a
            // phase-accumulated deadline. Advancing the deadline by EXACTLY frameIntervalMs (instead
            // of resetting it to `now`) keeps a true average cadence: a 60fps source → 25fps
            // (alternating 2-/3-vsync gaps). The old `now - last >= interval` gate instead rounded
            // each 40ms target up to the next whole 16.7ms source frame (→ 50ms = only 20fps). The
            // small tolerance lets a frame arriving a hair early still count, so a source already
            // near the target rate isn't decimated by jitter.
            val now = SystemClock.elapsedRealtime()
            if (now < nextFrameDeadline - frameIntervalMs / 4) {
                try { ir.acquireLatestImage()?.close() } catch (_: Exception) {}
                return@OnImageAvailableListener
            }
            // acquireLatestImage throws if the reader was closed under us — guard the race.
            val image = try { ir.acquireLatestImage() } catch (_: Exception) { null }
                ?: return@OnImageAvailableListener
            nextFrameDeadline += frameIntervalMs
            // Fell more than a frame behind (source stall / GC pause): re-anchor the phase to now so
            // we don't emit a catch-up burst.
            if (nextFrameDeadline <= now) nextFrameDeadline = now + frameIntervalMs
            processAndSend(image, width, height)
        }
    }

    /**
     * Convert → build → release the Image → hand off to the sender. The Image is released before
     * any network I/O (the blocking write runs on the sender coroutine): if it ran here, a slow
     * Hyperion/network would stall this thread while holding a capture buffer, fill the
     * ImageReader's 2-slot queue, back-pressure SurfaceFlinger's mirror, and freeze the display.
     * Runs on imageThread.
     */
    private fun processAndSend(image: Image, width: Int, height: Int) {
        val framed: ByteArray? = try {
            val proc = frameProcessor
            if (proc != null && client.connectionState.value is ConnectionState.Connected)
                flatbuffers.buildImageMessage(proc.processImage(image), width, height)
            else null
        } catch (e: Exception) {
            Log.w(TAG, "Frame processing skipped: ${e.message}")
            null
        } finally {
            try { image.close() } catch (_: Exception) {}   // release ASAP — only 2 slots
        }
        // Non-blocking; conflation drops any frame the sender hasn't picked up yet.
        if (framed != null) {
            capturedCount++
            frameChannel.trySend(framed)
        }
    }

    // --- Gated-mirror cycle (all on imageThread via gateHandler) ---

    private val safetyCloseRunnable = Runnable { if (gateOpen) closeGate() }
    private val openGateRunnable = Runnable { openGate() }

    /** Attach/detach the mirror surface, tolerating the STOP race: setSurface() throws if onDestroy
     *  (main thread) already released the VirtualDisplay in the brief window before it nulls the ref,
     *  and the gate runnables run on imageThread where they can slip past their `releasing` guard. */
    private fun setMirrorSurface(s: Surface?) {
        try { virtualDisplay?.surface = s } catch (_: Exception) {}
    }

    /** Attach the mirror surface and arm the safety close. The NEXT open is scheduled by
     *  closeGate(), so opens stay one frame-interval apart regardless of re-attach latency.
     *  While disconnected the cycle PARKS: every captured frame would be discarded downstream, so we
     *  keep the surface detached and just poll until Connected, resuming the moment it returns —
     *  parking (self-rescheduling) rather than early-returning, which a missed Connected emission
     *  could otherwise make permanent. */
    private fun openGate() {
        if (releasing) return
        val s = captureSurface ?: return
        val h = gateHandler ?: return
        if (client.connectionState.value !is ConnectionState.Connected) {
            if (gateOpen) { gateOpen = false; setMirrorSurface(null) }
            h.removeCallbacks(safetyCloseRunnable)
            h.removeCallbacks(openGateRunnable)
            h.postDelayed(openGateRunnable, frameIntervalMs)
            return
        }
        h.removeCallbacks(openGateRunnable)
        h.removeCallbacks(safetyCloseRunnable)
        setMirrorSurface(s)
        gateOpen = true
        gateOpenedAt = SystemClock.elapsedRealtime()
        h.postDelayed(safetyCloseRunnable, GATE_MAX_OPEN_MS)
    }

    /** Detach the mirror surface (restores the overlay fast-path) and schedule the next open so
     *  that opens land ~frameIntervalMs apart — a regular cadence instead of jittery bursts.
     *  The delay is floored at GATE_MIN_CLOSED_MS so the surface always stays detached long enough
     *  for SurfaceFlinger to recover the hardware overlay; when frameIntervalMs ≤ re-attach latency
     *  (high requested fps) that floor is what caps the cadence, still evenly spaced. */
    private fun closeGate() {
        if (!gateOpen) return
        gateOpen = false
        val h = gateHandler
        h?.removeCallbacks(safetyCloseRunnable)
        setMirrorSurface(null)
        if (releasing) return
        val sinceOpen = SystemClock.elapsedRealtime() - gateOpenedAt
        val delay = (frameIntervalMs - sinceOpen).coerceAtLeast(GATE_MIN_CLOSED_MS)
        h?.removeCallbacks(openGateRunnable)
        h?.postDelayed(openGateRunnable, delay)
    }

    /**
     * Drains the frame channel and writes to the socket OFF the capture thread, so socket
     * backpressure can never stall screen mirroring. Stats reflect frames actually sent.
     */
    private fun startSenderLoop() {
        senderJob?.cancel()
        senderJob = lifecycleScope.launch(Dispatchers.IO) {
            for (frame in frameChannel) {
                if (client.connectionState.value !is ConnectionState.Connected) continue
                client.sendFrame(frame)
                totalBytesSent += frame.size
                sentCount++
                // Remember the last frame so the keepalive can re-send it while the screen is idle.
                lastFrame = frame
                lastSentAtMs = SystemClock.elapsedRealtime()
            }
        }
    }

    /**
     * Re-sends the last frame while connected but idle, so Hyperion never times out our priority
     * during a static image. Feeds the same conflated channel as capture (single writer = the sender
     * coroutine); while actively streaming, lastSentAtMs keeps advancing so this never fires.
     */
    private fun startKeepAliveLoop() {
        keepAliveJob?.cancel()
        keepAliveJob = lifecycleScope.launch(Dispatchers.Default) {
            while (true) {
                delay(KEEPALIVE_INTERVAL_MS)
                val frame = lastFrame ?: continue
                if (client.connectionState.value !is ConnectionState.Connected) continue
                if (SystemClock.elapsedRealtime() - lastSentAtMs >= KEEPALIVE_INTERVAL_MS) {
                    frameChannel.trySend(frame)
                }
            }
        }
    }

    /** Manages connection and reconnection only — no frame I/O here. */
    private fun startConnectionLoop(settings: Settings) {
        connectionJob?.cancel()
        connectionJob = lifecycleScope.launch(Dispatchers.IO) {
            launch { client.connectionState.collect { connectionState.value = it } }

            var backoffMs = 1_000L
            if (client.connect(settings)) applyAdjustments(settings)

            while (true) {
                delay(3_000)
                if (client.connectionState.value !is ConnectionState.Connected) {
                    Log.i(TAG, "Reconnecting in ${backoffMs}ms")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                    if (client.connect(settings)) {
                        backoffMs = 1_000L
                        applyAdjustments(settings)
                    }
                }
            }
        }
    }

    /**
     * Pushes the saved colour adjustments to Hyperion over its JSON-RPC channel on each (re)connect.
     * Hyperion does not persist adjustments across restarts, so re-applying here makes the user's
     * calibration "stick". Best-effort and isolated: a one-shot connection, failures are logged only
     * (the image stream is unaffected if the JSON-RPC port is closed / auth-protected).
     */
    private suspend fun applyAdjustments(settings: Settings) {
        val json = HyperionJsonClient()
        try {
            if (!json.connect(settings.serverIp, settings.jsonRpcPort, settings.jsonToken)) return
            val fields = Adjustment.entries.associate { adj ->
                adj.jsonKey to adj.get(settings).coerceIn(adj.range.start, adj.range.endInclusive)
            }
            val reply = json.setAdjustment(fields)
            if (reply?.success != true) Log.w(TAG, "Adjustment apply failed: ${reply?.error}")
        } catch (e: Exception) {
            Log.w(TAG, "Adjustment apply error: ${e.message}")
        } finally {
            json.disconnect()
        }
    }

    /**
     * Samples the running counters once a second and publishes captured/sent fps. Runs on its
     * own ticker so both rates stay live even when nothing is being sent (e.g. server down).
     */
    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch(Dispatchers.Default) {
            var windowStart = SystemClock.elapsedRealtime()
            var capPrev = capturedCount
            var sentPrev = sentCount
            while (true) {
                delay(1_000)
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - windowStart).coerceAtLeast(1L)
                val cap = capturedCount
                val sent = sentCount
                val capturedFps = (cap - capPrev) * 1000f / elapsed
                val sentFps = (sent - sentPrev) * 1000f / elapsed
                stats.value = Stats(capturedFps, sentFps, totalBytesSent)
                // Also log so the rates are observable via `adb logcat -s CaptureService`
                // while the app is backgrounded (you can't see the overlay during playback).
                Log.i(TAG, "fps captured=%.1f sent=%.1f bytes=%d".format(capturedFps, sentFps, totalBytesSent))
                windowStart = now
                capPrev = cap
                sentPrev = sent
            }
        }
    }

    override fun onDestroy() {
        // Order matters — closing the ImageReader while the VirtualDisplay still feeds its
        // Surface (or while a callback is mid-frame) is what crashed STOP.
        releasing = true
        connectionJob?.cancel()
        senderJob?.cancel()
        statsJob?.cancel()
        keepAliveJob?.cancel()
        lastFrame = null

        // 1) Stop the producer first so no new frames enter the reader's Surface.
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null

        // 2) Stop dispatching callbacks and the gate cycle, then unblock any in-flight send and
        //    let the image thread drain before we close the reader it reads from.
        imageReader?.setOnImageAvailableListener(null, null)
        gateHandler?.removeCallbacks(openGateRunnable)
        gateHandler?.removeCallbacks(safetyCloseRunnable)
        gateHandler = null
        captureSurface = null
        client.disconnect()
        imageThread?.quitSafely()
        try { imageThread?.join(500) } catch (_: InterruptedException) {}
        imageThread = null

        // 3) Now the consumer can be closed safely.
        imageReader?.close()
        imageReader = null

        captureState.value = CaptureState.Stopped
        connectionState.value = ConnectionState.Disconnected
        stats.value = Stats.EMPTY
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun createNotificationChannel() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildRunningNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun buildWaitingNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_FROM_BOOT, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text_waiting))
            .setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
    }
}
