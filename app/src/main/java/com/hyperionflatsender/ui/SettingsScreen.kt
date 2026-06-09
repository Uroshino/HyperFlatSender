package com.hyperionflatsender.ui

import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperionflatsender.BuildConfig
import com.hyperionflatsender.data.Settings
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    settings: Settings,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit
) {
    // Edit buffer owned by this screen — NOT keyed on `settings`, so autosave writing back through
    // `settings` can't reset the draft mid-edit. The screen is the only editor while it's open.
    var draft by remember { mutableStateOf(settings) }

    val portError = draft.serverPort !in 1..65535
    val jsonRpcPortError = draft.jsonRpcPort !in 1..65535
    val widthError = draft.frameWidth !in 1..1920
    val heightError = draft.frameHeight !in 1..1080
    val fpsError = draft.frameRate !in 1..60
    val priorityError = draft.priority !in Settings.PRIORITY_MIN..Settings.PRIORITY_MAX
    val wheelFpsError = draft.wheelFps !in 1..60
    val wheelSpinError = draft.wheelSpinSeconds !in 1..600
    val hasError = portError || jsonRpcPortError || widthError || heightError || fpsError ||
        priorityError || wheelFpsError || wheelSpinError

    // Autosave: valid edits persist automatically. A short debounce coalesces rapid changes
    // (typing, D-pad repeats) while on screen; the latest valid draft is also flushed on leave
    // (onDispose) so a change made just before pressing Back isn't lost. Invalid drafts (out-of-
    // range values) are never written — the field shows its error until corrected.
    LaunchedEffect(draft) {
        if (!hasError && draft != settings) {
            delay(500)
            onSave(draft)
        }
    }
    val pendingSave by rememberUpdatedState(if (!hasError && draft != settings) draft else null)
    DisposableEffect(Unit) {
        onDispose { pendingSave?.let(onSave) }
    }

    // Layout adapts: wide = TV/tablet side-by-side rows, narrow = phone stacked label+field
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isWide = screenWidthDp >= 600
    val hPad = if (isWide) 80.dp else 16.dp
    val labelWidth: Dp? = if (isWide) 220.dp else null  // null = full-width stacked label

    // Screen bounds drive the aspect snap. "Scaled" = the requested dims snapped to the
    // screen's real aspect ratio — exactly what CaptureService captures and streams.
    val context = LocalContext.current
    val screenBounds = remember {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .currentWindowMetrics.bounds
    }
    // Keyed so the aspect snap only recomputes when the dimensions/multiplier actually change —
    // not on every unrelated recomposition (toggling a switch, editing IP, focus moves, …).
    val scaledBefore = remember(draft.frameWidth, draft.frameHeight, screenBounds) {
        Settings.snapToAspect(
            draft.frameWidth, draft.frameHeight, screenBounds.width(), screenBounds.height()
        )
    }
    val scaledAfter = remember(draft.frameWidth, draft.frameHeight, draft.multiplier, screenBounds) {
        Settings.snapToAspect(
            draft.effectiveWidth, draft.effectiveHeight, screenBounds.width(), screenBounds.height()
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = hPad, vertical = 32.dp)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 720.dp)  // cap column width on very wide screens
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "HyperionFlatSender · v${BuildConfig.VERSION_NAME}",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontSize = 13.sp
            )

            Spacer(Modifier.height(16.dp))

            SettingsTextField(
                label = "Server IP",
                value = draft.serverIp,
                labelWidth = labelWidth,
                keyboardType = KeyboardType.Uri,
                onValueChange = { draft = draft.copy(serverIp = it) }
            )
            SettingsNumberField(
                label = "Server Port",
                value = draft.serverPort,
                labelWidth = labelWidth,
                isError = portError,
                errorText = "1–65535",
                onValueChange = { draft = draft.copy(serverPort = it) }
            )
            SettingsNumberField(
                label = "JSON-RPC Port",
                value = draft.jsonRpcPort,
                labelWidth = labelWidth,
                isError = jsonRpcPortError,
                errorText = "1–65535",
                onValueChange = { draft = draft.copy(jsonRpcPort = it) }
            )
            // Optional: only needed when this device isn't on Hyperion's local subnet (see Settings.jsonToken).
            SettingsTextField(
                label = "API Token",
                value = draft.jsonToken,
                labelWidth = labelWidth,
                keyboardType = KeyboardType.Text,
                onValueChange = { draft = draft.copy(jsonToken = it.trim()) }
            )
            SettingsNumberField(
                label = "Width (px)",
                value = draft.frameWidth,
                labelWidth = labelWidth,
                isError = widthError,
                errorText = "1–1920",
                onValueChange = { draft = draft.copy(frameWidth = it) }
            )
            SettingsNumberField(
                label = "Height (px)",
                value = draft.frameHeight,
                labelWidth = labelWidth,
                isError = heightError,
                errorText = "1–1080",
                onValueChange = { draft = draft.copy(frameHeight = it) }
            )
            MultiplierSelector(
                label = "Resolution ×",
                selected = draft.multiplier,
                labelWidth = labelWidth,
                onSelect = { draft = draft.copy(multiplier = it) }
            )
            if (!widthError && !heightError) {
                Text(
                    text = buildString {
                        append("Selected ${draft.frameWidth}×${draft.frameHeight}")
                        append("  (scaled ${scaledBefore.first}×${scaledBefore.second}")
                        if (draft.multiplier > 1) {
                            append(" → ×${draft.multiplier} → ${scaledAfter.first}×${scaledAfter.second}")
                        }
                        append(")")
                    },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(
                        start = labelWidth ?: 0.dp,
                        top = 2.dp,
                        bottom = 4.dp
                    )
                )
            }
            SettingsNumberField(
                label = "FPS (1–60)",
                value = draft.frameRate,
                labelWidth = labelWidth,
                isError = fpsError,
                errorText = "1–60",
                onValueChange = { draft = draft.copy(frameRate = it) }
            )
            frameRateHint(draft.frameRate)?.let { (msg, hintColor) ->
                Text(
                    msg,
                    color = hintColor,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(
                        start = labelWidth ?: 0.dp,
                        bottom = 4.dp
                    )
                )
            }
            SettingsNumberField(
                label = "Priority (100–199)",
                value = draft.priority,
                labelWidth = labelWidth,
                isError = priorityError,
                errorText = "100–199",
                onValueChange = { draft = draft.copy(priority = it) }
            )
            SettingsTextField(
                label = "Origin",
                value = draft.origin,
                labelWidth = labelWidth,
                onValueChange = { draft = draft.copy(origin = it) }
            )

            Spacer(Modifier.height(8.dp))

            SettingsSwitchRow(
                title = "Auto-start on boot (one tap to authorize)",
                checked = draft.autoStartOnBoot,
                onCheckedChange = { draft = draft.copy(autoStartOnBoot = it) }
            )

            SettingsSwitchRow(
                title = "Show capture stats on main screen",
                checked = draft.showStats,
                onCheckedChange = { draft = draft.copy(showStats = it) }
            )

            SettingsSwitchRow(
                title = "Gate mirror (experimental)",
                description = "Briefly enables the screen mirror only to grab each frame, to reduce " +
                    "video frame drops while capturing. Re-enabling the mirror takes time, " +
                    "so this caps the capture rate to ~10–12 fps — use 10 FPS with this on. " +
                    "Higher FPS won't be reached and weakens the effect.",
                checked = draft.gateMirror,
                onCheckedChange = { draft = draft.copy(gateMirror = it) }
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Colour wheel",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
            )
            Text(
                "The spinning wheel shown on the main screen while capturing. Cosmetic only — it " +
                    "does not affect what is sent to Hyperion. Higher FPS = smoother spin.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            SettingsNumberField(
                label = "Wheel FPS (1–60)",
                value = draft.wheelFps,
                labelWidth = labelWidth,
                isError = wheelFpsError,
                errorText = "1–60",
                onValueChange = { draft = draft.copy(wheelFps = it) }
            )
            SettingsNumberField(
                label = "Seconds per full spin",
                value = draft.wheelSpinSeconds,
                labelWidth = labelWidth,
                isError = wheelSpinError,
                errorText = "1–600",
                onValueChange = { draft = draft.copy(wheelSpinSeconds = it) }
            )

            Spacer(Modifier.height(24.dp))

            // Changes autosave (above); this is just the exit affordance — D-pad Back works too.
            HyperionButton(
                onClick = onBack,
                modifier = Modifier.width(240.dp)
            ) {
                Text("Back", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    labelWidth: Dp?,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    // While editing, a back press exits edit mode (and hides the IME) instead of leaving the screen.
    BackHandler(enabled = editing) {
        editing = false
        keyboard?.hide()
    }

    val field: @Composable (Modifier) -> Unit = { mod ->
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            // Read-only until activated → D-pad navigation onto the field never opens the keyboard.
            readOnly = !editing,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { editing = false; keyboard?.hide() }),
            modifier = mod
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) editing = false
                }
                .onPreviewKeyEvent { onTvFieldKey(it, editing) { editing = true; keyboard?.show() } }
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) MaterialTheme.colorScheme.primary else Color.Gray,
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }

    if (labelWidth != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp,
                modifier = Modifier.width(labelWidth))
            field(Modifier.weight(1f))
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
                fontWeight = FontWeight.Medium)
            field(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SettingsNumberField(
    label: String,
    value: Int,
    labelWidth: Dp?,
    isError: Boolean,
    errorText: String,
    onValueChange: (Int) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value.toString()) }

    // While editing, a back press exits edit mode (and hides the IME) instead of leaving the screen.
    BackHandler(enabled = editing) {
        editing = false
        keyboard?.hide()
    }

    val field: @Composable (Modifier) -> Unit = { mod ->
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() }
                text.toIntOrNull()?.let { onValueChange(it) }
            },
            // Read-only until activated → D-pad navigation onto the field never opens the keyboard.
            readOnly = !editing,
            textStyle = TextStyle(
                color = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { editing = false; keyboard?.hide() }),
            singleLine = true,
            modifier = mod
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) editing = false
                }
                .onPreviewKeyEvent { onTvFieldKey(it, editing) { editing = true; keyboard?.show() } }
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = when {
                        isError -> MaterialTheme.colorScheme.error
                        focused -> MaterialTheme.colorScheme.primary
                        else -> Color.Gray
                    },
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }

    Column {
        if (labelWidth != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp,
                    modifier = Modifier.width(labelWidth))
                field(Modifier.weight(1f))
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium)
                field(Modifier.fillMaxWidth())
            }
        }
        if (isError) {
            Text(
                "Must be $errorText",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(
                    start = if (labelWidth != null) labelWidth else 0.dp,
                    bottom = 2.dp
                )
            )
        }
    }
}

