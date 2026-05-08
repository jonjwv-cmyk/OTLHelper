package com.example.otlhelper.desktop.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val OtldDarkColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = BgApp,
    primaryContainer = BgCard,
    onPrimaryContainer = TextPrimary,

    secondary = TextSecondary,
    onSecondary = BgApp,
    secondaryContainer = BgCard,
    onSecondaryContainer = TextPrimary,

    tertiary = AccentMuted,
    onTertiary = TextPrimary,
    tertiaryContainer = AccentSubtle,
    onTertiaryContainer = TextPrimary,

    background = BgApp,
    onBackground = TextPrimary,
    surface = BgCard,
    onSurface = TextPrimary,
    surfaceVariant = BgCard,
    onSurfaceVariant = TextSecondary,

    surfaceContainer = BgElevated,
    surfaceContainerLow = BgCard,
    surfaceContainerHigh = BgElevated,
    surfaceContainerHighest = BgElevated,
    surfaceContainerLowest = BgInput,

    outline = BorderDivider,
    outlineVariant = BorderDivider,

    error = StatusErrorBorder,
    onError = TextPrimary,
)

@Composable
fun OtldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OtldDarkColorScheme,
        typography = OtldTypography,
        content = content,
    )
}
