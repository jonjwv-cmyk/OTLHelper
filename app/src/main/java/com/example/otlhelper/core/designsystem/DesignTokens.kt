package com.example.otlhelper.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single source of truth for OTL's visual language.
 *
 * TZ Priority 1 — "Linear, Notion, Figma Mobile в 2026 полностью работают
 * только через Design Tokens". Change a value here and the whole app picks
 * it up on next recomposition.
 *
 * Existing token surfaces already live in their own files and remain the
 * authority for that dimension:
 *   - Colors     → [com.example.otlhelper.core.theme] (Color.kt)
 *   - Typography → [com.example.otlhelper.core.theme.OtlTypography]
 *   - Motion     → [com.example.otlhelper.core.ui.animations.AppMotion]
 *
 * This file adds the three dimensions that were scattered across composables:
 * spacing, corner radii, elevation, and blur.
 *
 * Usage — prefer the MaterialTheme extension so themes can override them:
 *     padding(MaterialTheme.spacing.md)
 *     RoundedCornerShape(MaterialTheme.radii.lg)
 *
 * For code outside a @Composable, the object defaults are available:
 *     DesignTokens.spacing.md
 */

// ── Spacing ───────────────────────────────────────────────────────────────
// 4-based scale. Most layouts in the app already cluster around these values
// (2, 4, 8, 12, 16, 24, 32) — the scale codifies that.
data class Spacing(
    val none: Dp = 0.dp,
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
)

// ── Corner radii ──────────────────────────────────────────────────────────
// Matches the app's existing card language: 12 for cards, 16 for hero,
// 999 for pill. `glass` = the frosted-card radius used by OtlCard.
data class Radii(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val glass: Dp = 16.dp,
    val pill: Dp = 999.dp,
)

// ── Elevation ─────────────────────────────────────────────────────────────
// Dp values map 1:1 to Material 3 elevation tokens. Keep them on a tight
// scale — overuse of shadow is the fastest way to look like a 2019 app.
data class Elevation(
    val level0: Dp = 0.dp,
    val level1: Dp = 1.dp,
    val level2: Dp = 3.dp,
    val level3: Dp = 6.dp,
    val level4: Dp = 8.dp,
    val level5: Dp = 12.dp,
)

// ── Blur / glassmorphism ──────────────────────────────────────────────────
// Dp radii for `Modifier.blur()`. Android 12+ blur is GPU-cheap; below 12
// the app falls back to opaque surface colors (handled at call site).
data class Blur(
    val subtle: Dp = 4.dp,
    val medium: Dp = 12.dp,
    val strong: Dp = 24.dp,
)

// ── Aggregate — the "DesignTokens" object the TZ asked for ────────────────
// Object form for non-Composable access (e.g. Android View styles, dialogs
// constructed before composition).
object DesignTokens {
    val spacing: Spacing = Spacing()
    val radii: Radii = Radii()
    val elevation: Elevation = Elevation()
    val blur: Blur = Blur()
}

// ── CompositionLocals ─────────────────────────────────────────────────────
// Static because our tokens don't change within a session. If we later
// introduce a compact-mode or A11y scale toggle, promote these to
// `compositionLocalOf` so surface swaps trigger recomposition.
val LocalSpacing = staticCompositionLocalOf { DesignTokens.spacing }
val LocalRadii = staticCompositionLocalOf { DesignTokens.radii }
val LocalElevation = staticCompositionLocalOf { DesignTokens.elevation }
val LocalBlur = staticCompositionLocalOf { DesignTokens.blur }

// ── MaterialTheme extensions ──────────────────────────────────────────────
// `MaterialTheme.spacing.md` reads well and nests naturally with the
// colors / typography you already use from MaterialTheme.
val MaterialTheme.spacing: Spacing
    @Composable
    @ReadOnlyComposable
    get() = LocalSpacing.current

val MaterialTheme.radii: Radii
    @Composable
    @ReadOnlyComposable
    get() = LocalRadii.current

val MaterialTheme.elevation: Elevation
    @Composable
    @ReadOnlyComposable
    get() = LocalElevation.current

val MaterialTheme.blur: Blur
    @Composable
    @ReadOnlyComposable
    get() = LocalBlur.current
