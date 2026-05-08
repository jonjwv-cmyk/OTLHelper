package com.example.otlhelper.core.feedback

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.collectLatest

/**
 * CompositionLocal провайдер [FeedbackService]. HomeScreen заводит его в
 * корне, внутри UI composable'ы могут брать прямо через `LocalFeedback.current`
 * без каскадного пробрасывания.
 */
val LocalFeedback = staticCompositionLocalOf<FeedbackService?> { null }

/**
 * Attach tap-haptic к любому `InteractionSource` (используется под капотом у
 * IconButton/Button/clickable). При каждом PressInteraction.Press срабатывает
 * короткий tick.
 *
 * Предпочтительный способ подключения хаптики, потому что InteractionSource
 * живёт один раз на composable — никаких лямбд-wrapper'ов вокруг onClick.
 */
@Composable
fun Modifier.hapticOnPress(interactionSource: InteractionSource): Modifier = composed {
    val feedback = LocalFeedback.current
    val view = LocalView.current
    androidx.compose.runtime.LaunchedEffect(interactionSource, feedback) {
        if (feedback == null) return@LaunchedEffect
        interactionSource.interactions.collectLatest { interaction: Interaction ->
            if (interaction is PressInteraction.Press) {
                feedback.tick(view)
            }
        }
    }
    this
}
