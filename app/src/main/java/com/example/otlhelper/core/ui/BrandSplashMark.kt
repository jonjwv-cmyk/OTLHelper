package com.example.otlhelper.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.otlhelper.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ── Palette ──────────────────────────────────────────────────────────────
private val BrandTrack = Color(0xFF5F5B67)
private val BrandArrow = Color(0xFFF7C657)
private val GlowWarmInner = Color(0xFFFFE59A)   // pale warm yellow (inner bloom)
private val GlowAmber     = Color(0xFFF7C657)   // mid amber (halo body)
private val GlowDeep      = Color(0xFFE6941C)   // deeper orange (outer tail)

/**
 * Branded splash mark — SF-grade choreography.
 *
 * The system splash is intentionally blank, so Compose gets a clean black
 * canvas and plays the full sequence without a visual handoff. This is the
 * Linear / Arc / Superhuman pattern: the splash IS the brand moment, not
 * a transition from the launcher icon.
 *
 * Choreography:
 *   1. Halo anticipation — soft warm bloom expands before the pin arrives
 *   2. Pin drop — falls from -52dp with spring physics, slight tilt unwinds
 *   3. Impact — haptic tick + expanding ring + ground shadow appear
 *   4. Route reveal — dashed track marches left→right
 *   5. Arrow emerge — slides out trailing a motion-blur streak
 *   6. Idle drift — arrow gently drifts, halo breathes
 *
 * Layers (back → front):
 *   • Outer halo (multi-stop warm radial)
 *   • Inner glow (tight pale core hugging the pin)
 *   • Impact ring (one-shot expanding stroke on pin-land)
 *   • Ground shadow (elliptical, grounds the pin)
 *   • Dashed route + shimmer sweep
 *   • Pin (PNG) — the hero element
 *   • Arrow (PNG) + streak
 */
