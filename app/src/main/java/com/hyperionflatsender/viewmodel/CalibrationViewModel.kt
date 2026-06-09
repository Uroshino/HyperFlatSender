package com.hyperionflatsender.viewmodel

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hyperionflatsender.HyperionApp
import com.hyperionflatsender.capture.CalibPattern
import com.hyperionflatsender.capture.Calibration
import com.hyperionflatsender.data.Adjustment
import com.hyperionflatsender.data.Settings
import com.hyperionflatsender.network.ConnectionState
import com.hyperionflatsender.network.HyperionClient
import com.hyperionflatsender.network.HyperionFlatbuffers
import com.hyperionflatsender.network.HyperionJsonClient
import com.hyperionflatsender.service.CaptureService
import com.hyperionflatsender.service.CaptureState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Re-send the held (static) pattern this often so Hyperion never times out our priority during a
// solid/ramp/edge pattern — the same idle problem the capture service guards against.
private const val KEEPALIVE_MS = 2_000L

// Chase animation step interval. One border pixel per step; slow enough to follow the dot by eye
// around the strip to read off LED order / start corner / direction.
private const val CHASE_FRAME_MS = 80L

// How often the (single) render loop re-checks the selected pattern while holding a static one, so a
// pattern switch is acted on within ~this long instead of waiting out a full KEEPALIVE_MS.
private const val RENDER_POLL_MS = 120L

private const val TAG = "CalibrationVM"

// Don't re-attempt a failed JSON-RPC connect on every slider snapshot — a dropped SYN can cost up
// to the 5s socket timeout each time. Hold off this long after a failure so a drag can't serialize
// back-to-back blocking connects.
private const val JSON_RETRY_BACKOFF_MS = 3_000L

// Cap on how long we wait for the capture service to fully release its Hyperion registration before
// the calibration client connects (it is asked to stop as we navigate here).
private const val CAPTURE_STOP_WAIT_MS = 3_000L

/**
 * Drives the Calibration screen. It is a SELF-CONTAINED Hyperion client, independent of the capture
 * service (which the UI stops before entering calibration), with two channels to the same server:
 *
 *  - FlatBuffers (serverPort) — streams the selected test pattern. Solids go as a `Color` command,
 *    the per-LED patterns as a synthesized `RawImage` at the Hyperion layout size. Static patterns
 *    are re-sent on a keepalive; Chase animates and is self-keeping.
 *  - JSON-RPC (jsonRpcPort) — pushes the gamma / saturation / brightness adjustments live as you
 *    drag the sliders, and persists them so the capture service can re-apply them on connect.
 *
 * Leaving the screen clears the ViewModel: the loops stop and both sockets close, which releases our
 * Hyperion priority so the previous source / default resumes.
 */
class CalibrationViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as HyperionApp).settingsRepository
    // App-lifetime scope for the final settings flush, so it can't be cancelled with viewModelScope.
    private val appScope = (app as HyperionApp).appScope
    private val flatClient = HyperionClient(viewModelScope)
    private val jsonClient = HyperionJsonClient()
    private val flatbuffers = HyperionFlatbuffers()

    /** Latest full settings (adjustment sliders read their values from here). */
    private val _settings = MutableStateFlow(Settings.DEFAULT)
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _selectedPattern = MutableStateFlow<CalibPattern?>(null)
    val selectedPattern: StateFlow<CalibPattern?> = _selectedPattern.asStateFlow()

    /** True while the JSON-RPC adjustment channel is reachable (live slider preview works). */
    private val _adjustmentsLive = MutableStateFlow(false)
    val adjustmentsLive: StateFlow<Boolean> = _adjustmentsLive.asStateFlow()

    /** Why the adjustment channel isn't live (connect error / Hyperion rejection), or null when it
     *  is live. Surfaced in the UI so a failure is actionable instead of a generic "not reachable". */
    private val _adjustmentError = MutableStateFlow<String?>(null)
    val adjustmentError: StateFlow<String?> = _adjustmentError.asStateFlow()

    /** FlatBuffers (pattern stream) connection state, surfaced to the UI. */
    val connectionState: StateFlow<ConnectionState> = flatClient.connectionState

    // Hyperion LED-layout size the patterns are synthesized at. @Volatile: written on the connection
    // coroutine after settings load, read on the render coroutine.
    @Volatile private var width = 1
    @Volatile private var height = 1

    // Conflated so a fast slider drag collapses to the latest snapshot — the single drainer pushes
    // all adjustment fields per snapshot, so we never flood Hyperion with one RPC per pixel.
    private val pushChannel = Channel<Settings>(Channel.CONFLATED)

    private var started = false
    private var saveJob: Job? = null
    // Earliest time the drainer may retry a failed JSON-RPC connect (SystemClock.elapsedRealtime ms).
    private var nextJsonConnectAtMs = 0L

    /** Idempotent. [screenWidth]/[screenHeight] are the real display bounds, used to aspect-snap the
     *  layout size exactly like the capture pipeline does. */
    fun start(screenWidth: Int, screenHeight: Int) {
        if (started) return
        started = true

        // ONE render loop, the sole writer to the flat socket. It re-reads the selected pattern every
        // tick instead of spawning a per-pattern coroutine, so there is nothing to cancel on a switch
        // and a previous pattern (notably Chase) can never keep sending after you leave it — the next
        // tick simply sends the new pattern. Static patterns re-send on a keepalive; Chase advances.
        viewModelScope.launch(Dispatchers.IO) {
            var current: CalibPattern? = null
            var staticMsg: ByteArray? = null   // cached frame for non-Chase patterns (built once on change)
            var chaseBuf: ByteArray? = null    // reused across Chase frames so the animation doesn't churn the GC
            var chasePos = 0
            var lastStaticSendMs = 0L
            while (isActive) {
                val pattern = _selectedPattern.value
                if (pattern == null || flatClient.connectionState.value !is ConnectionState.Connected) {
                    delay(RENDER_POLL_MS)
                    continue
                }
                if (pattern !== current) {
                    Log.d(TAG, "pattern -> ${pattern.label}")
                    current = pattern
                    chasePos = 0
                    lastStaticSendMs = 0L
                    staticMsg = when (pattern) {
                        // Solids go out as a full RawImage (not a Color command) so they reliably
                        // overwrite a prior Chase frame on the LEDs — see Calibration.solid().
                        is CalibPattern.Solid -> flatbuffers.buildImageMessage(Calibration.solid(width, height, pattern.rgb), width, height)
                        CalibPattern.GammaRamp -> flatbuffers.buildImageMessage(Calibration.gammaRamp(width, height), width, height)
                        CalibPattern.EdgeMap -> flatbuffers.buildImageMessage(Calibration.edgeMap(width, height), width, height)
                        CalibPattern.Chase -> null
                    }
                }
                if (pattern is CalibPattern.Chase) {
                    val per = Calibration.perimeter(width, height).coerceAtLeast(1)
                    val frac = _settings.value.chaseBlockFraction
                    val buf = chaseBuf?.takeIf { it.size == width * height * 3 }
                        ?: ByteArray(width * height * 3).also { chaseBuf = it }
                    Calibration.fillChase(buf, width, height, chasePos, frac)
                    flatClient.sendFrame(flatbuffers.buildImageMessage(buf, width, height))
                    chasePos = (chasePos + 1) % per
                    delay(CHASE_FRAME_MS)
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastStaticSendMs >= KEEPALIVE_MS) {
                        staticMsg?.let { flatClient.sendFrame(it) }
                        lastStaticSendMs = now
                    }
                    delay(RENDER_POLL_MS)
                }
            }
        }

        // Drain adjustment snapshots → JSON-RPC. Single writer of jsonClient. Conflated upstream, and
        // a failed connect backs off (JSON_RETRY_BACKOFF_MS) so a drag can't serialize 5s connects.
        viewModelScope.launch(Dispatchers.IO) {
            for (snapshot in pushChannel) {
                if (!jsonClient.isConnected) {
                    if (SystemClock.elapsedRealtime() < nextJsonConnectAtMs) {
                        _adjustmentsLive.value = false
                        continue
                    }
                    if (!jsonClient.connect(snapshot.serverIp, snapshot.jsonRpcPort, snapshot.jsonToken)) {
                        nextJsonConnectAtMs = SystemClock.elapsedRealtime() + JSON_RETRY_BACKOFF_MS
                        _adjustmentsLive.value = false
                        _adjustmentError.value = jsonClient.lastError ?: "unreachable"
                        continue
                    }
                    nextJsonConnectAtMs = 0L
                }
                // Clamp to each field's range so a stale saved value can't push the command out of
                // Hyperion's accepted bounds (which would fail the whole command's schema validation).
                val fields = Adjustment.entries.associate { adj ->
                    adj.jsonKey to adj.get(snapshot).coerceIn(adj.range.start, adj.range.endInclusive)
                }
                val reply = jsonClient.setAdjustment(fields)
                when {
                    reply == null -> {
                        jsonClient.disconnect()
                        _adjustmentsLive.value = false
                        _adjustmentError.value = jsonClient.lastError ?: "no reply from Hyperion"
                    }
                    !reply.success -> {
                        _adjustmentsLive.value = false
                        _adjustmentError.value = reply.error ?: "rejected by Hyperion"
                    }
                    else -> {
                        _adjustmentsLive.value = true
                        _adjustmentError.value = null
                    }
                }
            }
        }

        // Connect the pattern stream + maintain it; (re)push adjustments on each connect.
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = repo.settingsFlow.first()
            _settings.value = loaded
            val (w, h) = Settings.snapToAspect(
                loaded.effectiveWidth, loaded.effectiveHeight, screenWidth, screenHeight
            )
            width = w
            height = h
            // Show something immediately once connected.
            _selectedPattern.value = Calibration.PATTERNS.firstOrNull()

            // Capture was asked to stop as we navigated here. Wait until the service has fully torn
            // down (which closes its socket and releases the Hyperion registration) before we connect,
            // so the two clients don't briefly contend at the same priority. Returns at once if no
            // capture was running; bounded so a stuck teardown can't block calibration forever.
            withTimeoutOrNull(CAPTURE_STOP_WAIT_MS) {
                CaptureService.captureState.first { it is CaptureState.Stopped }
            }

            var backoff = 1_000L
            while (isActive) {
                if (flatClient.connectionState.value !is ConnectionState.Connected) {
                    if (flatClient.connect(_settings.value)) {
                        backoff = 1_000L
                        pushChannel.trySend(_settings.value)   // (re)apply adjustments on connect
                    } else {
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(15_000L)
                        continue
                    }
                }
                delay(2_000)
            }
        }
    }

    fun selectPattern(pattern: CalibPattern) {
        _selectedPattern.value = pattern
    }

    /** Chase block size (fraction of each axis). Updates in-memory so the render loop picks it up on
     *  the next Chase frame, and persists on the usual debounce. Not a Hyperion adjustment, so it's
     *  not pushed over JSON-RPC. */
    fun onChaseBlockFractionChange(value: Float) {
        _settings.value = _settings.value.copy(chaseBlockFraction = value)
        scheduleSave()
    }

    fun onAdjustmentChange(adjustment: Adjustment, value: Float) {
        _settings.value = adjustment.set(_settings.value, value)
        pushChannel.trySend(_settings.value)   // live preview (conflated)
        scheduleSave()
    }

    /** Flush the latest adjustments to disk. Called from the screen's onDispose to catch a change made
     *  right before leaving, before the debounce fires. Runs on appScope (not viewModelScope) so the
     *  write isn't cancelled when the ViewModel is cleared on the way out. */
    fun persistNow() {
        saveJob?.cancel()
        val snapshot = _settings.value
        appScope.launch { repo.saveSettings(snapshot) }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        val snapshot = _settings.value
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(350)
            repo.saveSettings(snapshot)
        }
    }

    override fun onCleared() {
        // viewModelScope is already cancelled here (loops stopped). Closing the sockets releases our
        // Hyperion priority so the previous source / Hyperion's default resumes.
        flatClient.disconnect()
        jsonClient.disconnect()
        super.onCleared()
    }
}
