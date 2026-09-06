/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// LYREON adalah aplikasi dark-first: hitam pekat + crimson logo.
// Mode terang disediakan sebagai opsi bersih dan sederhana.
// Aksen bisa diganti bebas lewat [accentArgb] (-1 = crimson bawaan).

enum class ThemeMode { SYSTEM, DARK, LIGHT }

fun fontFamilyForKey(key: String): FontFamily = when (key) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}

private fun darkScheme(p: LyreonPalette) = darkColorScheme(
    primary = p.crimson,
    onPrimary = p.textBright,
    primaryContainer = p.crimson,
    onPrimaryContainer = p.textBright,
    secondary = p.rose,
    onSecondary = p.background,
    secondaryContainer = p.elevated,
    onSecondaryContainer = p.textPrimary,
    tertiary = p.rose,
    onTertiary = p.background,
    background = p.background,
    onBackground = p.textPrimary,
    surface = p.background,
    onSurface = p.textPrimary,
    surfaceVariant = p.surface,
    onSurfaceVariant = p.textSecondary,
    outline = p.line,
    outlineVariant = p.lineSoft,
    inverseSurface = p.textPrimary,
    inverseOnSurface = p.background,
    inversePrimary = p.crimson,
    scrim = p.scrim,
)

private fun lightScheme(p: LyreonPalette) = lightColorScheme(
    primary = p.crimson,
    onPrimary = p.textBright,
    primaryContainer = p.crimson,
    onPrimaryContainer = p.textBright,
    secondary = p.rose,
    onSecondary = p.textPrimary,
    secondaryContainer = p.elevated,
    onSecondaryContainer = p.textPrimary,
    tertiary = p.crimson,
    onTertiary = p.textBright,
    background = p.background,
    onBackground = p.textPrimary,
    surface = p.surface,
    onSurface = p.textPrimary,
    surfaceVariant = p.elevated,
    onSurfaceVariant = p.textSecondary,
    outline = p.line,
    outlineVariant = p.lineSoft,
    inverseSurface = p.textPrimary,
    inverseOnSurface = p.background,
    inversePrimary = p.crimson,
    scrim = p.scrim,
)

@Composable
fun LyreonTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentArgb: Int = -1,
    fontKey: String = "default",
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    // PENTING: warna opaque ARGB (0xFFxxxxxx) bernilai NEGATIF sebagai Int —
    // sentinel "bawaan" adalah tepat -1, bukan "negatif".
    val accent = if (accentArgb != -1) Color(accentArgb) else LyreonCrimsonDefault
    val palette = remember(dark, accent) {
        if (dark) lyreonDarkPalette(accent) else lyreonLightPalette(accent)
    }
    val typography = remember(fontKey) { lyreonTypography(fontFamilyForKey(fontKey)) }

    CompositionLocalProvider(LocalLyreonPalette provides palette) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(palette) else lightScheme(palette),
            typography = typography,
            content = content,
        )
    }
}
