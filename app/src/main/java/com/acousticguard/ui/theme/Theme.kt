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
    onPrimary = LightSurface,
    primaryContainer = ColorTokens.lightBlueContainer,
    onPrimaryContainer = LightPrimaryText,
    secondary = LightSecondaryText,
    onSecondary = LightSurface,
    background = LightBackground,
    onBackground = LightPrimaryText,
    surface = LightSurface,
    onSurface = LightPrimaryText,
    surfaceVariant = LightSecondarySurface,
    onSurfaceVariant = LightSecondaryText,
    error = SignalRed,
    onError = LightSurface,
)

private val DarkColors = darkColorScheme(
    primary = SignalBlue,
    onPrimary = DarkPrimaryText,
    primaryContainer = ColorTokens.darkBlueContainer,
    onPrimaryContainer = DarkPrimaryText,
    secondary = DarkSecondaryText,
    onSecondary = DarkPrimaryText,
    background = DarkBackground,
    onBackground = DarkPrimaryText,
    surface = DarkSurface,
    onSurface = DarkPrimaryText,
    surfaceVariant = DarkSecondarySurface,
    onSurfaceVariant = DarkSecondaryText,
    error = SignalRed,
    onError = DarkPrimaryText,
)

private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 34.sp,
            lineHeight = 41.sp,
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
            fontSize = 20.sp,
            lineHeight = 25.sp,
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
    val lightBlueContainer = androidx.compose.ui.graphics.Color(0xFFD9ECFF)
    val darkBlueContainer = androidx.compose.ui.graphics.Color(0xFF123A63)
}
