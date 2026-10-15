package com.naman.reelrein.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF9896EC)
val Accent2 = Color(0xFFC2B5F2)
val Lavender = Color(0xFFC8C5F7)
val LavenderInk = Color(0xFF2D2A72)
val AccentBrush = Brush.linearGradient(listOf(Color(0xFF8583DE), Color(0xFFB4A6EE)))
val Good = Color(0xFF7FD8B5)
val Warn = Color(0xFFF2C08A)

// One Sec-like calm palette: soft lavender on near-black with a warm undertone.
private val Dark = darkColorScheme(
    primary = Lavender,
    onPrimary = LavenderInk,
    primaryContainer = Color(0xFF3A3880),
    onPrimaryContainer = Color(0xFFE4E2FF),
    secondary = Accent2,
    background = Color(0xFF16171A),
    onBackground = Color(0xFFF1F0F6),
    surface = Color(0xFF16171A),
    onSurface = Color(0xFFF1F0F6),
    surfaceVariant = Color(0xFF26272C),
    onSurfaceVariant = Color(0xFFA9A8B6),
    surfaceContainerLowest = Color(0xFF111214),
    surfaceContainerLow = Color(0xFF1B1C20),
    surfaceContainer = Color(0xFF1F2024),
    surfaceContainerHigh = Color(0xFF26272C),
    surfaceContainerHighest = Color(0xFF2E2F35),
    outline = Color(0xFF4B4C57),
    outlineVariant = Color(0xFF2C2D33),
    error = Color(0xFFFF8A8F),
)

private val Light = lightColorScheme(
    primary = Color(0xFF5B58C9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E0FF),
    onPrimaryContainer = Color(0xFF15166B),
    secondary = Color(0xFF8A4FD6),
    background = Color(0xFFF6F6FC),
    onBackground = Color(0xFF16172A),
    surface = Color(0xFFF6F6FC),
    onSurface = Color(0xFF16172A),
    surfaceVariant = Color(0xFFE6E6F2),
    onSurfaceVariant = Color(0xFF5B5E78),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFBFF),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0F0F8),
    surfaceContainerHighest = Color(0xFFE8E8F3),
    outline = Color(0xFFA9ABC4),
    outlineVariant = Color(0xFFDDDDEB),
    error = Color(0xFFD23C4B),
)

@Composable
fun ReelReinTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
