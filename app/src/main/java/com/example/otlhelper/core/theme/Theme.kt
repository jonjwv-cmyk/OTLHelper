package com.example.otlhelper.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

enum class ThemeMode {
    STANDARD,   // фирменная тёмная с бронзой (дефолт)
    DARK,       // нейтральная AMOLED тёмная
    LIGHT,      // светлая дневная
}

private fun buildDarkScheme(c: OtlColors) = darkColorScheme(
    primary             = c.accent,
    onPrimary           = c.bgApp,
    primaryContainer    = c.bgCard,
    onPrimaryContainer  = c.textPrimary,
    secondary           = c.textSecondary,
    onSecondary         = c.bgApp,
    secondaryContainer  = c.bgCard,
    onSecondaryContainer = c.textPrimary,
    tertiary            = c.accentMuted,
    onTertiary          = c.textPrimary,
    tertiaryContainer   = c.accentSubtle,
    onTertiaryContainer = c.textPrimary,
    background          = c.bgApp,
    onBackground        = c.textPrimary,
    surface             = c.bgCard,
    onSurface           = c.textPrimary,
    surfaceVariant      = c.bgCard,
    onSurfaceVariant    = c.textSecondary,
    surfaceContainer        = c.bgElevated,
    surfaceContainerLow     = c.bgCard,
    surfaceContainerHigh    = c.bgElevated,
    surfaceContainerHighest = c.bgElevated,
    surfaceContainerLowest  = c.bgInput,
    outline        = c.borderDivider,
    outlineVariant = c.borderDivider,
    error   = c.statusErrorBorder,
    onError = c.textPrimary,
)

private fun buildLightScheme(c: OtlColors) = lightColorScheme(
    primary             = c.accent,
    onPrimary           = c.bgApp,
    primaryContainer    = c.accentSubtle,
    onPrimaryContainer  = c.accent,
    secondary           = c.textSecondary,
    onSecondary         = c.bgApp,
    secondaryContainer  = c.bgCard,
    onSecondaryContainer = c.textPrimary,
    tertiary            = c.accentMuted,
    onTertiary          = c.bgApp,
    tertiaryContainer   = c.accentSubtle,
    onTertiaryContainer = c.accent,
    background          = c.bgApp,
    onBackground        = c.textPrimary,
    surface             = c.bgCard,
    onSurface           = c.textPrimary,
    surfaceVariant      = c.bgInput,
    onSurfaceVariant    = c.textSecondary,
    surfaceContainer        = c.bgElevated,
    surfaceContainerLow     = c.bgInput,
    surfaceContainerHigh    = c.bgElevated,
    surfaceContainerHighest = c.bgElevated,
    surfaceContainerLowest  = c.bgApp,
    outline        = c.borderDivider,
    outlineVariant = c.borderDivider,
    error   = c.statusErrorBorder,
    onError = c.bgApp,
)

@Composable
fun OtlTheme(
    themeMode: ThemeMode = ThemeMode.STANDARD,
    content: @Composable () -> Unit,
) {
    val colors = when (themeMode) {
        ThemeMode.STANDARD -> standardColors()
        ThemeMode.DARK     -> darkColors()
        ThemeMode.LIGHT    -> lightColors()
    }
    val colorScheme = if (colors.isDark) buildDarkScheme(colors) else buildLightScheme(colors)

    CompositionLocalProvider(
        LocalOtlColors provides colors,
        com.example.otlhelper.core.designsystem.LocalSpacing
            provides com.example.otlhelper.core.designsystem.DesignTokens.spacing,
        com.example.otlhelper.core.designsystem.LocalRadii
            provides com.example.otlhelper.core.designsystem.DesignTokens.radii,
        com.example.otlhelper.core.designsystem.LocalElevation
            provides com.example.otlhelper.core.designsystem.DesignTokens.elevation,
        com.example.otlhelper.core.designsystem.LocalBlur
            provides com.example.otlhelper.core.designsystem.DesignTokens.blur,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = OtlTypography,
            content     = content,
        )
    }
}
