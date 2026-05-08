package com.example.otlhelper.desktop.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.AccentMuted
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.BubbleOther
import com.example.otlhelper.desktop.theme.BubbleOwn
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import kotlin.math.max

/**
 * §TZ-DESKTOP-0.1.0 — desktop-копия app/core/ui/ChatBubble.kt.
 *
 * Layout fidelity:
 *  - bubble padding start=10, end=10, top=6, bottom=6; widthIn max 290.dp.
 *  - inline meta: время + галочки рисуются на последней строке текста если
 *    там есть место, иначе — под текстом справа (Telegram «time hugs last word»).
 *  - галочки — Canvas-drawn (SingleCheck 13×10, DoubleCheck 17×10, Clock 11dp),
 *    НЕ emoji. Accent на read, AccentMuted на sent, TextTertiary на pending.
 *
 * Right-click → [onRequestAction] (desktop-эквивалент long-press).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChatBubble(
    text: String,
    time: String,
    isOwn: Boolean,
    isRead: Boolean = false,
    isPending: Boolean = false,
    reactions: Map<String, Int> = emptyMap(),
    myReactions: Set<String> = emptySet(),
    attachments: List<com.example.otlhelper.desktop.data.feed.NewsRepository.Attachment> = emptyList(),
    onReactionToggle: (emoji: String, alreadyReacted: Boolean) -> Unit = { _, _ -> },
    onReactionLongPress: ((emoji: String) -> Unit)? = null,
    onRequestAction: () -> Unit = {},
) {
    val bubbleShape = if (isOwn) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 290.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(bubbleShape)
                    .background(if (isOwn) BubbleOwn else BubbleOther)
                    .onPointerEvent(PointerEventType.Press) { e ->
                        if (e.buttons.isSecondaryPressed) onRequestAction()
                    },
            ) {
                Column(modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)) {
                    val metaSlot: @Composable () -> Unit = {
                        MessageMeta(time = time, isOwn = isOwn, isPending = isPending, isRead = isRead)
                    }
                    // §TZ-DESKTOP-0.1.0 этап 5 — attachments ВЫШЕ текста/meta
                    // (зеркалит app/core/ui/ChatBubble.kt). Ограничиваем шириной 260dp.
                    if (attachments.isNotEmpty()) {
                        com.example.otlhelper.desktop.ui.components.AttachmentsView(
                            attachments = attachments,
                            modifier = Modifier.widthIn(max = 260.dp),
                        )
                        if (text.isNotBlank()) Spacer(Modifier.height(6.dp))
                    }
                    if (text.isNotBlank()) {
                        TextWithInlineMeta(
                            text = text,
                            textStyle = TextStyle(
                                color = TextPrimary,
                                fontSize = 15.sp,
                                lineHeight = 20.sp,
                            ),
                            meta = metaSlot,
                        )
                    } else if (attachments.isEmpty()) {
                        // Только если ни текста, ни attachments — показываем одиночный meta-row.
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) { metaSlot() }
                    } else {
                        // Text пустой, но attachments есть — meta прижимается к правому
                        // нижнему углу отдельной строкой.
                        Row(
                            modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) { metaSlot() }
                    }
                    // §TZ-DESKTOP-0.1.0 этап 4c+ — реакции внутри пузыря. Клик по чипу
                    // toggle, long-press/right-click → voters (dev only), «+» для добавления.
                    if (reactions.isNotEmpty() || onReactionLongPress != null) {
                        Spacer(Modifier.height(4.dp))
                        com.example.otlhelper.desktop.ui.components.ReactionsBar(
                            aggregate = reactions,
                            myReactions = myReactions,
                            onToggle = { emoji -> onReactionToggle(emoji, emoji in myReactions) },
                            showAddButton = true,
                            onChipLongPress = onReactionLongPress,
                        )
                    }
                }
            }
        }
    }
}


/**
 * Измеряет текст дважды: первый раз чтобы узнать правый край последней строки,
 * второй — чтобы нарисовать. Если meta помещается на последней строке с
 * учётом gap'а — рисуется inline, иначе переносится под текстом справа.
 * Это тот же «Telegram time hugs last word» lookup что в Android-копии.
 */
