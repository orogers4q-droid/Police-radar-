package com.policeradar.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PoliceRed = Color(0xFFEF4444)
val PoliceBlue = Color(0xFF3B82F6)
val SafeGreen = Color(0xFF22C55E)
val WarnAmber = Color(0xFFF59E0B)

private val colors = darkColorScheme(
    primary = PoliceRed,
    onPrimary = Color.White,
    secondary = PoliceBlue,
    onSecondary = Color.White,
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    errorContainer = Color(0xFF3F1D24),
    onErrorContainer = Color(0xFFFECACA),
)

@Composable
fun PoliceRadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