@Composable
fun BrandSplashMark(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    // Pin drops from above (where the icon was on the system splash).
    // Larger start offset = more convincing "fell from icon" perception.
    val pinStartOffset = with(density) { (-52).dp.toPx() }
    val arrowStartOffset = with(density) { (-10).dp.toPx() }
    val arrowIntroTravel = with(density) { 18.dp.toPx() }
    val arrowIdleTravel = with(density) { 8.dp.toPx() }

    val outerHaloAlpha = remember { Animatable(0f) }
    val outerHaloScale = remember { Animatable(0.55f) }
    val innerGlowAlpha = remember { Animatable(0f) }
    val innerGlowScale = remember { Animatable(0.85f) }
    val impactRingAlpha = remember { Animatable(0f) }
    val impactRingScale = remember { Animatable(0.4f) }
    val groundShadowAlpha = remember { Animatable(0f) }
    val pinOffsetY = remember(pinStartOffset) { Animatable(pinStartOffset) }
    val pinRotation = remember { Animatable(-4f) }
    val pinScale = remember { Animatable(0.88f) }
    val pinAlpha = remember { Animatable(0f) }
    val arrowAlpha = remember { Animatable(0f) }
    val arrowOffsetX = remember(arrowStartOffset) { Animatable(arrowStartOffset) }
    val trackAlpha = remember { Animatable(0f) }
    val trackReveal = remember { Animatable(0f) }

    LaunchedEffect(pinStartOffset, arrowStartOffset, arrowIntroTravel, arrowIdleTravel) {
        outerHaloAlpha.snapTo(0f)
        outerHaloScale.snapTo(0.55f)
        innerGlowAlpha.snapTo(0f)
        innerGlowScale.snapTo(0.85f)
        impactRingAlpha.snapTo(0f)
        impactRingScale.snapTo(0.4f)
        groundShadowAlpha.snapTo(0f)
        pinOffsetY.snapTo(pinStartOffset)
        pinRotation.snapTo(-4f)
        pinScale.snapTo(0.88f)
        pinAlpha.snapTo(0f)
        arrowAlpha.snapTo(0f)
        arrowOffsetX.snapTo(arrowStartOffset)
        trackAlpha.snapTo(0f)
        trackReveal.snapTo(0f)

        // 1) ANTICIPATION — halo blooms where the pin will land
        kotlinx.coroutines.coroutineScope {
            launch { outerHaloAlpha.animateTo(0.38f, tween(260, easing = FastOutSlowInEasing)) }
            launch { outerHaloScale.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
        }

        // 2) DROP — pin falls from icon position with spring bounce + tilt
        kotlinx.coroutines.coroutineScope {
            launch { pinAlpha.animateTo(1f, tween(180, easing = LinearEasing)) }
            launch {
                pinOffsetY.animateTo(
                    0f,
                    spring(dampingRatio = 0.54f, stiffness = 260f)
                )
            }
            launch {
                pinRotation.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 320f))
            }
            launch {
                pinScale.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 340f))
            }
            launch {
                delay(140)
                innerGlowAlpha.animateTo(0.62f, tween(260))
                innerGlowScale.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 280f))
            }
        }

        // 3) IMPACT — haptic tick + expanding ring + ground shadow appears
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        kotlinx.coroutines.coroutineScope {
            launch {
                impactRingAlpha.snapTo(0.55f)
                impactRingScale.snapTo(0.45f)
                launch { impactRingScale.animateTo(1.9f, tween(680, easing = FastOutSlowInEasing)) }
                launch { impactRingAlpha.animateTo(0f, tween(680, easing = FastOutSlowInEasing)) }
            }
            launch { outerHaloAlpha.animateTo(0.22f, tween(500)) }
            launch { groundShadowAlpha.animateTo(0.40f, tween(320)) }
            launch {
                pinScale.animateTo(1.05f, tween(120))
                pinScale.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
            }
        }

        // 4) ROUTE — dashes march left→right
        delay(40)
        kotlinx.coroutines.coroutineScope {
            launch { trackAlpha.animateTo(0.62f, tween(280)) }
            launch { trackReveal.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
        }

        // 5) ARROW — emerges and rides
        kotlinx.coroutines.coroutineScope {
            launch { arrowAlpha.animateTo(1f, tween(240)) }
            launch {
                arrowOffsetX.animateTo(
                    arrowIntroTravel,
                    tween(720, easing = FastOutSlowInEasing)
                )
            }
        }

        // 6) STEADY-STATE — gentle drift
        while (isActive) {
            arrowOffsetX.animateTo(
                arrowIntroTravel + arrowIdleTravel,
                tween(1100, easing = FastOutSlowInEasing)
            )
            arrowOffsetX.animateTo(
                arrowIntroTravel,
                tween(1100, easing = FastOutSlowInEasing)
            )
        }
    }

    // Ambient infinite — track shimmer + halo breathing
    val infinite = rememberInfiniteTransition(label = "brand_ambient")
    val trackSweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "track_sweep"
    )
    val haloBreath by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo_breath"
    )
    val innerGlowBreath by infinite.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "inner_breath"
    )

    Box(modifier = modifier.size(width = 228.dp, height = 118.dp)) {

        // ── BG layer 1: Outer halo — big soft ambient bloom ────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = with(density) { 63.dp.toPx() }
            val cy = size.height * 0.50f
            val base = with(density) { 78.dp.toPx() }
            val radius = base * outerHaloScale.value * haloBreath
            val aOuter = outerHaloAlpha.value
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to GlowWarmInner.copy(alpha = aOuter * 0.95f),
                        0.35f to GlowAmber.copy(alpha = aOuter * 0.50f),
                        0.70f to GlowDeep.copy(alpha = aOuter * 0.20f),
                        1.00f to Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = radius
                ),
                radius = radius,
                center = Offset(cx, cy)
            )
        }

        // ── BG layer 2: Inner glow — tight pale core around pin ────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = with(density) { 63.dp.toPx() }
            val cy = size.height * 0.46f
            val radius = with(density) { 40.dp.toPx() } * innerGlowScale.value * innerGlowBreath
            val a = innerGlowAlpha.value
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to GlowWarmInner.copy(alpha = a * 0.90f),
                        0.50f to GlowAmber.copy(alpha = a * 0.35f),
                        1.00f to Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = radius
                ),
                radius = radius,
                center = Offset(cx, cy)
            )
        }

        // ── BG layer 3: Impact ring — expanding stroke on pin-land ──────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = with(density) { 63.dp.toPx() }
            val cy = size.height * 0.52f
            val r = with(density) { 34.dp.toPx() } * impactRingScale.value
            val stroke = with(density) { 1.6.dp.toPx() }
            drawCircle(
                color = GlowWarmInner.copy(alpha = impactRingAlpha.value),
                radius = r,
                center = Offset(cx, cy),
                style = StrokeStyle(width = stroke)
            )
        }

        // ── BG layer 4: Ground shadow — soft ellipse below pin ──────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = with(density) { 63.dp.toPx() }
            val cy = with(density) { 100.dp.toPx() }
            val w = with(density) { 48.dp.toPx() }
            val h = with(density) { 8.dp.toPx() }
            val a = groundShadowAlpha.value
            drawOval(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to Color(0xFF000000).copy(alpha = a * 0.65f),
                        0.70f to Color(0xFF000000).copy(alpha = a * 0.15f),
                        1.00f to Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = w / 2f
                ),
                topLeft = Offset(cx - w / 2f, cy - h / 2f),
                size = Size(w, h)
            )
        }

        // ── Dashed route + moving shimmer ──────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val routeY = size.height * 0.73f
            val routeStart = size.width * 0.28f
            val routeEnd = size.width * 0.90f
            val revealedEnd = routeStart + (routeEnd - routeStart) * trackReveal.value
            val stroke = with(density) { 3.dp.toPx() }
            val dash = floatArrayOf(with(density) { 11.dp.toPx() }, with(density) { 8.dp.toPx() })

            if (revealedEnd > routeStart) {
                drawLine(
                    color = BrandTrack.copy(alpha = trackAlpha.value),
                    start = Offset(routeStart, routeY),
                    end = Offset(revealedEnd, routeY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(dash)
                )
                val sweepWidth = (routeEnd - routeStart) * 0.22f
                val sweepStart = routeStart + (routeEnd - routeStart - sweepWidth) * trackSweep
                if (sweepStart + sweepWidth <= revealedEnd + stroke) {
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                BrandArrow.copy(alpha = 0.14f + trackAlpha.value * 0.72f),
                                Color.Transparent
                            ),
                            startX = sweepStart,
                            endX = sweepStart + sweepWidth
                        ),
                        start = Offset(sweepStart, routeY),
                        end = Offset(sweepStart + sweepWidth, routeY),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // ── Pin — sits beneath the icon overlay; extraction lifts it, drop lands it
        Image(
            painter = painterResource(id = R.drawable.brand_pin),
            contentDescription = null,
            modifier = Modifier
                .offset(x = 24.dp, y = 0.dp)
                .size(width = 78.dp, height = 108.dp)
                .graphicsLayer {
                    translationY = pinOffsetY.value
                    alpha = pinAlpha.value
                    scaleX = pinScale.value
                    scaleY = pinScale.value
                    rotationZ = pinRotation.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.9f)
                }
        )

        // Icon overlay removed — it was creating a visible second pin that
        // didn't align with brand_pin.png. Now the animation is clean:
        // system splash shows the icon → Compose takes over with the pin
        // doing its extract/drop motion at the same position.

        // ── Arrow streak + arrow ───────────────────────────────────────
        Canvas(
            modifier = Modifier
                .offset(x = 126.dp, y = 61.dp)
                .size(width = 72.dp, height = 48.dp)
        ) {
            val tailWidth = with(density) { 28.dp.toPx() }
            val tailY = size.height * 0.48f
            val alpha = (arrowAlpha.value * 0.55f).coerceAtMost(0.55f)
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, BrandArrow.copy(alpha = alpha))
                ),
                start = Offset(-tailWidth + arrowOffsetX.value, tailY),
                end = Offset(arrowOffsetX.value + with(density) { 6.dp.toPx() }, tailY),
                strokeWidth = with(density) { 3.dp.toPx() },
                cap = StrokeCap.Round
            )
        }
        Image(
            painter = painterResource(id = R.drawable.brand_arrow),
            contentDescription = null,
            modifier = Modifier
                .offset(x = 126.dp, y = 61.dp)
                .size(width = 72.dp, height = 48.dp)
                .graphicsLayer {
                    translationX = arrowOffsetX.value
                    alpha = arrowAlpha.value
                }
        )
    }
}
