package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun JarvisTheme(
    primaryHue: Float = 187f, // Default Cyan (187 deg)
    content: @Composable () -> Unit
) {
    val dynamicColors = generateHudColors(primaryHue)
    val primaryColor = dynamicColors[0]
    val secondaryColor = dynamicColors[1]
    val tertiaryColor = dynamicColors[2]

    val colorScheme = darkColorScheme(
        primary = primaryColor,
        onPrimary = Color(0xFF001F26),
        primaryContainer = Color(0xFF004D5A),
        onPrimaryContainer = Color(0xFF80F0FF),
        secondary = secondaryColor,
        onSecondary = Color(0xFF00201F),
        secondaryContainer = Color(0xFF004F4C),
        onSecondaryContainer = Color(0xFF6EFFF0),
        tertiary = tertiaryColor,
        background = DarkHudBackground,
        onBackground = TextPrimary,
        surface = DarkHudSurface,
        onSurface = TextPrimary,
        surfaceVariant = DarkHudSurfaceVariant,
        onSurfaceVariant = TextSecondary,
        outline = DarkHudBorder
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                // MainActivity already calls enableEdgeToEdge(), so the bars are transparent and
                // the HUD background is painted behind them by the root Box. Setting
                // statusBarColor/navigationBarColor here was a no-op from API 35 onward and
                // deprecated in 35, so only the icon appearance is configured.
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
