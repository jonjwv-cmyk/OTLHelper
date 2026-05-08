package com.example.otlhelper.presentation.home.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.theme.avatarColor
import com.example.otlhelper.core.ui.AdminInboxItem
import com.example.otlhelper.core.ui.AdminInboxRow
import com.example.otlhelper.core.ui.ChatBubble
import com.example.otlhelper.core.ui.toAdminInboxRow
import com.example.otlhelper.core.ui.animations.AppMotion
import com.example.otlhelper.core.ui.components.ChatListSkeleton
import com.example.otlhelper.core.ui.components.ContactListSkeleton
import com.example.otlhelper.core.ui.components.ThinDivider
import coil3.request.crossfade
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

// ── User chat (role=user: single chat with admins) ────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserChatTab(
    items: List<JSONObject>,
    isLoading: Boolean,
    myLogin: String,
    typingFromLogins: Set<String> = emptySet(),
    listState: LazyListState = rememberLazyListState(),
    onMarkRead: (Long) -> Unit = {},
    onReactionToggle: ((JSONObject, String, Boolean) -> Unit)? = null,
    onMessageLongPress: ((JSONObject, Rect) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val typingLogin = typingFromLogins.firstOrNull()

    Column(modifier = modifier.fillMaxSize()) {
        // ── Header with typing indicator ─────────────────────────────
        if (typingLogin != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgCard)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$typingLogin печатает…",
                    color = Accent,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            ThinDivider()
        }

        // ── Content ──────────────────────────────────────────────────
        // Data-first rule: if we have messages, always render the list.
        // Skeleton only on a true cold start (no cache, never loaded).
        // Empty welcome only after a settled, confirmed-empty fetch.
        // hasLoadedOnce is rememberSaveable so a tab-switch can't wipe it
        // and flash a skeleton over the cache on return.
        var hasLoadedOnce by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(isLoading, items) {
            if (!isLoading) hasLoadedOnce = true
        }
        Box(modifier = Modifier.weight(1f)) {
            when {
                items.isNotEmpty() ->
                    // User sees multiple admins → avatar tells them which admin replied.
                    ChatMessageList(
                        items = items,
                        myLogin = myLogin,
                        showSenderNames = true,
                        showAvatars = true,
                        listState = listState,
                        onMarkRead = onMarkRead,
                        onReactionToggle = onReactionToggle,
                        onMessageLongPress = onMessageLongPress
                    )
                isLoading && !hasLoadedOnce ->
                    ChatListSkeleton(modifier = Modifier.fillMaxSize())
                hasLoadedOnce ->
                    ChatEmptyWelcome(
                        title = "Чат с поддержкой",
                        subtitle = "Напишите сообщение — специалисты ответят вам здесь"
                    )
            }
        }
    }
}

