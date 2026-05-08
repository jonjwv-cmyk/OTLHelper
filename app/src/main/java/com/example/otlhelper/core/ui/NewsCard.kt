package com.example.otlhelper.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AccentSubtle
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.theme.UnreadGreen
import com.example.otlhelper.domain.model.FeedItemView

/**
 * News card. Role-driven visibility comes from [view] — the card itself is
 * role-agnostic (§4.2 passport v3).
 *
 * `item` — @Immutable-обёртка над raw JSONObject для стабильной Compose-
 * рекомпозиции (подробнее — см. [StableJsonItem]). Если контент тот же,
 * вся карточка пропускает recompose.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NewsCard(
    item: StableJsonItem,
    view: FeedItemView,
    myLogin: String,
    onOverflowClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onReactionToggle: ((emoji: String, alreadyReacted: Boolean) -> Unit)? = null,
    onReactionLongPress: ((emoji: String) -> Unit)? = null,
    // §TZ-2.3.38 — когда карточка рендерится внутри PinnedItemSheet
    // (раскрытое закреплённое), pinned-пилл дублирует самоочевидное и под
    // клавиатурой обрезается. Скрываем его в раскрытом виде.
    hidePinnedBadge: Boolean = false,
    modifier: Modifier = Modifier
) {
    val raw = item.raw
    val text = raw.optString("text", "")
    val senderAvatarUrl = com.example.otlhelper.core.security.blobAwareUrl(raw, "sender_avatar_url")
    val senderPresence = raw.optString("sender_presence_status", "offline")
    val isUnread = raw.optInt("is_read", 1) == 0
    val isPending = raw.optBoolean("is_pending", false)

    val borderColor = when {
        view.isPinned -> Accent
        else -> BorderDivider
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(BgCard, CardShape)
            .border(0.5.dp, borderColor, CardShape)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onLongClick = onLongClick, onClick = {})
                } else Modifier
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                view.headerLabel,
                color = Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            if (view.isEdited) {
                Spacer(Modifier.width(6.dp))
                Text("(изменено)", color = TextTertiary, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(
                avatarUrl = senderAvatarUrl,
                name = view.authorLabel,
                presenceStatus = senderPresence,
                size = 32.dp
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (view.showAuthor) {
                    Text(
                        text = view.authorLabel,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = formatDate(view.createdAt),
                        color = TextTertiary,
                        fontSize = 11.sp
                    )
                }
            }
            if (isPending) {
                // §TZ-2.3.7 — Telegram-style clock. Раньше был ⏳ emoji
                // (нестабильный рендер между устройствами + детский look).
                PendingClockIcon()
                Spacer(Modifier.width(6.dp))
            } else {
                if (isUnread) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(UnreadGreen)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (view.canOpenMenu) {
                    // §TZ-2.3.24 — tap haptic на три точки (overflow → stats).
                    val overflowFeedback = com.example.otlhelper.core.feedback.LocalFeedback.current
                    val overflowView = androidx.compose.ui.platform.LocalView.current
                    IconButton(
                        onClick = {
                            overflowFeedback?.tap(overflowView)
                            onOverflowClick()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.MoreVert, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        // §TZ-2.3.7 — текст выводим ТОЛЬКО если он есть. Раньше пустой Text
        // занимал ~22dp line-height → при медиа-только новости карточка
        // визуально «задиралась» вверх пустой дырой над видео. Теперь если
        // `text.isBlank()` — показываем сразу вложения без пустого Text'а.
        val textValue = text.trim()
        if (textValue.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = textValue,
                color = TextPrimary,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }

        val attJson = raw.optJSONArray("attachments")?.toString() ?: raw.optString("attachments_json", "")
        if (attJson.isNotBlank() && attJson != "null" && attJson != "[]") {
            // Spacer только если уже есть текст сверху — иначе avatar+header
            // уже отделены собственным Spacer(10).
            if (textValue.isNotEmpty()) Spacer(Modifier.height(10.dp))
            else Spacer(Modifier.height(2.dp))
            AttachmentsView(attachmentsJson = attJson)
        }
        if (view.isPinned && !hidePinnedBadge) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .background(AccentSubtle, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Закреплено", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (onReactionToggle != null) {
            ReactionsBar(
                aggregate = view.reactionsAggregate,
                myReactions = view.myReactions,
                onToggle = { emoji -> onReactionToggle(emoji, emoji in view.myReactions) },
                onChipLongPress = onReactionLongPress,
            )
        }
    }
}
