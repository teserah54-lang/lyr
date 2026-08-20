package com.lyreon.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// LYREON — "Hear What Words Can't Say"
// Palet DINAMIS: token di bawah ini adalah getter @Composable yang membaca
// palette dari CompositionLocal — sehingga mode terang benar-benar berganti
// dan warna aksen bisa dipilih bebas oleh pengguna.

// ------------------------------------------------------------------
// Warna dasar (internal, dipakai membangun palette)
// ------------------------------------------------------------------

/** Crimson bawaan LYREON (diambil dari logo). */
val LyreonCrimsonDefault = Color(0xFFE94560)

private val DarkBackground = Color(0xFF0B0B10)
private val DarkSurface = Color(0xFF14141C)
private val DarkElevated = Color(0xFF1C1C26)
private val DarkTextPrimary = Color(0xFFF4F4F8)
private val DarkTextBright = Color(0xFFFFFFFF)
private val DarkTextSecondary = Color(0xFFA3A3AF)
private val DarkTextMuted = Color(0xFF71717D)
private val DarkLine = Color(0xFF282832)
private val DarkLineSoft = Color(0xFF22222C)
private val DarkScrim = Color(0xE60B0B10)

// Palet terang "alabaster hangat" — bukan kertas putih silau: redup, hangat,
// dan harmonis dengan crimson tanpa terasa mencolok.
private val LightBackground = Color(0xFFF0EBE4)
private val LightSurface = Color(0xFFF7F3ED)
private val LightElevated = Color(0xFFFCFAF6)
private val LightTextPrimary = Color(0xFF23201E)
private val LightTextBright = Color(0xFFFFFFFF)
private val LightTextSecondary = Color(0xFF565049)
private val LightTextMuted = Color(0xFF8A8178)
private val LightLine = Color(0xFFE0D7CB)
private val LightLineSoft = Color(0xFFEAE3D7)
private val LightScrim = Color(0x7A23201E)

/** Cerahkan warna aksen (untuk varian "rose"). */
internal fun lighten(c: Color, amount: Float): Color {
    val r = c.red + (1f - c.red) * amount
    val g = c.green + (1f - c.green) * amount
    val b = c.blue + (1f - c.blue) * amount
    return Color(red = r.coerceIn(0f, 1f), green = g.coerceIn(0f, 1f), blue = b.coerceIn(0f, 1f), alpha = c.alpha)
}

/** Gelapkan warna aksen sedikit agar kontras di latar terang. */
internal fun darken(c: Color, amount: Float): Color =
    Color(
        red = (c.red * (1f - amount)).coerceIn(0f, 1f),
        green = (c.green * (1f - amount)).coerceIn(0f, 1f),
        blue = (c.blue * (1f - amount)).coerceIn(0f, 1f),
        alpha = c.alpha,
    )

// ------------------------------------------------------------------
// Palette dinamis
// ------------------------------------------------------------------

data class LyreonPalette(
    val background: Color,
    val surface: Color,
    val elevated: Color,
    val textPrimary: Color,
    val textBright: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val line: Color,
    val lineSoft: Color,
    val scrim: Color,
    val crimson: Color,
    val rose: Color,
)

fun lyreonDarkPalette(accent: Color = LyreonCrimsonDefault) = LyreonPalette(
    background = DarkBackground,
    surface = DarkSurface,
    elevated = DarkElevated,
    textPrimary = DarkTextPrimary,
    textBright = DarkTextBright,
    textSecondary = DarkTextSecondary,
    textMuted = DarkTextMuted,
    line = DarkLine,
    lineSoft = DarkLineSoft,
    scrim = DarkScrim,
    crimson = accent,
    rose = lighten(accent, 0.25f),
)

fun lyreonLightPalette(accent: Color = LyreonCrimsonDefault): LyreonPalette {
    val strong = darken(accent, 0.08f)
    return LyreonPalette(
        background = LightBackground,
        surface = LightSurface,
        elevated = LightElevated,
        textPrimary = LightTextPrimary,
        textBright = LightTextBright,
        textSecondary = LightTextSecondary,
        textMuted = LightTextMuted,
        line = LightLine,
        lineSoft = LightLineSoft,
        scrim = LightScrim,
        crimson = strong,
        rose = accent,
    )
}

/** Palette aktif — di-provide oleh LyreonTheme. */
val LocalLyreonPalette = staticCompositionLocalOf { lyreonDarkPalette() }

// ------------------------------------------------------------------
// Token publik — nama dipertahankan agar semua layar langsung ikut tema
// ------------------------------------------------------------------

val LyreonBackground: Color @Composable @ReadOnlyComposable get() = LocalLyreonPalette.current.background
val LyreonSurface: Color @Composable @ReadOnlyComposable get() = LocalLyreonPalette.current.surface
val LyreonElevated: Color @Composable @ReadOnlyComposable get() = LocalLyreonPalette.current.elevated
val LyreonTextPrimary: Color @Composable @ReadOnlyComposable get() = LocalLyreonPalette.current.textPrimary
val LyreonTextBright: Color @Composable @ReadOnlyComposable get() = LocalLyreonPalette.current.textBright
val LyreonTextSecondary: Color @Composable @ReadOnlyComposable get() = LocalLyreonPalette.current.textSecondary
val LyreonTextMuted: Color @Composable @ReadOnlyComposable get() = LocalLyreonPalette.current.textMuted
val LyreonLine: Color @Composable @ReadOnlyComposable get() = LocalLyreonPalette.current.line
val LyreonLineSoft: Color @Composable @ReadOnlyComposable get() = LocalLyreonPalette.current.lineSoft
val LyreonScrim: Color @Composable @ReadOnlyComposable get() = LocalLyreonPalette.current.scrim
val LyreonCrimson: Color @Composable @ReadOnlyComposable get() = LocalLyreonPalette.current.crimson
val LyreonRose: Color @Composable @ReadOnlyComposable get() = LocalLyreonPalette.current.rose

// Token lama yang jarang dipakai — dipertahankan sebagai konstanta statis
val LyreonTextSoft = Color(0xFFCDCDD6)
val LyreonTextDim = Color(0xFF4E4E5A)
val LyreonTextFaint = Color(0xFF7A7A86)
val LyreonSurfaceAlt = Color(0xFF12121A)
val LyreonStroke = Color(0x1AFFFFFF)
val LyreonOverlay = Color(0xCC0B0B10)
val LyreonPaperBackground = Color(0xFFF0EBE4)
val LyreonPaperSurface = Color(0xFFF7F3ED)
val LyreonPaperText = Color(0xFF23201E)
