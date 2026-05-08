package com.example.otlhelper.core.ui.animations

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * App-wide motion tokens.
 *
 * Single source of truth for springs, durations, and enter/exit specs — so any
 * surface in the app (sheets, chat, pills, cards, buttons) moves identically.
 *
 * Premium feel: spring-driven, damping ≈ 0.82, medium-low stiffness.
 * Reduced motion: fades only, no slide/scale (respects system a11y setting).
 */
object AppMotion {

    // ── Spring specs ──────────────────────────────────────────────────────────
    val SpringStandard = spring<Float>(
        dampingRatio = 0.82f,
        stiffness = 380f
    )

    val SpringBouncy = spring<Float>(
        dampingRatio = 0.60f,
        stiffness = 420f
    )

    val SpringStiff = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    // ── IntOffset variants (for slideIn/slideOut) ─────────────────────────────
    val SpringStandardOffset = spring<IntOffset>(
        dampingRatio = 0.82f,
        stiffness = 380f
    )

    val SpringBouncyOffset = spring<IntOffset>(
        dampingRatio = 0.60f,
        stiffness = 420f
    )

    val SpringStandardSize = spring<IntSize>(
        dampingRatio = 0.82f,
        stiffness = 380f
    )

    val SpringStandardDp = spring<Dp>(
        dampingRatio = 0.82f,
        stiffness = 380f
    )

    // ── Durations ────────────────────────────────────────────────────────────
    const val DurationQuick = 160
    const val DurationBase = 260
    const val DurationBaseMs = 260
    const val DurationSlow = 420

    // ── Enter / exit combos ───────────────────────────────────────────────────
    val SlideInFromTop = slideInVertically(
        animationSpec = SpringStandardOffset,
        initialOffsetY = { -it }
    ) + fadeIn(animationSpec = SpringStandard)

    val SlideOutToTop = slideOutVertically(
        animationSpec = SpringStandardOffset,
        targetOffsetY = { -it }
    ) + fadeOut(animationSpec = SpringStandard)

    val ScaleInSheet = scaleIn(
        animationSpec = SpringStandard,
        initialScale = 0.95f
    ) + fadeIn(animationSpec = SpringStandard)

    val ScaleOutSheet = scaleOut(
        animationSpec = SpringStandard,
        targetScale = 0.95f
    ) + fadeOut(animationSpec = SpringStandard)

    val BubbleEnter = slideInVertically(
        animationSpec = SpringBouncyOffset,
        initialOffsetY = { it / 3 }
    ) + fadeIn(animationSpec = SpringBouncy) + scaleIn(
        animationSpec = SpringBouncy,
        initialScale = 0.94f
    )

    val NewsItemEnter = fadeIn(animationSpec = tween(DurationBase)) +
            scaleIn(animationSpec = SpringStandard, initialScale = 0.98f)

    // ── Reduced motion variants (fade only) ──────────────────────────────────
    // When system "Remove animations" is on, these replace the full transitions.
    private val FadeInOnly: EnterTransition = fadeIn(animationSpec = tween(DurationQuick))
    private val FadeOutOnly: ExitTransition = fadeOut(animationSpec = tween(DurationQuick))

    /**
     * Check system reduced motion preference. Call from @Composable context.
     * Returns true when the user has set animator duration scale to 0.
     */
    @Composable
    @ReadOnlyComposable
    fun isReducedMotion(): Boolean {
        val context = LocalContext.current
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
        return scale == 0f
    }

    /** Use in AnimatedVisibility: respects system reduced motion. */
    @Composable
    @ReadOnlyComposable
    fun enterSheet(): EnterTransition = if (isReducedMotion()) FadeInOnly else ScaleInSheet

    @Composable
    @ReadOnlyComposable
    fun exitSheet(): ExitTransition = if (isReducedMotion()) FadeOutOnly else ScaleOutSheet

    @Composable
    @ReadOnlyComposable
    fun enterSlideTop(): EnterTransition = if (isReducedMotion()) FadeInOnly else SlideInFromTop

    @Composable
    @ReadOnlyComposable
    fun exitSlideTop(): ExitTransition = if (isReducedMotion()) FadeOutOnly else SlideOutToTop
}