/**
 * A toggle row with an accent-coloured Switch and a strong, TV-visible focus highlight: when the
 * D-pad lands on the switch the whole row gets an accent outline + tint, so it's obvious which
 * setting is selected (the default Material3 switch focus state is near-invisible on the dark TV
 * theme, which made navigation feel unresponsive). [onFocusChanged] sits above the Switch's own
 * focusable, so it reports the switch's focus and we mirror it onto the row.
 */
@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null
) {
    val accent = MaterialTheme.colorScheme.primary
    var focused by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                color = if (focused) accent.copy(alpha = 0.14f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) accent else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
            if (description != null) {
                Text(
                    description,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    fontSize = 12.sp
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = accent,
                checkedBorderColor = accent,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            ),
            modifier = Modifier.onFocusChanged { focused = it.isFocused }
        )
    }
}

@Composable
private fun MultiplierSelector(
    label: String,
    selected: Int,
    labelWidth: Dp?,
    onSelect: (Int) -> Unit
) {
    val options = com.hyperionflatsender.data.Settings.MULTIPLIERS

    val content: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { value ->
                val isSelected = value == selected
                HyperionButton(
                    onClick = { onSelect(value) },
                    modifier = Modifier.heightIn(min = 36.dp),
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                     else MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.primary,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    focusedContentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(
                        "${value}×",
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }

    if (labelWidth != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp,
                modifier = Modifier.width(labelWidth))
            content()
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
                fontWeight = FontWeight.Medium)
            content()
        }
    }
}

