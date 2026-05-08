package com.example.otlhelper.desktop.ui.components

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.otlhelper.desktop.theme.Accent

/**
 * §TZ-DESKTOP-0.10.2 — Modifier который рисует radial glow за курсором
 * мыши когда мышь над элементом. Юзер просил «свечение за мышкой
 * усиливается, мы видим как она движется».
 *
 * Использует `pointerInput` для трекинга позиции + `drawWithContent`
 * для рендера полупрозрачного gradient'а ПОД контентом (не закрывает
 * текст / иконки).
 *
 * Применять к Row/Box/Surface чтобы добавить hover-glow эффект.
 *
 * @param interaction для синхронизации hover state с другими modifier'ами
 *                    (например clickable). Если null — создаётся внутренний.
 * @param glowColor цвет свечения (default Accent с alpha)
 * @param radiusDp радиус gradient'а
 */
@Composable
fun Modifier.cursorGlow(
    interaction: MutableInteractionSource? = null,
    glowColor: Color = Accent,
    radiusDp: Int = 90,
): Modifier = composed {
    val source = interaction ?: remember { MutableInteractionSource() }
    val isHovered by source.collectIsHoveredAsState()
    var cursor by remember { mutableStateOf(Offset.Unspecified) }

    this
        .hoverable(source)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val pos = event.changes.firstOrNull()?.position
                    if (pos != null) cursor = pos
                }
            }
        }
        .drawWithContent {
            drawContent()
            if (isHovered && cursor != Offset.Unspecified) {
                val r = radiusDp.dp.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = 0.22f),
                            glowColor.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                        center = cursor,
                        radius = r,
                    ),
                    center = cursor,
                    radius = r,
                )
            }
        }
}
