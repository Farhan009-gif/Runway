package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val GeminiDarkBg = DarkBackground
val GeminiSurface = DarkSurface
val GeminiSurfaceElevated = DarkElevatedSurface
val GeminiAccent = SoftIndigo
val GeminiEmerald = EmeraldSuccess
val GeminiCoral = CoralError
val GeminiAmber = AmberWarning
val GeminiTextPrimary = TextPrimary
val GeminiTextSecondary = TextSecondary
val GeminiBorder = DarkDivider

private val RunwayDarkColorScheme = darkColorScheme(
    primary = SoftIndigo,
    onPrimary = Color.White,
    primaryContainer = SoftIndigoDim,
    onPrimaryContainer = TextPrimary,
    secondary = TextSecondary,
    onSecondary = DarkBackground,
    secondaryContainer = DarkElevatedSurface,
    onSecondaryContainer = TextPrimary,
    tertiary = EmeraldSuccess,
    onTertiary = Color.White,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkElevatedSurface,
    onSurfaceVariant = TextSecondary,
    outline = DarkDivider,
    outlineVariant = DarkDivider,
    error = CoralError,
    onError = Color.White
)

private val RunwayLightColorScheme = lightColorScheme(
    primary = SoftIndigo,
    onPrimary = Color.White,
    primaryContainer = SoftIndigoDim,
    onPrimaryContainer = TextPrimaryLight,
    secondary = TextSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = LightElevatedSurface,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = EmeraldSuccess,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightElevatedSurface,
    onSurfaceVariant = TextSecondaryLight,
    outline = LightDivider,
    outlineVariant = LightDivider,
    error = CoralError,
    onError = Color.White
)

@Composable
fun OmniAIStudioTheme(
    themeMode: String = "DARK",
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDarkTheme = when (themeMode.uppercase()) {
        "LIGHT" -> false
        "SYSTEM" -> systemDark
        else -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDarkTheme -> RunwayDarkColorScheme
        else -> RunwayLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