/**
 * Advice shown under the FPS field. The smoothest result is when the capture rate matches the
 * SOURCE: 24 fps suits 24fps movies/film, while rates that divide 60 (30/20/15/12/10) suit 60fps
 * content (YouTube, games, TV UI). Rates matching neither land between source frames and judder, and
 * high rates raise the screen-mirror's GPU-compositing load (→ more dropped video frames). 24 is the
 * default — film-aimed. Amber = suboptimal, green = good. Null for out-of-range input (the field's
 * own "Must be 1–60" error covers that).
 */
private fun frameRateHint(fps: Int): Pair<String, Color>? {
    if (fps !in 1..60) return null
    val amber = Color(0xFFFFA000)
    val green = Color(0xFF66BB6A)
    return when {
        fps == 24 ->
            "✓ 24 fps suits 24fps movies/film — even, judder-free on most films (slightly uneven on 60fps content)." to green
        60 % fps == 0 && fps <= 30 ->
            "✓ $fps fps divides 60 evenly — smooth on 60fps content (YouTube, games, UI)." to green
        fps > 30 ->
            "$fps fps is high: more GPU compositing and more dropped video frames. 24 (film) or 30/20 is gentler." to amber
        else ->
            "$fps fps matches no common source rate, so it can judder. Use 24 for movies, or 30/20/15 for 60fps content." to amber
    }
}

/**
 * D-pad handling for TV text fields. A field stays read-only — and therefore never pops the
 * soft keyboard — while you merely navigate onto it with the D-pad. Pressing the centre/OK
 * button is the only thing that starts editing and shows the keyboard. Returns true only when
 * an activation press is consumed, so plain up/down focus navigation is never swallowed.
 */
private fun onTvFieldKey(event: KeyEvent, editing: Boolean, onStartEditing: () -> Unit): Boolean {
    if (editing) return false   // already editing: let the field/IME handle every key
    val isActivate = event.key == Key.DirectionCenter ||
        event.key == Key.Enter || event.key == Key.NumPadEnter
    if (!isActivate) return false
    if (event.type == KeyEventType.KeyUp) onStartEditing()
    return true   // consume both down and up so the platform doesn't also act on the press
}
