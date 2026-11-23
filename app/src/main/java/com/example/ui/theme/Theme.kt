package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = LuminousCyan,
    onPrimary = CobaltNavy,
    primaryContainer = DarkSurfaceAlt,
    onPrimaryContainer = DarkTextPrimary,
    secondary = SpectralTealLight,
    onSecondary = CobaltNavy,
    secondaryContainer = Color(0xFF0F3834),
    onSecondaryContainer = SpectralTealLight,
    tertiary = AmberGlow,
    onTertiary = CobaltNavy,
    tertiaryContainer = Color(0xFF3A280A),
    onTertiaryContainer = AmberGlow,
    background = DarkCanvas,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceAlt,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkOutline,
    error = CrimsonSoft,
    onError = CobaltNavy
)

private val LightColorScheme = lightColorScheme(
    primary = IndustrialCyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEEFB),
    onPrimaryContainer = CobaltNavy,
    secondary = SpectralTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F5F0),
    onSecondaryContainer = Color(0xFF064E46),
    tertiary = AmberInspect,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF0D6),
    onTertiaryContainer = Color(0xFF78350F),
    background = LightCanvas,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceAlt,
    onSurfaceVariant = LightTextSecondary,
    outline = LightOutline,
    error = CrimsonAlert,
    onError = Color.White
)

@Composable
fun SpectraQcTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
