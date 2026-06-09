package com.hyperflatsender.ui

import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hyperflatsender.capture.CalibPattern
import com.hyperflatsender.capture.Calibration
import com.hyperflatsender.data.Adjustment
import com.hyperflatsender.data.ServerType
import com.hyperflatsender.data.Settings
import com.hyperflatsender.network.ConnectionState
import com.hyperflatsender.viewmodel.CalibrationViewModel

@Composable
fun CalibrationScreen(onBack: () -> Unit) {
    val vm: CalibrationViewModel = viewModel()
    val context = LocalContext.current

    val settings by vm.settings.collectAsState()
    val connectionState by vm.connectionState.collectAsState()
    val selectedPattern by vm.selectedPattern.collectAsState()
    val adjustmentsLive by vm.adjustmentsLive.collectAsState()
    val adjustmentError by vm.adjustmentError.collectAsState()

    val screenBounds = remember {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .currentWindowMetrics.bounds
    }
    // Start the standalone calibration session once; the VM aspect-snaps the layout itself.
    LaunchedEffect(Unit) { vm.start(screenBounds.width(), screenBounds.height()) }
    // Flush any pending adjustment edit to disk on the way out (debounce may not have fired yet).
    DisposableEffect(Unit) { onDispose { vm.persistNow() } }

    // Prime D-pad focus on the first pattern so the first remote press isn't wasted bootstrapping it.
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }

    // Layout size the patterns map to — matches what the capture pipeline streams.
    val (layoutW, layoutH) = remember(settings.effectiveWidth, settings.effectiveHeight, screenBounds) {
        Settings.snapToAspect(
            settings.effectiveWidth, settings.effectiveHeight,
            screenBounds.width(), screenBounds.height()
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .tvSafeArea()
                .fillMaxSize()
                .widthIn(max = 760.dp)
        ) {
            // ── Fixed header (never scrolls) — title + connection status stay visible. ───────────
            // Previously these lived inside the scrolling column with no focusable above the first
            // pattern button, so D-pad navigation scrolled them off the top with no way back, which
            // left only part of the status row visible.
            Text(
                "Calibration",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(Modifier.size(12.dp).background(statusColor(connectionState), CircleShape))
                Text(
                    statusLabel(connectionState),
                    color = statusColor(connectionState),
                    fontSize = 15.sp
                )
                Text(
                    if (settings.serverIp.isBlank()) "· no server set"
                    else "· ${settings.serverIp}:${settings.serverPort}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Scrollable body — controls scroll under the fixed header. ────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Patterns stream to the LED layout ${layoutW}×${layoutH}. Capture is stopped " +
                        "while you calibrate; leaving releases the priority.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    fontSize = 12.sp
                )

                SectionLabel("Test pattern")
                // 4-per-row grid of pattern buttons.
                Calibration.PATTERNS.chunked(4).forEachIndexed { rowIndex, rowPatterns ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowPatterns.forEachIndexed { colIndex, pattern ->
                            val cellModifier =
                                if (rowIndex == 0 && colIndex == 0) Modifier.weight(1f).focusRequester(firstFocus)
                                else Modifier.weight(1f)
                            PatternButton(
                                pattern = pattern,
                                selected = pattern == selectedPattern,
                                onClick = { vm.selectPattern(pattern) },
                                modifier = cellModifier
                            )
                        }
                        // Pad the last (short) row so buttons keep a consistent width.
                        repeat(4 - rowPatterns.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                // Chase block size — local render setting (not a Hyperion adjustment). Size it to
                // your LED sampling so the walking block reads as solid white.
                AdjustmentSlider(
                    label = "Chase block size",
                    value = settings.chaseBlockFraction,
                    range = Settings.CHASE_BLOCK_RANGE,
                    valueText = { "%.0f%%".format(it * 100) },
                    onValueChange = { vm.onChaseBlockFractionChange(it) }
                )

                Spacer(Modifier.height(8.dp))

                SectionLabel("Colour adjustments")
                Text(
                    when {
                        adjustmentsLive ->
                            "Live over JSON-RPC (:${settings.jsonRpcPort}). Saved and re-applied " +
                                "automatically when capture connects."
                        adjustmentError != null ->
                            "Adjustment channel (:${settings.jsonRpcPort}) — $adjustmentError. " +
                                "Values are still saved and applied on the next capture connect."
                        else ->
                            "Adjustment channel (JSON-RPC :${settings.jsonRpcPort}) connecting… " +
                                "Values are saved and applied on the next capture connect."
                    },
                    color = if (adjustmentsLive) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            else Color(0xFFFFA000),
                    fontSize = 12.sp
                )

                if (settings.serverType == ServerType.HYPERHDR) {
                    Text(
                        "HyperHDR mode: a single Gamma plus Saturation/Brightness (sent as HyperHDR's " +
                            "gamma / saturationGain / luminanceGain). If a gain slider seems inert, " +
                            "take HyperHDR off its “classic” calibration so the HSL gains apply.",
                        color = Color(0xFFFFA000),
                        fontSize = 12.sp
                    )
                }

                // Only the sliders valid for the selected server — Hyperion exposes per-channel
                // gamma + brightnessGain, HyperHDR a single gamma + luminanceGain (see Adjustment).
                Adjustment.forServer(settings.serverType).forEach { adjustment ->
                    AdjustmentSlider(
                        label = adjustment.label,
                        value = adjustment.get(settings),
                        range = adjustment.range,
                        onValueChange = { vm.onAdjustmentChange(adjustment, it) }
                    )
                }

                Spacer(Modifier.height(16.dp))

                HyperionButton(onClick = onBack, modifier = Modifier.width(240.dp)) {
                    Text("Back", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun PatternButton(
    pattern: CalibPattern,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    HyperionButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                         else MaterialTheme.colorScheme.surface,
        focusedContainerColor = MaterialTheme.colorScheme.primary,
        contentColor = if (selected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        focusedContentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (pattern is CalibPattern.Solid) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(Color(0xFF000000L or pattern.rgb.toLong()), CircleShape)
                        .border(1.dp, Color.Gray.copy(alpha = 0.5f), CircleShape)
                )
            }
            Text(
                pattern.label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun AdjustmentSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String = { "%.2f".format(it) },
    onValueChange: (Float) -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    var focused by remember { mutableStateOf(false) }
    // The Material3 Slider doesn't move on D-pad left/right by itself, so we step the value
    // ourselves: ~40 presses span the whole range. We intercept on preview (before the slider's
    // own handling) and consume left/right (down AND up) so focus never escapes sideways; up/down/
    // OK fall through for normal navigation.
    val step = (range.endInclusive - range.start) / 40f

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(
                valueText(value),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                // hasFocus (not just isFocused) so the highlight shows when the slider's inner
                // focusable thumb holds focus, which is what the D-pad lands on.
                .onFocusChanged { focused = it.isFocused || it.hasFocus }
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionLeft -> {
                            if (event.type == KeyEventType.KeyDown) {
                                onValueChange((value - step).coerceIn(range.start, range.endInclusive))
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            if (event.type == KeyEventType.KeyDown) {
                                onValueChange((value + step).coerceIn(range.start, range.endInclusive))
                            }
                            true
                        }
                        else -> false
                    }
                }
                .border(
                    width = if (focused) 2.dp else 0.dp,
                    color = if (focused) accent else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
        )
    }
}

private fun statusColor(state: ConnectionState) = when (state) {
    is ConnectionState.Connected -> Color(0xFF4CAF50)
    is ConnectionState.Connecting -> Color(0xFFFFC107)
    else -> Color(0xFFF44336)
}

private fun statusLabel(state: ConnectionState) = when (state) {
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Error -> "Error"
    is ConnectionState.Disconnected -> "Disconnected"
}
