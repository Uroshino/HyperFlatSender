package com.hyperflatsender.ui

import android.content.Context
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperflatsender.data.Settings
import com.hyperflatsender.network.ConnectionState
import com.hyperflatsender.service.CaptureState
import com.hyperflatsender.service.Stats
import com.hyperflatsender.viewmodel.CaptureViewModel
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val WHEEL_SEGMENTS = 12          // panels — like a real umbrella

/**
 * On-screen demo overlays toggled from the main screen, independent of capture. The toggle button
 * cycles through these in declaration order, so adding a future demo is just a new entry here.
 */
enum class DemoMode(val label: String) {
    None("Demo: off"),
    ColourWheel("Demo: wheel"),
    Spokes("Demo: spokes");

    fun next(): DemoMode = entries[(ordinal + 1) % entries.size]
}

@Composable
fun MainScreen(
    viewModel: CaptureViewModel,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onCalibrateClick: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val captureState by viewModel.captureState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val stats by viewModel.stats.collectAsState()

    val context = LocalContext.current
    // Screen bounds are stable; the snapped capture size recalculates when the resolution changes
    val screenBounds = remember {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .currentWindowMetrics.bounds
    }
    val displayResolution = "${screenBounds.width()} × ${screenBounds.height()}"
    // The actual capture/send resolution: requested dims snapped to the screen aspect
    // (auto-nearest). This is exactly what CaptureService streams to Hyperion.
    val (capWidth, capHeight) = remember(
        settings.effectiveWidth, settings.effectiveHeight, screenBounds
    ) {
        Settings.snapToAspect(
            settings.effectiveWidth, settings.effectiveHeight,
            screenBounds.width(), screenBounds.height()
        )
    }
    // Aspect-maintained version of the *set* resolution (before the multiplier), for display.
    val (fitWidth, fitHeight) = remember(
        settings.frameWidth, settings.frameHeight, screenBounds
    ) {
        Settings.snapToAspect(
            settings.frameWidth, settings.frameHeight,
            screenBounds.width(), screenBounds.height()
        )
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isWide = screenWidthDp >= 600
    val titleSize = if (isWide) 28.sp else 22.sp
    val buttonWidth = if (isWide) 280.dp else (screenWidthDp * 0.65f).dp
    // Narrower so START + the demo toggle sit side by side without overflowing a narrow TV.
    val actionButtonWidth = if (isWide) 230.dp else (screenWidthDp * 0.40f).dp

    val startFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { startFocusRequester.requestFocus() }

    val isRunning = captureState == CaptureState.Running

    // Demo overlay is opt-in via its own button — it no longer pops automatically on START.
    var demoMode by remember { mutableStateOf(DemoMode.None) }
    val demoActive = demoMode != DemoMode.None

    // Panel scrim fades in while a demo overlay is showing so text stays readable
    val scrimAlpha by animateFloatAsState(
        targetValue = if (demoActive) 0.55f else 0f,
        animationSpec = tween(350),
        label = "scrim"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Spinning colour wheel — visible only while capturing
        AnimatedVisibility(
            visible = demoMode == DemoMode.ColourWheel,
            enter = fadeIn(tween(500)),
            exit = fadeOut(tween(500))
        ) {
            UmbrellaWheel(settings.wheelFps, settings.wheelSpinSeconds, Modifier.fillMaxSize())
        }

        AnimatedVisibility(
            visible = demoMode == DemoMode.Spokes,
            enter = fadeIn(tween(500)),
            exit = fadeOut(tween(500))
        ) {
            SpokesWheel(
                settings.wheelFps, settings.wheelSpinSeconds, settings.chaseBlockFraction,
                Modifier.fillMaxSize()
            )
        }

        // Content stays centered while it fits, but scrolls when the screen is too short to show
        // every button — TV usable heights vary, and without this the bottom buttons (e.g. CALIBRATE)
        // would be clipped off-screen instead of reachable. Focus navigation auto-scrolls them in.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
        // Content panel — always on top, scrim appears when the wheel spins
        Column(
            modifier = Modifier
                .background(
                    color = Color.Black.copy(alpha = scrimAlpha),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "HyperFlatSender",
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    if (settings.serverIp.isNotBlank()) "Server: ${settings.serverIp}:${settings.serverPort}"
                    else "Server: not configured",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                )
                StatusDot(connectionState)
                Text(
                    connectionStateLabel(connectionState),
                    color = dotColor(connectionState),
                    fontSize = 16.sp
                )
            }

            Text(
                "Screen: $displayResolution",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                fontSize = 14.sp
            )
            Text(
                buildString {
                    append("Output: ${capWidth}×${capHeight} @ ${settings.frameRate} fps")
                    if (settings.multiplier > 1) append("  (${settings.multiplier}×)")
                },
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp
            )
            Text(
                "Set ${settings.frameWidth}×${settings.frameHeight}  →  " +
                    "aspect-fit ${fitWidth}×${fitHeight}",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontSize = 13.sp
            )

            if (settings.showStats) StatsRow(isRunning, stats)

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HyperionButton(
                    onClick = if (isRunning) onStopClick else onStartClick,
                    modifier = Modifier
                        .width(actionButtonWidth)
                        .focusRequester(startFocusRequester),
                    containerColor = if (isRunning) Color(0xFF7F0000) else MaterialTheme.colorScheme.surface,
                    focusedContainerColor = if (isRunning) Color(0xFFB71C1C) else MaterialTheme.colorScheme.primary,
                    contentColor = if (isRunning) Color.White else MaterialTheme.colorScheme.primary,
                    focusedContentColor = if (isRunning) Color.White else MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(
                        if (isRunning) "STOP" else "START",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                // Cycles demo overlays (off → wheel → spokes); active state is highlighted.
                HyperionButton(
                    onClick = { demoMode = demoMode.next() },
                    modifier = Modifier.width(actionButtonWidth),
                    containerColor = if (demoActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                     else MaterialTheme.colorScheme.surface,
                    contentColor = if (demoActive) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface
                ) {
                    Text(
                        demoMode.label,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            // Second grid row: CALIBRATE sits under START, SETTINGS under the demo toggle.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Calibration runs its own connection and needs a server; it also stops capture first.
                HyperionButton(
                    onClick = onCalibrateClick,
                    enabled = settings.isServerConfigured,
                    modifier = Modifier.width(actionButtonWidth)
                ) {
                    Text("CALIBRATE", fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
                HyperionButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.width(actionButtonWidth)
                ) {
                    Text("SETTINGS", fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
        }
    }
}

/**
 * Umbrella-from-above: N hue-saturated wedges + rib lines + central hub, spinning continuously.
 * Rotation is advanced by a coroutine at the wheel's own [fps] (default 60, independent of the
 * capture rate). One full rotation takes [spinSeconds] regardless of fps — `stepDeg` scales with
 * the frame interval so the spin duration is constant; higher fps just means smoother motion.
 *
 * Perf: the geometry is angle-independent, so the Canvas draws the panels/ribs/hub ONCE into a
 * cached layer and the spin is applied as a `graphicsLayer { rotationZ }` GPU transform. Reading
 * `rotation` inside the layer block (not the draw scope) means each step is a transform-only
 * re-composite of the already-rasterised layer — NOT a full-screen 4K re-raster of 12 arcs +
 * 12 lines every frame, which was stealing GPU from the mirror and making the app's own UI lag.
 */
@Composable
private fun UmbrellaWheel(fps: Int, spinSeconds: Int, modifier: Modifier = Modifier) {
    val frameMs = if (fps > 0) 1000L / fps else 16L
    val spinMs = spinSeconds.coerceAtLeast(1) * 1000L
    // Step size: one full rotation in spinMs, advanced once per wheel frame. frameMs cancels out
    // of the per-second total, so spin duration stays = spinMs at any fps; fps only sets smoothness.
    val stepDeg = 360f * frameMs / spinMs
    var rotation by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(fps, spinSeconds) {
        while (true) {
            kotlinx.coroutines.delay(frameMs)
            rotation = (rotation + stepDeg) % 360f
        }
    }

    Canvas(
        // Spin the cached layer instead of re-drawing rotated geometry. The hub is centred, so
        // rotating about the default (centre) origin leaves it visually fixed — identical look.
        modifier = modifier.graphicsLayer { rotationZ = rotation }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        // radius large enough to always cover the screen corners at any rotation
        val radius = sqrt(cx * cx + cy * cy) * 1.05f
        val sweep = 360f / WHEEL_SEGMENTS

        // Coloured panels
        for (i in 0 until WHEEL_SEGMENTS) {
            val startAngle = i * sweep - 90f
            val hue = i.toFloat() * (360f / WHEEL_SEGMENTS)
            val panelColor = Color(
                android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.92f, 1f))
            )
            drawArc(
                color = panelColor,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = true,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2f, radius * 2f)
            )
        }

        // Rib lines — one per segment boundary, from hub to edge
        for (i in 0 until WHEEL_SEGMENTS) {
            val angleRad = Math.toRadians((i * sweep - 90f).toDouble())
            drawLine(
                color = Color.Black.copy(alpha = 0.28f),
                start = Offset(cx, cy),
                end = Offset(
                    cx + (radius * cos(angleRad)).toFloat(),
                    cy + (radius * sin(angleRad)).toFloat()
                ),
                strokeWidth = 4f
            )
        }

        // Hub — white ferrule ring + dark centre pin
        val hubR = size.minDimension * 0.045f
        drawCircle(color = Color.White, radius = hubR, center = Offset(cx, cy))
        drawCircle(color = Color(0xFF333333), radius = hubR * 0.45f, center = Offset(cx, cy))
    }
}

private const val SPOKE_COUNT = 12

/**
 * Demo overlay tied to the calibration "Chase block size": each of [SPOKE_COUNT] segments is filled
 * for [blockFraction] of its width as a coloured spoke (so a bigger chase fraction = fatter spokes),
 * spinning like the umbrella wheel via the same cached-layer rotation. Reuses the wheel's fps/spin.
 */
@Composable
private fun SpokesWheel(fps: Int, spinSeconds: Int, blockFraction: Float, modifier: Modifier = Modifier) {
    val frameMs = if (fps > 0) 1000L / fps else 16L
    val spinMs = spinSeconds.coerceAtLeast(1) * 1000L
    val stepDeg = 360f * frameMs / spinMs
    var rotation by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(fps, spinSeconds) {
        while (true) {
            kotlinx.coroutines.delay(frameMs)
            rotation = (rotation + stepDeg) % 360f
        }
    }

    Canvas(modifier = modifier.graphicsLayer { rotationZ = rotation }) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = sqrt(cx * cx + cy * cy) * 1.05f
        val seg = 360f / SPOKE_COUNT
        // Spoke angular width = the chase block fraction of a segment (clamped to stay visible/in-bounds).
        val spokeWidth = (seg * blockFraction).coerceIn(1f, seg)
        for (i in 0 until SPOKE_COUNT) {
            val center = i * seg - 90f
            val hue = i.toFloat() * (360f / SPOKE_COUNT)
            val spokeColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.9f, 1f)))
            drawArc(
                color = spokeColor,
                startAngle = center - spokeWidth / 2f,
                sweepAngle = spokeWidth,
                useCenter = true,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2f, radius * 2f)
            )
        }
        val hubR = size.minDimension * 0.045f
        drawCircle(color = Color.White, radius = hubR, center = Offset(cx, cy))
    }
}

@Composable
private fun StatsRow(isRunning: Boolean, stats: Stats) {
    if (!isRunning) return
    Text(
        "Captured ${"%.1f".format(stats.capturedFps)} → Sent ${"%.1f".format(stats.sentFps)} fps" +
            "  |  ${formatBytes(stats.bytesSent)}",
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        fontSize = 14.sp
    )
}

@Composable
private fun StatusDot(state: ConnectionState) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(dotColor(state), CircleShape)
    )
}

private fun dotColor(state: ConnectionState) = when (state) {
    is ConnectionState.Connected -> Color(0xFF4CAF50)
    is ConnectionState.Connecting -> Color(0xFFFFC107)
    else -> Color(0xFFF44336)
}

private fun connectionStateLabel(state: ConnectionState) = when (state) {
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Error -> "Error"
    is ConnectionState.Disconnected -> "Disconnected"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${"%.1f".format(bytes / 1_048_576f)} MB"
    bytes >= 1_024 -> "${"%.0f".format(bytes / 1_024f)} KB"
    else -> "$bytes B"
}
