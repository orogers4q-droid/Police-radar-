package com.bleradar.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.bleradar.app.detect.Threat

val RadarCyan = Color(0xFF22D3EE)
val AlertRed = Color(0xFFEF4444)
val WarnAmber = Color(0xFFF59E0B)
val SafeGreen = Color(0xFF22C55E)
val Muted = Color(0xFF94A3B8)

fun threatColor(t: Threat): Color = when (t) {
    Threat.HIGH -> AlertRed
    Threat.MEDIUM -> WarnAmber
    Threat.LOW -> RadarCyan
    Threat.NONE -> Muted
}

private val colors = darkColorScheme(
    primary = RadarCyan,
    onPrimary = Color(0xFF04222A),
    secondary = WarnAmber,
    onSecondary = Color.Black,
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF16213A),
    onSurfaceVariant = Muted,
    errorContainer = Color(0xFF3F1D24),
    onErrorContainer = Color(0xFFFECACA),
)

@Composable
fun BleRadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
