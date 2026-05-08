package com.example.otlhelper.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AccentMuted
import com.example.otlhelper.core.theme.BubbleOther
import com.example.otlhelper.core.theme.BubbleOwn
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import org.json.JSONObject
import kotlin.math.max

/**
 * Telegram-style chat bubble.
 *
 * [showSenderName] — sender label (true in user tab with multiple admins).
 * [showAvatar]     — peer avatar beside bubble. False in 1-on-1 (conversation
 *                    header already identifies the peer).
 * [isFirstInGroup] — first consecutive message from this sender → show name.
 * [isLastInGroup]  — last consecutive message → where avatar (if any) renders.
 *
 * Layout fidelity:
 *  - Outer corners 16dp; grouped inner (side touching next same-sender
 *    bubble) collapses to 4dp. No decorative tail.
 *  - Time + read receipt are drawn as inline meta. If the final line of text
 *    has room, they share that line; otherwise meta wraps below.
 *  - Read receipts are Canvas-drawn (single / double check + pending clock).
 *  - Long-press captures the bubble's window bounds so a floating action
 *    popup can anchor to this specific message.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    item: JSONObject,
    myLogin: String,
    showSenderName: Boolean = true,
    showAvatar: Boolean = true,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    onReactionToggle: ((emoji: String, alreadyReacted: Boolean) -> Unit)? = null,
    onReactionLongPress: ((emoji: String) -> Unit)? = null,
    onLongPress: ((bounds: Rect) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val text = item.optString("text", "")
    val senderLogin = item.optString("sender_login", "")
    val senderName = item.optString("sender_name", senderLogin)
    val senderAvatarUrl = com.example.otlhelper.core.security.blobAwareUrl(item, "sender_avatar_url")
    val senderPresence = item.optString("sender_presence_status", "offline")
    val createdAt = item.optString("created_at", "")
    val isPending = item.optBoolean("is_pending", false)
    val status = item.optString("status", "")
    val isOwn = senderLogin == myLogin

    val bubbleBg = if (isOwn) BubbleOwn else BubbleOther

    val bigR = 16.dp
    val innerR = 4.dp
    val bubbleShape = if (isOwn) {
        RoundedCornerShape(
            topStart = bigR,
            topEnd = if (isFirstInGroup) bigR else innerR,
            bottomStart = bigR,
            bottomEnd = if (isLastInGroup) bigR else innerR
        )
    } else {
        RoundedCornerShape(
            topStart = if (isFirstInGroup) bigR else innerR,
            topEnd = bigR,
            bottomStart = if (isLastInGroup) bigR else innerR,
            bottomEnd = bigR
        )
    }

    val avatarSize = 28.dp

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isOwn && showAvatar) {
            if (isLastInGroup) {
                UserAvatar(
                    avatarUrl = senderAvatarUrl,
                    name = senderName,
                    presenceStatus = senderPresence,
                    size = avatarSize
                )
            } else {
                Spacer(Modifier.width(avatarSize))
            }
            Spacer(Modifier.width(6.dp))
        }

        Column(
            horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 290.dp)
        ) {
            if (!isOwn && showSenderName && isFirstInGroup) {
                Text(
                    senderName,
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
                )
            }

            val haptic = LocalHapticFeedback.current
            var bubbleBounds by remember { mutableStateOf(Rect.Zero) }
            Box(
                modifier = Modifier
                    .onGloballyPositioned { coords -> bubbleBounds = coords.boundsInWindow() }
                    .clip(bubbleShape)
                    .background(bubbleBg)
                    .then(
                        if (onLongPress != null) Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPress(bubbleBounds)
                            }
                        ) else Modifier
                    )
            ) {
                Column(modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)) {
                    val replyPreview = item.optJSONObject("reply_preview")
                    if (replyPreview != null) {
                        Row(
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .widthIn(max = 260.dp)
                                .background(Accent.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        ) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .heightIn(min = 28.dp)
                                    .background(Accent)
                            )
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(
                                    replyPreview.optString("sender_name", "").ifBlank { "сообщение" },
                                    color = Accent,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                                val replyText = replyPreview.optString("text", "")
                                if (replyText.isNotBlank()) {
                                    Text(
                                        replyText,
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    val attJson = item.optJSONArray("attachments")?.toString()
                        ?: item.optString("attachments_json", "")
                    val hasAttachments = attJson.isNotBlank() && attJson != "null" && attJson != "[]"
                    if (hasAttachments) {
                        AttachmentsView(attachmentsJson = attJson, modifier = Modifier.widthIn(max = 260.dp))
                        if (text.isNotBlank()) Spacer(Modifier.height(6.dp))
                    }

                    val metaSlot: @Composable () -> Unit = {
                        MessageMeta(
                            createdAt = createdAt,
                            isOwn = isOwn,
                            isPending = isPending,
                            status = status,
                        )
                    }
                    if (text.isNotBlank()) {
                        TextWithInlineMeta(
                            text = text,
                            textStyle = TextStyle(
                                color = TextPrimary,
                                fontSize = 15.sp,
                                lineHeight = 20.sp
                            ),
                            meta = metaSlot,
                        )
                    } else {
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) { metaSlot() }
                    }

                    if (onReactionToggle != null) {
                        val reactionsJson = item.optJSONObject("reactions")
                        val aggregate = buildMap {
                            reactionsJson?.keys()?.forEach { key ->
                                val count = reactionsJson.optInt(key, 0)
                                if (count > 0) put(key, count)
                            }
                        }
                        val myReactionsJson = item.optJSONArray("my_reactions")
                        val myReactions = buildSet {
                            if (myReactionsJson != null) for (i in 0 until myReactionsJson.length()) {
                                val v = myReactionsJson.optString(i, "")
                                if (v.isNotBlank()) add(v)
                            }
                        }
                        if (aggregate.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            ReactionsBar(
                                aggregate = aggregate,
                                myReactions = myReactions,
                                onToggle = { emoji -> onReactionToggle(emoji, emoji in myReactions) },
                                showAddButton = false,
                                onChipLongPress = onReactionLongPress,
                            )
                        }
                    }
                }
            }
        }

        if (isOwn) Spacer(Modifier.width(4.dp))
    }
}

// Measures the text twice: once to find the last line right edge, then again
// to render it. If meta fits on the last line, it overlaps trailing whitespace;
// otherwise wraps beneath. This is the Telegram "time hugs the last word" look.
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
            constraints = Constraints(maxWidth = constraints.maxWidth)
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
    createdAt: String,
    isOwn: Boolean,
    isPending: Boolean,
    status: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatTime(createdAt),
            color = if (isOwn) Accent.copy(alpha = 0.65f) else TextTertiary.copy(alpha = 0.85f),
            fontSize = 11.sp,
            letterSpacing = 0.1.sp
        )
        if (isOwn) {
            Spacer(Modifier.width(4.dp))
            ChatStatusIcon(isPending = isPending, status = status)
        }
    }
}

/** clock (pending) / single check / double check */
@Composable
internal fun ChatStatusIcon(isPending: Boolean, status: String) {
    val read = status.equals("read", ignoreCase = true)
    when {
        isPending -> PendingClockIcon()
        read -> DoubleCheckIcon(color = Accent)
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
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
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
internal fun PendingClockIcon() {
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
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = center,
            end = Offset(center.x + r * 0.50f, center.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}
