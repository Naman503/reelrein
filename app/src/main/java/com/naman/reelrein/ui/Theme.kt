package com.naman.reelrein.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF8E90FF)
val Accent2 = Color(0xFFC08CFF)
val AccentBrush = Brush.linearGradient(listOf(Color(0xFF6C6EF5), Color(0xFFA35BF5)))
val Good = Color(0xFF3DD6A3)
val Warn = Color(0xFFFFB454)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF16174D),
    primaryContainer = Color(0xFF2B2C6B),
    onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Accent2,
    background = Color(0xFF0D0E19),
    onBackground = Color(0xFFE8E8F4),
    surface = Color(0xFF0D0E19),
    onSurface = Color(0xFFE8E8F4),
    surfaceVariant = Color(0xFF22243A),
    onSurfaceVariant = Color(0xFFA4A7C4),
    surfaceContainerLowest = Color(0xFF0A0B14),
    surfaceContainerLow = Color(0xFF131423),
    surfaceContainer = Color(0xFF171829),
    surfaceContainerHigh = Color(0xFF1E2033),
    surfaceContainerHighest = Color(0xFF26283E),
    outline = Color(0xFF4A4D6B),
    outlineVariant = Color(0xFF2A2C44),
    error = Color(0xFFFF7A85),
)

private val Light = lightColorScheme(
    primary = Color(0xFF5557E0),
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
