package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

val InterFontFamily = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium)
)

val MonospaceFontFamily = FontFamily.Monospace
val JetBrainsMonoFontFamily = InterFontFamily
val SpaceGroteskFontFamily = InterFontFamily
val PlusJakartaFontFamily = InterFontFamily

// Strictly 3 font sizes across the entire app:
// - 20sp (Title) — Medium weight
// - 15sp (Body) — Regular weight (Medium for emphasized section headers)
// - 12sp (Caption) — Regular weight
private val Title20Style = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.2).sp
)

private val Body15Style = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.sp
)

private val Caption12Style = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.sp
)

val Typography = Typography(
    displayLarge = Title20Style,
    displayMedium = Title20Style,
    displaySmall = Title20Style,
    headlineLarge = Title20Style,
    headlineMedium = Title20Style,
    headlineSmall = Title20Style,
    titleLarge = Title20Style,
    titleMedium = Title20Style,
    titleSmall = Body15Style.copy(fontWeight = FontWeight.Medium),
    bodyLarge = Body15Style,
    bodyMedium = Body15Style,
    bodySmall = Caption12Style,
    labelLarge = Body15Style,
    labelMedium = Caption12Style,
    labelSmall = Caption12Style
)
