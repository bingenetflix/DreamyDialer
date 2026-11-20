package com.local.dreamydialer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dreamy Lavender & Periwinkle Palette
val LavenderBg = Color(0xFFF4F3FB)
val LavenderSurface = Color(0xFFFFFFFF)
val LavenderSearchBg = Color(0xFFE9E7F5)
val LavenderPillLight = Color(0xFFE7E4F8)
val LavenderHalo = Color(0xFFD6D0F7)
val PeriwinklePrimary = Color(0xFF6D63D9)
val InkPrimary = Color(0xFF1E1D2B)
val SlateMuted = Color(0xFF8C8A9E)
val HairlineLavender = Color(0xFFE3E0F2)
val CallGreen = Color(0xFF22C55E)
val EndRed = Color(0xFFEF4444)

private val DialerColorScheme = lightColorScheme(
    primary = PeriwinklePrimary,
    onPrimary = LavenderSurface,
    background = LavenderBg,
    onBackground = InkPrimary,
    surface = LavenderSurface,
    onSurface = InkPrimary,
    surfaceVariant = LavenderSearchBg,
    onSurfaceVariant = SlateMuted,
    outline = HairlineLavender
)

@Composable
fun DreamyDialerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DialerColorScheme,
        content = content
    )
}
