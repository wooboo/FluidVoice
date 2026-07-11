package com.fluidvoice.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val FluidNavy = Color(0xFF141C30)
val FluidOrange = Color(0xFFE15B38)
val FluidGreen = Color(0xFF237A5A)
val FluidPaper = Color(0xFFF5F6F1)
val FluidInk = Color(0xFF202534)
val FluidMuted = Color(0xFF656B78)

private val FluidColors = lightColorScheme(
    primary = FluidNavy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E7F1),
    onPrimaryContainer = FluidNavy,
    secondary = FluidOrange,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCCF),
    onSecondaryContainer = Color(0xFF3E1207),
    tertiary = FluidGreen,
    onTertiary = Color.White,
    background = FluidPaper,
    onBackground = FluidInk,
    surface = Color(0xFFFCFCF8),
    onSurface = FluidInk,
    surfaceVariant = Color(0xFFE9EAE4),
    onSurfaceVariant = FluidMuted,
    outline = Color(0xFFBFC2BC),
    error = Color(0xFFB3261E),
)

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
