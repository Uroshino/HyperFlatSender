package com.hyperflatsender.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Primary = Color(0xFF00BCD4)
private val OnPrimary = Color(0xFF000000)
private val Background = Color(0xFF0D0D0D)
private val Surface = Color(0xFF1A1A2E)
private val OnSurface = Color(0xFFE0E0E0)
private val Error = Color(0xFFCF6679)

private val HyperionColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    background = Background,
    surface = Surface,
    onSurface = OnSurface,
    error = Error,
    onBackground = OnSurface
)

@Composable
fun HyperionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HyperionColorScheme,
        content = content
    )
}
