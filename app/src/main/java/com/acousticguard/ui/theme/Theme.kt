package com.acousticguard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = SignalBlue,
    onPrimary = Color.White,
    primaryContainer = ColorTokens.lightBlueContainer,
    onPrimaryContainer = ColorTokens.lightBlueText,
    secondary = ColorTokens.lightSecondary,
    onSecondary = LightSurface,
    background = LightBackground,
    onBackground = LightPrimaryText,
    surface = LightSurface,
    onSurface = LightPrimaryText,
    surfaceVariant = LightSecondarySurface,
    onSurfaceVariant = LightSecondaryText,
    error = SignalRed,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = SignalBlue,
    onPrimary = Color.White,
    primaryContainer = ColorTokens.darkBlueContainer,
    onPrimaryContainer = ColorTokens.darkBlueText,
    secondary = ColorTokens.darkSecondary,
    onSecondary = DarkPrimaryText,
    background = DarkBackground,
    onBackground = DarkPrimaryText,
    surface = DarkSurface,
    onSurface = DarkPrimaryText,
    surfaceVariant = DarkSecondarySurface,
    onSurfaceVariant = DarkSecondaryText,
    error = SignalRed,
    onError = Color.White,
)

private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 32.sp,
            lineHeight = 39.sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            lineHeight = 29.sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 19.sp,
            lineHeight = 24.sp,
        ),
        bodyLarge = base.bodyLarge.copy(
            fontFamily = FontFamily.SansSerif,
            fontSize = 17.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            lineHeight = 21.sp,
        ),
        labelLarge = base.labelLarge.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
    )
}

@Composable
fun AcousticGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}

private object ColorTokens {
    val lightBlueContainer = Color(0xFFE6EBFF)
    val lightBlueText = Color(0xFF19358F)
    val lightSecondary = Color(0xFF5B6375)
    val darkBlueContainer = Color(0xFF253B91)
    val darkBlueText = Color(0xFFE7EBFF)
    val darkSecondary = Color(0xFFBAC4D9)
}