// ── Admin inbox list ───────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AdminInboxTab(
    items: List<JSONObject>,
    isLoading: Boolean,
    myLogin: String,
    fromCache: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    onContactClick: (login: String, name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    // §TZ-2.3.5 final — Telegram-алгоритм. Сортировка по `lastMessageId DESC`.
    // Детерминированная (id не меняются пока нет нового сообщения), стабильная
    // между сессиями (cold-start → тот же порядок что был при закрытии),
    // не нуждается в rankState / hasLoadedOnce / fromCache-магии. Порядок
    // меняется только когда РЕАЛЬНО приходит новое сообщение: у контакта
    // lastMessageId становится больше → sortByDescending автоматически поднимает
    // его наверх, placement-spring в `animateItem` плавно анимирует движение,
    // остальные остаются на местах. Точно как в Telegram.
    //
    // Dedupe по senderLogin — сервер иногда возвращает несколько ряд'ов от
    // одного пользователя (артефакт JOIN на историю). Keep row с большим
    // lastMessageId — свежее сообщение.
    // §TZ-2.3.19 — скрываем самого себя из списка чатов (сервер иногда
    // отдаёт записи с sender_login = авторизованный админ, если у старых
    // записей sender_role сохранилось как 'user' до миграции роли).
    // Плюс отбрасываем пустой senderLogin (защита от синтетических строк
    // без login'а).
    val displayRows: List<AdminInboxRow> = remember(items, myLogin) {
        items.map { it.toAdminInboxRow() }
            .filter { it.senderLogin.isNotBlank() && it.senderLogin != myLogin }
            .groupBy { it.senderLogin }
            .mapNotNull { (_, group) -> group.maxByOrNull { it.lastMessageId } }
            .sortedByDescending { it.lastMessageId }
    }
    // §TZ-2.3.38 — визуальное разделение на users/clients. Для admin/dev:
    // users сверху → тонкая линия → clients снизу. Если клиентов нет — без линии.
    val (regularRows, clientRows) = remember(displayRows) {
        displayRows.partition { it.senderRole.lowercase() != "client" }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            // Skeleton только когда реально нечего показать ВООБЩЕ (никогда
            // не запускалось, кеш пуст, сеть ещё не ответила). При повторных
            // входах в app кеш предоставляет список сразу — без skeleton.
            displayRows.isEmpty() && isLoading ->
                ContactListSkeleton(modifier = Modifier.fillMaxSize())

            displayRows.isNotEmpty() -> {
                // §TZ-2.3.7 — лёгкий tick при прокрутке через каждый item —
                // тактильная связь с листом в SF-стиле. НЕ спамит: тикает
                // только при реальном изменении firstVisibleItem.
                val inboxFeedback = com.example.otlhelper.core.feedback.LocalFeedback.current
                val inboxHost = androidx.compose.ui.platform.LocalView.current
                androidx.compose.runtime.LaunchedEffect(listState) {
                    androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
                        .collect { _ ->
                            if (listState.isScrollInProgress) inboxFeedback?.tick(inboxHost)
                        }
                }
                LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // §TZ-2.3.38 — сначала обычные пользователи.
                itemsIndexed(
                    regularRows,
                    key = { index, row ->
                        if (row.senderLogin.isNotBlank()) row.senderLogin else "row#$index"
                    },
                ) { _, row ->
                    AdminInboxItem(
                        row = row,
                        onClick = { onContactClick(row.senderLogin, row.senderName) },
                        modifier = Modifier
                            .fillMaxWidth()
                            // §TZ-2.3.5++ — animateItem БЕЗ fadeIn/fadeOut.
                            // Раньше default fadeIn срабатывал при переходе
                            // `skeleton → LazyColumn` (все items считаются
                            // «новыми» при первой композиции) → визуально
                            // «контакты прогружаются по одному» даже когда
                            // данные были одни и те же. Оставляем только
                            // placement-spring — чтобы bump контакта наверх
                            // при новом сообщении был плавным, но появление
                            // списка из skeleton — мгновенное.
                            .animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = null,
                                placementSpec = AppMotion.SpringStandardOffset,
                            ),
                    )
                }
                // §TZ-2.3.38 — тонкая линия-разделитель между users и clients.
                // Показываем только если есть и те, и другие.
                if (regularRows.isNotEmpty() && clientRows.isNotEmpty()) {
                    item(key = "__client_divider__") {
                        ThinDivider(modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
                // Ниже линии — клиенты.
                itemsIndexed(
                    clientRows,
                    key = { index, row ->
                        if (row.senderLogin.isNotBlank()) "client_${row.senderLogin}" else "client_row#$index"
                    },
                ) { _, row ->
                    AdminInboxItem(
                        row = row,
                        onClick = { onContactClick(row.senderLogin, row.senderName) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = null,
                                placementSpec = AppMotion.SpringStandardOffset,
                            ),
                    )
                }
            }
            }

            // Network completed, nothing to show.
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.ChatBubble,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Нет обращений",
                        color = TextSecondary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}


// ── Admin conversation ─────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AdminConversationTab(
    items: List<JSONObject>,
    isLoading: Boolean,
    myLogin: String,
    userName: String,
    peerLogin: String = "",
    peerAvatarUrl: String = "",
    typingFromLogins: Set<String> = emptySet(),
    listState: LazyListState = rememberLazyListState(),
    onMarkRead: (Long) -> Unit = {},
    onBack: () -> Unit = {},
    onReactionToggle: ((JSONObject, String, Boolean) -> Unit)? = null,
    onMessageLongPress: ((JSONObject, Rect) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Prefer the explicit URL from state; fall back to scanning items — handles
    // the race where the avatar is in feed but state hasn't been backfilled yet.
    val resolvedAvatarUrl = peerAvatarUrl.ifBlank {
        items.firstOrNull {
            it.optString("sender_login", "") == peerLogin &&
                com.example.otlhelper.core.security.blobAwareUrl(it, "sender_avatar_url").isNotBlank()
        }?.let { com.example.otlhelper.core.security.blobAwareUrl(it, "sender_avatar_url") } ?: ""
    }
    val isPeerTyping = peerLogin.isNotBlank() && peerLogin in typingFromLogins

    // Текущий presence peer'а — берём из последнего сообщения этого пользователя.
    // items освежаются каждые 5с (autoRefreshJob) и при возврате на вкладку,
    // так что точка в шапке меняет цвет почти мгновенно.
    val peerPresence = items.firstOrNull {
        it.optString("sender_login", "") == peerLogin
    }?.optString("sender_presence_status", "offline") ?: "offline"

    Column(modifier = modifier.fillMaxSize()) {
        // ── Conversation header ──────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgCard)
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            // Аватар с presence-точкой — единый компонент UserAvatar умеет
            // рисовать пульсирующий зелёный/жёлтый/серый индикатор; не плодим
            // отдельный Box без presence в шапке.
            com.example.otlhelper.core.ui.UserAvatar(
                avatarUrl = resolvedAvatarUrl,
                name = userName,
                presenceStatus = peerPresence,
                size = 36.dp
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    userName.ifBlank { "Переписка" },
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                // Subtitle: typing → "печатает…"; иначе — словесный статус.
                val subtitleText = when {
                    isPeerTyping -> "печатает…"
                    peerPresence == "online" -> "онлайн"
                    peerPresence == "paused" -> "был(а) недавно"
                    else -> ""
                }
                val subtitleColor = when {
                    isPeerTyping -> Accent
                    peerPresence == "online" -> com.example.otlhelper.core.theme.UnreadGreen
                    peerPresence == "paused" -> com.example.otlhelper.core.theme.PresencePaused
                    else -> TextTertiary
                }
                if (subtitleText.isNotBlank()) {
                    Text(subtitleText, color = subtitleColor, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        ThinDivider()

        // ── Messages ─────────────────────────────────────────────────
        // Same data-first rule as AdminInboxTab / UserChatTab. Data wins;
        // skeleton reserved for true cold start; welcome only on confirmed
        // empty. rememberSaveable survives tab-switch so coming back to an
        // open conversation doesn't flash a skeleton over the cached list.
        var hasLoadedOnce by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(isLoading, items) {
            if (!isLoading) hasLoadedOnce = true
        }
        Box(modifier = Modifier.weight(1f)) {
            when {
                items.isNotEmpty() ->
                    // 1-on-1 conversation — header already identifies the peer,
                    // avatars next to each incoming bubble are redundant noise.
                    ChatMessageList(
                        items = items,
                        myLogin = myLogin,
                        showSenderNames = false,
                        showAvatars = false,
                        listState = listState,
                        onMarkRead = onMarkRead,
                        onReactionToggle = onReactionToggle,
                        onMessageLongPress = onMessageLongPress
                    )
                isLoading && !hasLoadedOnce ->
                    ChatListSkeleton(modifier = Modifier.fillMaxSize())
                hasLoadedOnce ->
                    ChatEmptyWelcome(
                        title = userName.ifBlank { "Переписка" },
                        subtitle = "Начните общение — напишите первое сообщение"
                    )
            }
        }
    }
}

/**
 * Иммутабельный snapshot состояния ранжирования для AdminInbox. Один `remember`
 * хранит всё вместе, чтобы транзакция seed+update была атомарной — никакой
 * промежуточный кадр с половиной заполненных ranks не может попасть на экран.
 */
@Immutable
private data class RankState(
    val rankByLogin: Map<String, Long>,
    val lastMessageIdByLogin: Map<String, Long>,
    val nextTopRank: Long,
)

// ── Shared chat message list with grouping + sticky date headers ─────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessageList(
    items: List<JSONObject>,
    myLogin: String,
    showSenderNames: Boolean,
    showAvatars: Boolean,
    listState: LazyListState,
    onMarkRead: (Long) -> Unit,
    onReactionToggle: ((JSONObject, String, Boolean) -> Unit)? = null,
    onMessageLongPress: ((JSONObject, Rect) -> Unit)? = null
) {
    // §TZ-2.3.9 — держим последнее сообщение над клавиатурой. Когда IME
    // открывается (юзер жмёт на input-bar), Column родителя уменьшается на
    // высоту клавиатуры — last message уезжает за неё. Авто-скроллим к
    // концу списка, если до этого были в последних двух item'ах (юзер был
    // на «дне» диалога). Если прокрутили вверх читать историю — не трогаем.
    val imeBottom = WindowInsets.ime
        .getBottom(androidx.compose.ui.platform.LocalDensity.current)
    val imeVisible = imeBottom > 0
    androidx.compose.runtime.LaunchedEffect(imeVisible, imeBottom) {
        if (!imeVisible || items.isEmpty()) return@LaunchedEffect
        val lastIdx = items.size - 1
        val currentLastVisible = listState.layoutInfo.visibleItemsInfo
            .lastOrNull()?.index ?: -1
        if (currentLastVisible >= lastIdx - 2) {
            // animate чтобы не было рывка
            listState.animateScrollToItem(lastIdx)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (i in items.indices) {
            val item = items[i]
            val sender = item.optString("sender_login", "")
            val prevSender = if (i > 0) items[i - 1].optString("sender_login", "") else ""
            val nextSender = if (i < items.size - 1) items[i + 1].optString("sender_login", "") else ""
            val isFirstInGroup = sender != prevSender
            val isLastInGroup = sender != nextSender

            // Детерминистичный ключ — вычисляем РАНЬШЕ headers/spacer, чтобы
            // их ключи тоже могли опираться на него. Раньше header/spacer
            // использовали index `$i`, что ломало стабильность: при вставке
            // нового сообщения вверх списка все spacer_$i сдвигались → Compose
            // думал что старые spacer'ы удалены + новые добавлены → срабатывал
            // animateItem() на всей цепочке сообщений.
            val itemKey = com.example.otlhelper.core.ui.stableFeedKey(item)

            // Sticky date header — stays visible while scrolling through that day
            val thisDate = extractDateLabel(item)
            val prevDate = if (i > 0) extractDateLabel(items[i - 1]) else null
            if (thisDate != null && thisDate != prevDate) {
                stickyHeader(key = "date_${thisDate}_$itemKey") {
                    DateSeparator(dateText = thisDate)
                }
            }

            // Extra spacing between different-sender groups (Telegram-style breathing room).
            if (isFirstInGroup && i > 0 && prevDate == thisDate) {
                item(key = "spacer_before_$itemKey") { Spacer(Modifier.height(8.dp)) }
            }

            // Message item
            item(key = itemKey) {
                // Mark as read
                val itemId = item.optLong("id", 0L)
                val isUnread = item.optInt("is_read", if (sender == myLogin) 1 else 0) == 0
                if (isUnread && itemId > 0L && sender != myLogin) {
                    LaunchedEffect(itemId) { onMarkRead(itemId) }
                }

                ChatBubble(
                    item = item,
                    myLogin = myLogin,
                    showSenderName = showSenderNames,
                    showAvatar = showAvatars,
                    isFirstInGroup = isFirstInGroup,
                    isLastInGroup = isLastInGroup,
                    onReactionToggle = onReactionToggle?.let { cb ->
                        { emoji, already -> cb(item, emoji, already) }
                    },
                    onLongPress = onMessageLongPress?.let { cb -> { bounds -> cb(item, bounds) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ── Shared composables ───────────────────────────────────────────────────────

@Composable
private fun DateSeparator(dateText: String) {
    // Telegram-style: centered pill with semi-transparent dark bg, lighter text
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = dateText,
            color = TextPrimary.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            letterSpacing = 0.3.sp,
            modifier = Modifier
                .background(
                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f),
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ChatEmptyWelcome(title: String, subtitle: String) {
    // §TZ-2.3.6 — единый язык empty-states: Lottie otter_sad везде где пусто.
    // Раньше тут был круг с инициалами — визуально расходилось с NewsTab/
    // SearchTab, где уже otter_sad. Теперь везде консистентно.
    val composition by com.airbnb.lottie.compose.rememberLottieComposition(
        com.airbnb.lottie.compose.LottieCompositionSpec.Asset("otter_sad.json")
    )
    val progress by com.airbnb.lottie.compose.animateLottieCompositionAsState(
        composition,
        iterations = com.airbnb.lottie.compose.LottieConstants.IterateForever,
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            com.airbnb.lottie.compose.LottieAnimation(
                composition = composition,
                progress = { progress },
                modifier = Modifier.size(140.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                color = TextTertiary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun extractDateLabel(item: JSONObject): String? {
    val raw = item.optString("created_at", "")
    if (raw.isBlank()) return null
    return try {
        // Accept both ISO ("2026-04-15T14:32:00Z") and SQLite ("2026-04-15 14:32:00", UTC-assumed).
        val normalized = if (raw.contains('T')) {
            if (raw.endsWith("Z") || raw.contains('+') || raw.lastIndexOf('-') > 10) raw
            else "${raw}Z"
        } else {
            "${raw.replace(' ', 'T')}Z"
        }
        val zdt = ZonedDateTime.parse(normalized)
        val yek = zdt.withZoneSameInstant(TimeZone.getTimeZone("Asia/Yekaterinburg").toZoneId())
        val today = java.time.LocalDate.now(yek.zone)
        val msgDate = yek.toLocalDate()
        when {
            msgDate == today -> "Сегодня"
            msgDate == today.minusDays(1) -> "Вчера"
            else -> yek.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")))
        }
    } catch (_: Exception) { null }
}
