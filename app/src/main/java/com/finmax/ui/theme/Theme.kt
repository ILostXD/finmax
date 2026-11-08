package com.finmax.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.finmax.R

/**
 * FINMAX palette, from the brand card:
 * - Chlorophyll Void `#1B3100`: the deep green the whole UI sits in (base, glass panels, washes).
 * - Taut Orchid `#8D3F7D`: the brand colour (primary actions, progress, focus glow).
 * - Ionized Parchment `#FFF2C5`: warm light (text, the focus fill).
 */
object Fm {
    // Brand
    val Void = Color(0xFF1B3100)
    val Orchid = Color(0xFF8D3F7D)
    val Parchment = Color(0xFFFFF2C5)

    // Base and surfaces: near-black tinted with Chlorophyll Void
    val Bg = Color(0xFF090D05)
    val Surface = Color(0xFF121A0B)
    val SurfaceHigh = Color(0xFF1C2712)
    val Outline = Color(0xFF34402A)

    // Text: parchment-warm whites
    val Text = Color(0xFFFFF8E2)
    val TextDim = Color(0xFFCFC8AC)
    val TextFaint = Color(0xFF8E8B74)

    /** Readable orchid for text, rings and progress on dark backgrounds (6:1 on [Bg]). */
    val Accent = Color(0xFFD486C4)
    /** Fill of the focused control, and the colour drawn on it. */
    val Focus = Parchment
    val OnFocus = Color(0xFF142400)

    val Error = Color(0xFFFF8A7A)
    val Success = Color(0xFFB9DB7A)

    /** TV overscan-safe margins (≈5% of a 960×540 dp canvas). */
    val SafeH = 48.dp
    val SafeV = 27.dp
    val CardGap = 16.dp
}

/**
 * Bricolage Grotesque (variable: optical size 12–96, width 75–100, weight 200–800), the typeface of
 * the finmax logo. The logo sits around weight 600, so titles are built from 600 rather than the
 * heaviest cut. Optical size follows the role: display masters for titles, text masters for reading.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun bricolage(weight: Int, opticalSize: Float) = Font(
    R.font.bricolage_grotesque,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", opticalSize),
        FontVariation.width(100f),
    ),
)

/** Text optical size for reading. */
val BodyFont = FontFamily(bricolage(400, 14f), bricolage(500, 14f), bricolage(600, 14f), bricolage(700, 14f))

/** Display optical size (the logo's look): titles and big numbers. */
val DisplayFont = FontFamily(bricolage(500, 96f), bricolage(600, 96f), bricolage(700, 96f), bricolage(800, 96f))

/** Small-size masters for buttons and tracked uppercase overlines. */
val WideFont = FontFamily(bricolage(500, 12f), bricolage(600, 12f), bricolage(700, 12f))

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = DisplayFont, fontSize = 56.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.2).sp, lineHeight = 56.sp),
    displayMedium = TextStyle(fontFamily = DisplayFont, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, lineHeight = 42.sp),
    displaySmall = TextStyle(fontFamily = DisplayFont, fontSize = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = DisplayFont, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = DisplayFont, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = BodyFont, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = BodyFont, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = BodyFont, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = BodyFont, fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = WideFont, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontFamily = BodyFont, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = WideFont, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
)

@Composable
fun FinmaxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Fm.Orchid,
            onPrimary = Fm.Parchment,
            secondary = Fm.Accent,
            background = Fm.Bg,
            onBackground = Fm.Text,
            surface = Fm.Surface,
            onSurface = Fm.Text,
            surfaceVariant = Fm.SurfaceHigh,
            onSurfaceVariant = Fm.TextDim,
            border = Fm.Outline,
            error = Fm.Error,
        ),
        typography = typography,
        content = content,
    )
}
