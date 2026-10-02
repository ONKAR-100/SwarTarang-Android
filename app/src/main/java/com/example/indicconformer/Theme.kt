package com.example.indicconformer

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 05 · Butter & Rust Palette (Light, Calm, Warm Cream & Pure White Aesthetic)
val AppBg = Color(0xFFFAF6EE)          // Soft warm butter cream background (calm, eye-friendly, no blue light)
val SurfaceWhite = Color(0xFFFFFFFF)    // Crisp clean pure white card surface
val SurfaceButter = Color(0xFFFEF3C7)   // Pale warm butter highlight card (recent voice note, active pill)
val SurfaceRaised = Color(0xFFF5EDE0)   // Elevated warm beige surface
val Border = Color(0xFFEADBCE)          // Soft warm clay border
val BorderButter = Color(0xFFF2D99E)    // Warm golden butter border
val Rust = Color(0xFFBA5D3F)            // Grounded Rust / Terracotta (Primary CTA buttons, Sender badges)
val RustDark = Color(0xFFA14C30)        // Deep rust pressed state
val ButterYellow = Color(0xFFEAB308)   // Sunny Golden Butter Accent
val ButterSoft = Color(0xFFFEF9C3)     // Light Butter Cream
val WarmAmber = Color(0xFFD97706)      // Warm Amber / Golden Ochre
val TextDark = Color(0xFF221712)        // Deep warm chocolate espresso text (high legibility, zero eye fatigue)
val TextMuted = Color(0xFF7A6E65)       // Toasted sand / warm taupe secondary text
val TextAmber = Color(0xFFB45309)       // Amber subtitle text (e.g. GOOD AFTERNOON, RECENT)
val PureWhite = Color(0xFFFFFFFF)       // Crisp pure white for text on Rust buttons
val SoftWhite = TextDark                // Compatibility: mapped to dark espresso for all general UI text
val Muted = TextMuted                   // Secondary text
val Ink = AppBg                         // Screen background: Soft warm butter cream
val Surface = SurfaceWhite              // Primary surface is clean white
val Success = Color(0xFF16A34A)         // Calming herbal sage green
val AlertRed = Color(0xFFDC2626)        // Warm crimson alert

// Compatibility aliases
val Violet = Rust
val Cyan = ButterYellow
val Blue = WarmAmber

private val ButterRustLightColors = lightColorScheme(
    primary = Rust,
    secondary = ButterYellow,
    tertiary = WarmAmber,
    background = AppBg,
    surface = SurfaceWhite,
    surfaceVariant = SurfaceRaised,
    onBackground = TextDark,
    onSurface = TextDark,
    onSurfaceVariant = TextMuted,
    onPrimary = PureWhite
)

@Composable
fun MarathiVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ButterRustLightColors, typography = Typography(), content = content)
}
