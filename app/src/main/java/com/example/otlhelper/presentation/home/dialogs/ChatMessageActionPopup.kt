package com.example.otlhelper.presentation.home.dialogs

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.example.otlhelper.core.theme.AccentSubtle
import com.example.otlhelper.core.theme.BgElevated
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.ui.components.ThinDivider
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.domain.model.Reactions

/**
 * Telegram-style floating action popup, anchored to a specific chat bubble.
 *
 * Layout:
 *  ┌─────────────────────────┐
 *  │  🔥  👍  ❤️  😂  😮  😢  │  ← quick reactions (top row)
 *  ├─────────────────────────┤
 *  │  ↩  Ответить            │  ← action menu
 *  │  📋  Копировать         │
 *  └─────────────────────────┘
 *
 * The popup tries to position itself below the bubble, or above if there's
 * no room. Horizontally it anchors to the bubble side (right for own bubble,
 * left otherwise) and clamps to the viewport with an 8dp margin.
 */
@Composable
fun ChatMessageActionPopup(
    bubbleBounds: Rect,
    myReactions: Set<String>,
    canReply: Boolean = true,
    canCopy: Boolean = true,
    onReact: (emoji: String, alreadyReacted: Boolean) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    // §TZ-2.3.23 — unified haptic через FeedbackService, не LocalHapticFeedback.
    val feedback = com.example.otlhelper.core.feedback.LocalFeedback.current
    val feedbackView = LocalView.current
    val positionProvider = remember(bubbleBounds) { MessagePopupPositionProvider(bubbleBounds) }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        )
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 220.dp, max = 300.dp)
                .shadow(elevation = 12.dp, shape = RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(BgElevated)
                .border(0.5.dp, BorderDivider, RoundedCornerShape(14.dp))
        ) {
            // ── Quick reactions row ──────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (emoji in Reactions.ALLOWED) {
                    val selected = emoji in myReactions
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .then(
                                if (selected) Modifier.background(AccentSubtle) else Modifier
                            )
                            .clickable {
                                // Confirm — set/remove reaction это committed action.
                                feedback?.confirm(feedbackView)
                                onReact(emoji, selected)
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emoji, fontSize = 22.sp)
                    }
                }
            }

            ThinDivider()

            // ── Actions ──────────────────────────────────────────────
            if (canReply) {
                PopupActionRow(Icons.AutoMirrored.Outlined.Reply, "Ответить") {
                    onReply(); onDismiss()
                }
            }
            if (canCopy) {
                PopupActionRow(Icons.Outlined.ContentCopy, "Копировать") {
                    onCopy(); onDismiss()
                }
            }
        }
    }
}

@Composable
private fun PopupActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = TextPrimary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

/**
 * Positions the popup just below the given bubble when possible; flips to
 * above if there's not enough room. Horizontally, prefers the bubble's
 * near edge (right edge for own bubbles, left edge for peer bubbles) and
 * clamps to the viewport with an 8dp margin.
 */
private class MessagePopupPositionProvider(
    private val bubble: Rect,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val margin = 8
        val gap = 6
        val spaceBelow = windowSize.height - bubble.bottom.toInt()
        val spaceAbove = bubble.top.toInt()
        val fitsBelow = spaceBelow >= popupContentSize.height + gap + margin
        val fitsAbove = spaceAbove >= popupContentSize.height + gap + margin

        val y = when {
            fitsBelow -> bubble.bottom.toInt() + gap
            fitsAbove -> bubble.top.toInt() - popupContentSize.height - gap
            else -> (windowSize.height - popupContentSize.height) / 2
        }

        // Horizontal anchor: align the popup's near edge with the bubble's
        // near edge. For bubbles sitting on the right half of the screen we
        // align right edges; otherwise align left edges.
        val bubbleCenterX = (bubble.left + bubble.right) / 2f
        val windowCenterX = windowSize.width / 2f
        val preferRight = bubbleCenterX > windowCenterX

        val rawX = if (preferRight) {
            bubble.right.toInt() - popupContentSize.width
        } else {
            bubble.left.toInt()
        }
        val x = rawX.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))

        return IntOffset(x, y)
    }
}