@Composable
private fun TextWithInlineMeta(
    text: String,
    textStyle: TextStyle,
    meta: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val annotated = remember(text) { AnnotatedString(text) }

    SubcomposeLayout(modifier) { constraints ->
        val metaPlaceable = subcompose("meta") { meta() }
            .first()
            .measure(Constraints(maxWidth = constraints.maxWidth))
        val metaW = metaPlaceable.width
        val metaH = metaPlaceable.height
        val gapPx = 6.dp.roundToPx()

        val layoutResult = measurer.measure(
            text = annotated,
            style = textStyle,
            constraints = Constraints(maxWidth = constraints.maxWidth),
        )
        val lineCount = layoutResult.lineCount
        val textHeight = layoutResult.size.height
        val textWidth = layoutResult.size.width
        val lastLineIdx = (lineCount - 1).coerceAtLeast(0)
        val lastLineRight = layoutResult.getLineRight(lastLineIdx)
        val lastLineTop = layoutResult.getLineTop(lastLineIdx)
        val lastLineBottom = layoutResult.getLineBottom(lastLineIdx)
        val lastLineHeight = lastLineBottom - lastLineTop

        val roomOnLast = constraints.maxWidth - lastLineRight.toInt()
        val inline = roomOnLast >= metaW + gapPx

        val totalWidth: Int
        val totalHeight: Int
        val metaX: Int
        val metaY: Int

        if (inline) {
            totalWidth = max(textWidth, (lastLineRight.toInt() + gapPx + metaW))
            val lineCenter = lastLineTop + lastLineHeight / 2f
            val tentativeMetaY = (lineCenter - metaH / 2f + 1.dp.toPx()).toInt()
                .coerceAtLeast(lastLineTop.toInt())
            val extraBottom = (tentativeMetaY + metaH - textHeight).coerceAtLeast(0)
            totalHeight = textHeight + extraBottom
            metaX = totalWidth - metaW
            metaY = tentativeMetaY
        } else {
            totalWidth = max(textWidth, metaW)
            totalHeight = textHeight + 2.dp.roundToPx() + metaH
            metaX = totalWidth - metaW
            metaY = textHeight + 2.dp.roundToPx()
        }

        val textPlaceable = subcompose("text") {
            Text(text = text, style = textStyle)
        }.first().measure(Constraints(maxWidth = constraints.maxWidth))

        layout(totalWidth, totalHeight) {
            textPlaceable.place(0, 0)
            metaPlaceable.place(metaX, metaY)
        }
    }
}

@Composable
private fun MessageMeta(
    time: String,
    isOwn: Boolean,
    isPending: Boolean,
    isRead: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            time,
            color = if (isOwn) Accent.copy(alpha = 0.65f) else TextTertiary.copy(alpha = 0.85f),
            fontSize = 11.sp,
            letterSpacing = 0.1.sp,
        )
        if (isOwn) {
            Spacer(Modifier.width(4.dp))
            ChatStatusIcon(isPending = isPending, isRead = isRead)
        }
    }
}

/** clock (pending) / single check (sent) / double check (read) */
@Composable
private fun ChatStatusIcon(isPending: Boolean, isRead: Boolean) {
    when {
        isPending -> PendingClockIcon()
        isRead -> DoubleCheckIcon(color = Accent)
        else -> SingleCheckIcon(color = AccentMuted)
    }
}

@Composable
private fun SingleCheckIcon(color: Color) {
    Canvas(modifier = Modifier.size(width = 13.dp, height = 10.dp)) {
        val stroke = 1.5.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * 0.02f, size.height * 0.55f)
            lineTo(size.width * 0.38f, size.height * 0.90f)
            lineTo(size.width * 0.98f, size.height * 0.12f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun DoubleCheckIcon(color: Color) {
    Canvas(modifier = Modifier.size(width = 17.dp, height = 10.dp)) {
        val stroke = 1.5.dp.toPx()
        val back = Path().apply {
            moveTo(size.width * 0.02f, size.height * 0.55f)
            lineTo(size.width * 0.30f, size.height * 0.90f)
            lineTo(size.width * 0.68f, size.height * 0.12f)
        }
        val front = Path().apply {
            moveTo(size.width * 0.34f, size.height * 0.55f)
            lineTo(size.width * 0.54f, size.height * 0.90f)
            lineTo(size.width * 0.98f, size.height * 0.12f)
        }
        drawPath(back, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(front, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun PendingClockIcon() {
    val color = TextTertiary.copy(alpha = 0.9f)
    Canvas(modifier = Modifier.size(11.dp)) {
        val stroke = 1.2.dp.toPx()
        val r = size.minDimension / 2f - stroke / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = color, radius = r, center = center, style = Stroke(stroke))
        drawLine(
            color = color,
            start = center,
            end = Offset(center.x, center.y - r * 0.65f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = center,
            end = Offset(center.x + r * 0.50f, center.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
