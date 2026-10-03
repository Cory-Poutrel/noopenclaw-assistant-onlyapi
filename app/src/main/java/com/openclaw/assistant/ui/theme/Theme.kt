package com.openclaw.assistant.ui.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.openclaw.assistant.R
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val OpenClawColorScheme = darkColorScheme(
    primary = OpenClawOrange,
    secondary = OpenClawPopYellow,
    tertiary = OpenClawOrange,
    background = OpenClawDarkGrey,
    surface = OpenClawSurfaceGrey,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = OpenClawTextPrimary,
    onSurface = OpenClawTextPrimary,
    error = OpenClawError
)

private val OpenClawLightColorScheme = lightColorScheme(
    primary = OpenClawOrange,
    secondary = OpenClawPopYellow,
    tertiary = OpenClawOrange,
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onTertiary = Color.White,
    onBackground = Color(0xFF1B1B1B),
    onSurface = Color(0xFF1B1B1B),
    error = OpenClawError
)

private val VoiceOverlayDarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    background = OpenClawDarkGrey,
    surface = OpenClawSurfaceGrey,
    onBackground = OpenClawTextPrimary,
    onSurface = OpenClawTextPrimary,
    onSurfaceVariant = OpenClawTextSecondary,
    error = Color(0xFFFFB4AB)
)

private val VoiceOverlayLightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    onBackground = Color(0xFF1B1B1B),
    onSurface = Color(0xFF1B1B1B),
    onSurfaceVariant = Color(0xFF49454F),
    error = Color(0xFFBA1A1A)
)

// Manrope TTF not bundled — use system sans-serif to avoid FontNotFoundException
val Manrope = FontFamily.SansSerif

private val AppTypography = Typography(
    headlineLarge = Typography().headlineLarge.copy(fontFamily = Manrope),
    headlineMedium = Typography().headlineMedium.copy(fontFamily = Manrope),
    headlineSmall = Typography().headlineSmall.copy(fontFamily = Manrope),
    titleLarge = Typography().titleLarge.copy(fontFamily = Manrope),
    titleMedium = Typography().titleMedium.copy(fontFamily = Manrope),
    titleSmall = Typography().titleSmall.copy(fontFamily = Manrope),
    bodyLarge = Typography().bodyLarge.copy(fontFamily = Manrope),
    bodyMedium = Typography().bodyMedium.copy(fontFamily = Manrope),
    bodySmall = Typography().bodySmall.copy(fontFamily = Manrope),
    labelLarge = Typography().labelLarge.copy(fontFamily = Manrope),
    labelMedium = Typography().labelMedium.copy(fontFamily = Manrope),
    labelSmall = Typography().labelSmall.copy(fontFamily = Manrope)
)

@Composable
fun OpenClawAssistantTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) OpenClawColorScheme else OpenClawLightColorScheme
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val context = view.context
            if (context is Activity) {
                val window = context.window
                window.statusBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}

@Composable
fun VoiceOverlayTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) VoiceOverlayDarkColorScheme else VoiceOverlayLightColorScheme,
        typography = AppTypography,
        content = content
    )
}
