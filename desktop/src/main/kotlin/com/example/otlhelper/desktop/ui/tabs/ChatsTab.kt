package com.example.otlhelper.desktop.ui.tabs

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.data.chat.InboxRepository
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgCardHover
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.CardShape
import com.example.otlhelper.desktop.theme.PresencePaused
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import com.example.otlhelper.desktop.theme.UnreadGreen
import com.example.otlhelper.desktop.ui.components.Pill
import com.example.otlhelper.desktop.ui.components.PillSize
import com.example.otlhelper.desktop.ui.components.ThinDivider
import com.example.otlhelper.desktop.ui.components.UserAvatar

/**
 * §TZ-DESKTOP-0.1.0 — контент вкладки Чаты ВНУТРИ WorkspacePanel.
 * Точная копия AdminInboxItem (app/core/ui/AdminInboxItem.kt):
 *   — Card shape 16dp, bg BgCard, 0.5dp border BorderDivider
 *   — Avatar 44dp, name 15sp SemiBold, last 13sp TextSecondary
 *   — Время справа 11sp, Unread badge 20dp+ UnreadGreen, цифра 12sp Bold lineHeight 14sp
 * Между группами «пользователи» / «клиенты» — inset divider без подписей.
 */
@Composable
fun ChatsPanelContent(
    state: InboxRepository.State,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    onOpenConversation: (login: String, name: String) -> Unit = { _, _ -> },
) {
    if (state.isLoading && state.rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(BgApp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
        }
        return
    }
    if (state.rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(BgApp), contentAlignment = Alignment.Center) {
            Text(
                if (state.lastError.isNotBlank()) "Ошибка: ${state.lastError}" else "Нет обращений",
                color = TextTertiary,
                fontSize = 13.sp,
            )
        }
        return
    }

    // §ТЗ-2.3.38 — users сверху / clients снизу, inset divider между.
    val users = state.rows.filter { it.senderRole.lowercase() != "client" }
    val clients = state.rows.filter { it.senderRole.lowercase() == "client" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp)
            .verticalScroll(scrollState)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        users.forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            InboxRow(
                name = row.senderName,
                avatarUrl = row.senderAvatarUrl,
                lastText = row.lastText,
                createdAt = formatInboxTime(row.createdAt),
                unread = row.unreadCount,
                presence = row.senderPresence,
                onClick = { onOpenConversation(row.senderLogin, row.senderName) },
            )
        }
        if (users.isNotEmpty() && clients.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ThinDivider(inset = 32.dp)
            Spacer(Modifier.height(12.dp))
        }
        clients.forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            InboxRow(
                name = row.senderName,
                avatarUrl = row.senderAvatarUrl,
                lastText = row.lastText,
                createdAt = formatInboxTime(row.createdAt),
                unread = row.unreadCount,
                presence = row.senderPresence,
                onClick = { onOpenConversation(row.senderLogin, row.senderName) },
            )
        }
    }
}

/** Формат времени в списке чатов — h:mm AM/PM для сегодня, dd.MM для старых. */
private fun formatInboxTime(raw: String): String {
    if (raw.isBlank()) return ""
    return com.example.otlhelper.desktop.util.formatDate(raw).takeIf { it.isNotBlank() } ?: raw.take(16)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun InboxRow(
    name: String,
    avatarUrl: String = "",
    lastText: String,
    createdAt: String,
    unread: Int,
    presence: String = "offline",
    onClick: () -> Unit = {},
) {
    // §TZ-DESKTOP-0.1.0 — компактнее чем Android (панель 280dp минимум):
    // avatar 40 вместо 44, name 14sp вместо 15, row padding 14/12 вместо 16,
    // createdAt справа убран (время видно внутри чата), оставлен только unread chip.
    // §TZ-DESKTOP 0.2.2 — hover-эффект: BgCard → BgCardHover, плавно 150ms.
    var hovered by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        targetValue = if (hovered) BgCardHover else BgCard,
        animationSpec = tween(150),
        label = "inboxRowBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(bg)
            .border(0.5.dp, BorderDivider, CardShape)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(name = name, avatarUrl = avatarUrl, presenceStatus = presence, size = 40.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            if (lastText.isNotBlank()) {
                Text(
                    lastText,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                val (label, color) = when (presence) {
                    "online" -> "онлайн" to UnreadGreen
                    "paused" -> "был(а) недавно" to PresencePaused
                    else -> "оффлайн" to TextTertiary
                }
                Text(label, color = color, fontSize = 11.sp)
            }
        }
        if (unread > 0) {
            Spacer(Modifier.width(6.dp))
            // §TZ-DESKTOP 0.2.0 — единый Pill-компонент вместо ручного UnreadChip.
            Pill(
                text = if (unread > 99) "99+" else unread.toString(),
                containerColor = UnreadGreen,
                contentColor = Color.Black,
                size = PillSize.Md,
            )
        }
    }
}
