package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Default Futuristic Cybernetic Palette
val CyanPrimary = Color(0xFF00E5FF)
val CyanGlow = Color(0xFF80F0FF)
val DarkHudBackground = Color(0xFF080C14)
val DarkHudSurface = Color(0xFF0F1726)
val DarkHudSurfaceVariant = Color(0xFF162238)
val DarkHudBorder = Color(0xFF1E3A5F)
val NeonAccent = Color(0xFF00FFCC)
val AmberAlert = Color(0xFFFFB300)
val CrimsonAlert = Color(0xFFFF3366)
val TextPrimary = Color(0xFFE2F1FF)
val TextSecondary = Color(0xFF8BA2BE)
val TextTertiary = Color(0xFF5A718C)

// Function to generate dynamic M3 ColorScheme based on a primary hue (0..360)
fun generateHudColors(hue: Float): List<Color> {
    val primary = Color.hsv(hue, 0.85f, 0.95f)
    val secondary = Color.hsv((hue + 30f) % 360f, 0.70f, 0.90f)
    val tertiary = Color.hsv((hue + 180f) % 360f, 0.75f, 0.90f)
    return listOf(primary, secondary, tertiary)
}
