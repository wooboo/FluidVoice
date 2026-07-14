package com.fluidvoice.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val FluidPrimary = Color(0xFFAFC1E8)
val FluidOrange = Color(0xFFF47B57)
val FluidGreen = Color(0xFF60C39A)
val FluidBackground = Color(0xFF0C1017)
val FluidSurface = Color(0xFF141A24)
val FluidInk = Color(0xFFE7EAF0)
val FluidMuted = Color(0xFFADB5C2)

private val FluidColors = darkColorScheme(
    primary = FluidPrimary,
    onPrimary = Color(0xFF15213A),
    primaryContainer = Color(0xFF293650),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = FluidOrange,
    onSecondary = Color(0xFF3D1005),
    secondaryContainer = Color(0xFF65301F),
    onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = FluidGreen,
    onTertiary = Color(0xFF003826),
    background = FluidBackground,
    onBackground = FluidInk,
    surface = FluidSurface,
    onSurface = FluidInk,
    surfaceVariant = Color(0xFF232B37),
    onSurfaceVariant = FluidMuted,
    outline = Color(0xFF788292),
    error = Color(0xFFFFB4AB),
)

internal object CaptureChrome {
    const val PROMPT_NOTE = 0xFFA8462C
    const val REJECT = 0xFFA93643
    const val CONFIRM = 0xFF2E8B68
}

private val FluidTypography = androidx.compose.material3.Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 30.sp,
        lineHeight = 35.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
    ),
)

@Composable
fun FluidVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FluidColors,
        typography = FluidTypography,
        content = content,
    )
}
