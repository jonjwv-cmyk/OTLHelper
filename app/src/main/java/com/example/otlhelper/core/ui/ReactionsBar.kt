package com.example.otlhelper.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AccentSubtle
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BgElevated
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.domain.model.Reactions

/**
 * Compact reactions bar with Telegram-style floating picker.
 *
 * - Active reactions shown as chips (count > 0)
 * - "+" trigger opens a floating popup ABOVE the bar with all emoji
 * - Tap emoji → react and dismiss
 */
@Composable
fun ReactionsBar(
    aggregate: Map<String, Int>,
    myReactions: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Show the "+" picker trigger. Set false in chat (long-press replaces it). */
    showAddButton: Boolean = true,
    /** Long-press on a chip — typically opens a voter list (developer only). */
    onChipLongPress: ((String) -> Unit)? = null,
) {
    // §TZ-2.3.23 — unified haptic через FeedbackService (прямой Vibrator),
    // не через LocalHapticFeedback (= view.performHapticFeedback) который
    // на многих OEM silent no-op'ает. См. FeedbackService docstring.
    val feedback = com.example.otlhelper.core.feedback.LocalFeedback.current
    val feedbackView = LocalView.current
    var pickerOpen by remember { mutableStateOf(false) }
    val activeReactions = Reactions.ALLOWED.filter { (aggregate[it] ?: 0) > 0 }

    // Nothing to show at all → skip
    if (activeReactions.isEmpty() && !showAddButton) return

    Row(
        modifier = modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (emoji in activeReactions) {
            val count = aggregate[emoji] ?: 0
            val selected = emoji in myReactions
            ReactionChip(
                emoji = emoji,
                count = count,
                selected = selected,
                onClick = {
                    feedback?.tap(feedbackView)
                    onToggle(emoji)
                },
                onLongClick = onChipLongPress?.let { cb ->
                    {
                        feedback?.tap(feedbackView)
                        cb(emoji)
                    }
                },
            )
        }

        if (!showAddButton) return@Row

        // "+" trigger with floating popup
        Box {
            Icon(
                Icons.Outlined.AddReaction,
                contentDescription = "Реакция",
                tint = TextTertiary,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable {
                        feedback?.tap(feedbackView)
                        pickerOpen = !pickerOpen
                    }
                    .padding(4.dp)
            )

            if (pickerOpen) {
                Popup(
                    alignment = Alignment.TopCenter,
                    offset = IntOffset(0, -120),
                    onDismissRequest = { pickerOpen = false },
                    properties = PopupProperties(focusable = true)
                ) {
                    Row(
                        modifier = Modifier
                            .shadow(12.dp, RoundedCornerShape(24.dp))
                            .background(BgElevated, RoundedCornerShape(24.dp))
                            .border(0.5.dp, BorderDivider, RoundedCornerShape(24.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (emoji in Reactions.ALLOWED) {
                            Text(
                                emoji,
                                fontSize = 26.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        // Confirm — выбор реакции это committed action,
                                        // double-pulse сильнее чем tap в ReactionChip (тот
                                        // открывал/закрывал same-emoji).
                                        feedback?.confirm(feedbackView)
                                        pickerOpen = false
                                        onToggle(emoji)
                                    }
                                    .padding(6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReactionChip(
    emoji: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)
    val borderColor = if (selected) Accent else BorderDivider
    val bg = if (selected) AccentSubtle else BgCard

    Row(
        modifier = Modifier
            .clip(shape)
            .background(bg, shape)
            .border(0.5.dp, borderColor, shape)
            .then(
                if (onLongClick != null)
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else Modifier.clickable(onClick = onClick)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 14.sp)
        if (count > 0) {
            Spacer(Modifier.width(3.dp))
            Text(
                count.toString(),
                color = if (selected) Accent else TextSecondary,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
