package com.finmax.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/** FINMAX palette: ink-black base, cool slate surfaces, a single warm "projector gold" accent. */
object Fm {
    val Bg = Color(0xFF07090D)
    val Surface = Color(0xFF11151C)
    val SurfaceHigh = Color(0xFF1A2029)
    val Outline = Color(0xFF2A3240)
    val Text = Color(0xFFF2F4F7)
    val TextDim = Color(0xFFB4BCC8)
    val TextFaint = Color(0xFF7D8694)
    val Gold = Color(0xFFE8B04A)
    val GoldDeep = Color(0xFFB9862C)
    val Error = Color(0xFFFF7A70)
    val Success = Color(0xFF5BD69B)

    /** TV overscan-safe margins (≈5% of a 960×540 dp canvas). */
    val SafeH = 48.dp
    val SafeV = 27.dp
    val CardGap = 16.dp
}

private val typography = Typography(
    displayMedium = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
)

@Composable
fun FinmaxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Fm.Gold,
            onPrimary = Fm.Bg,
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
