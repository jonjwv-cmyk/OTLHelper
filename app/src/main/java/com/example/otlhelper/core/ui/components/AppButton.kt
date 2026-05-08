package com.example.otlhelper.core.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.BgApp
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.StatusErrorBorder
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.UnreadGreen
import com.example.otlhelper.core.ui.animations.AppMotion
import kotlinx.coroutines.delay

/**
 * Premium app-wide button.
 *
 * Behavior:
 *   - Idle: scales 1.0 → 0.96 on press, plus haptic LIGHT tick
 *   - Loading: shows a small spinner; ignores clicks
 *   - Success: morphs to a green checkmark for ~1.2s, then back to Idle
 *   - Error:   morphs to a red cross for ~1.6s, then back to Idle
 *
 * Drive state from the caller via `state`; the button never owns the loading
 * lifecycle — the caller flips state when the network result arrives.
 */
enum class AppButtonState { Idle, Loading, Success, Error }

enum class AppButtonStyle { Primary, Secondary, Destructive }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: AppButtonState = AppButtonState.Idle,
    style: AppButtonStyle = AppButtonStyle.Primary,
    enabled: Boolean = true
) {
    val (bg, fg) = when (style) {
        AppButtonStyle.Primary -> Accent to BgApp
        AppButtonStyle.Secondary -> BgCard to TextPrimary
        AppButtonStyle.Destructive -> StatusErrorBorder to TextPrimary
    }
    val effectiveBg = when (state) {
        AppButtonState.Success -> UnreadGreen
        AppButtonState.Error -> StatusErrorBorder
        else -> bg
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current

    // Press scale — spring-driven, identical to AppMotion language
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && state == AppButtonState.Idle) 0.96f else 1f,
        animationSpec = AppMotion.SpringStiff,
        label = "press_scale"
    )

    // Light haptic tick on first press
    LaunchedEffect(isPressed) {
        if (isPressed && state == AppButtonState.Idle) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val clickable = enabled && state == AppButtonState.Idle

    Surface(
        onClick = { if (clickable) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .scale(pressScale),
        shape = RoundedCornerShape(14.dp),
        color = effectiveBg.copy(alpha = if (clickable) 1f else 0.55f),
        contentColor = fg,
        interactionSource = interactionSource,
        enabled = clickable
    ) {
        Box(contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    (scaleIn(animationSpec = AppMotion.SpringStandard, initialScale = 0.6f) +
                            fadeIn(animationSpec = AppMotion.SpringStandard))
                        .togetherWith(
                            scaleOut(animationSpec = AppMotion.SpringStandard, targetScale = 0.6f) +
                                    fadeOut(animationSpec = AppMotion.SpringStandard)
                        )
                },
                label = "button_state"
            ) { current ->
                when (current) {
                    AppButtonState.Idle ->
                        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

                    AppButtonState.Loading ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = LocalContentColor.current,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(text, color = fg.copy(alpha = 0.85f), fontSize = 14.sp)
                        }

                    AppButtonState.Success ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✓", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text("Готово", color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }

                    AppButtonState.Error ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✕", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text("Ошибка", color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                }
            }
        }
    }

    // Haptic burst on terminal states
    LaunchedEffect(state) {
        when (state) {
            AppButtonState.Success -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            AppButtonState.Error -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                delay(80)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            else -> {}
        }
    }
}

/**
 * Returns an [InteractionSource] paired with a `Modifier.scale` that springs
 * down on press. Use when you want premium press-feel on a composable that
 * already owns its own click handler (e.g. M3 IconButton, FloatingActionButton).
 *
 * Usage:
 *     val (source, mod) = rememberAppPressFeel()
 *     IconButton(onClick = ..., interactionSource = source,
 *         modifier = Modifier.then(mod)) { ... }
 */
@Composable
fun rememberAppPressFeel(scaleDown: Float = 0.92f): Pair<MutableInteractionSource, Modifier> {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val s by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = AppMotion.SpringStiff,
        label = "app_press_feel"
    )
    LaunchedEffect(isPressed) {
        if (isPressed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    return interactionSource to Modifier.scale(s)
}
