package com.hyperflatsender.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * Fraction of each screen edge kept clear of app UI — a TV safe-area margin. Beyond the usual
 * overscan nicety, it keeps the app's own chrome away from the very screen edges so that if the
 * app's screen is the one being mirrored, no UI element lands in the capture's edge regions (which
 * map to the Hyperion LEDs).
 */
const val TV_SAFE_MARGIN_FRACTION = 0.16f

/**
 * Insets content by [TV_SAFE_MARGIN_FRACTION] of the screen width (left/right) and height
 * (top/bottom), so no UI element sits within 16% of any TV edge.
 */
@Composable
fun Modifier.tvSafeArea(): Modifier {
    val config = LocalConfiguration.current
    val horizontal = (config.screenWidthDp * TV_SAFE_MARGIN_FRACTION).dp
    val vertical = (config.screenHeightDp * TV_SAFE_MARGIN_FRACTION).dp
    return padding(horizontal = horizontal, vertical = vertical)
}
