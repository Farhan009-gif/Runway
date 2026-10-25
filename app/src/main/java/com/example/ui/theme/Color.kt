package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Minimal Premium Monochrome + Soft Indigo (#6C7CFF) Design System
val SoftIndigo = Color(0xFF6C7CFF)
val SoftIndigoDim = Color(0x1F6C7CFF)

// Dark Palette (Default)
val DarkBackground = Color(0xFF0B0D10)
val DarkSurface = Color(0xFF14171C)
val DarkElevatedSurface = Color(0xFF1B1F26)
val DarkDivider = Color(0x14FFFFFF) // 1px subtle border: white at 8% opacity

val TextPrimary = Color(0xFFF3F4F6)
val TextSecondary = Color(0xFF8E95A2)
val TextMuted = Color(0xFF5E6572)

// Light Palette
val LightBackground = Color(0xFFF8F9FB)
val LightSurface = Color(0xFFFFFFFF)
val LightElevatedSurface = Color(0xFFF1F3F7)
val LightDivider = Color(0x14000000) // 1px subtle border: black at 8% opacity

val TextPrimaryLight = Color(0xFF111318)
val TextSecondaryLight = Color(0xFF68707E)

// Status Dot Colors (Minimal)
val StatusGreenDot = Color(0xFF34D399)
val StatusGrayDot = Color(0xFF6B7280)

// Backward-compatible semantic aliases mapped to the single Soft Indigo accent & neutral grays
val MutedAccent = SoftIndigo
val MutedAccentContainer = SoftIndigoDim
val VioletAccent = SoftIndigo
val VioletAccentContainer = SoftIndigoDim
val SkyCyanAccent = SoftIndigo
val SkyCyanContainer = SoftIndigoDim

val EmeraldConnected = StatusGreenDot
val EmeraldSuccess = StatusGreenDot
val CoralError = Color(0xFFEF4444)
val CoralErrorContainer = Color(0x1FEF4444)
val OnCoralErrorContainer = Color(0xFFFCA5A5)
val AmberWarning = Color(0xFF9CA3AF)

val ObsidianCanvas = DarkBackground
val SlateSurface = DarkSurface
val SlateElevated = DarkElevatedSurface
val SlateBorder = DarkDivider

val CyberCyan = SoftIndigo
val CyberCyanContainer = SoftIndigoDim
val ElectricIndigo = SoftIndigo
val ElectricIndigoContainer = SoftIndigoDim
val ElectricViolet = SoftIndigo

val TextPrimaryDark = TextPrimary
val TextSecondaryDark = TextSecondary

val AlabasterCanvas = LightBackground
val WhiteSurface = LightSurface
val SoftSlateElevated = LightElevatedSurface
val LightBorder = LightDivider
val DeepIndigo = SoftIndigo
val DeepIndigoContainer = SoftIndigoDim
val DeepCyan = SoftIndigo
val DeepCyanContainer = SoftIndigoDim
