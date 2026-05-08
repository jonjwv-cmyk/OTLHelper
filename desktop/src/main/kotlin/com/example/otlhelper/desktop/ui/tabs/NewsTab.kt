package com.example.otlhelper.desktop.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.data.feed.NewsRepository
import com.example.otlhelper.desktop.model.Role
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgCardHover
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.CardShape
import com.example.otlhelper.desktop.theme.PollOptionNormal
import com.example.otlhelper.desktop.theme.PollOptionSelected
import com.example.otlhelper.desktop.theme.Space
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import com.example.otlhelper.desktop.theme.UnreadGreen
import com.example.otlhelper.desktop.ui.components.AttachmentsView
import com.example.otlhelper.desktop.ui.components.Pill
import com.example.otlhelper.desktop.ui.components.PillSize
import com.example.otlhelper.desktop.ui.components.ThinDivider
import com.example.otlhelper.desktop.ui.components.Tooltip
import com.example.otlhelper.desktop.ui.components.UserAvatar
import kotlinx.coroutines.launch

/** §TZ-DESKTOP — лимит закрепов: 3 любых (news + poll), как и в Android. */
private const val MAX_PINNED = 3

/**
 * §TZ-DESKTOP-0.1.0 этап 5 — Новости с реальными данными из [NewsRepository].
 *
 * UI: pinned pill сверху, карточки ниже. Реакции кликабельны (toggle). Three-dot →
 * статистика (для kind=news → NewsReadersDialog через onOpenReaders;
 * kind=poll → PollStatsDialog через onOpenPollStats). Pin/unpin — через callback.
 *
 * Edit/Delete UI НЕ показываем по политике (ни на Android, ни здесь).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun NewsPanelContent(
    state: NewsRepository.State,
    role: Role,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    onToggleReaction: (messageId: Long, emoji: String, alreadyReacted: Boolean) -> Unit = { _, _, _ -> },
    onReactionLongPress: ((messageId: Long, emoji: String) -> Unit)? = null,
    onVote: (pollId: Long, optionId: Long) -> Unit = { _, _ -> },
    onTogglePin: (messageId: Long, pin: Boolean) -> Unit = { _, _ -> },
    onOpenReaders: (messageId: Long) -> Unit = {},
    onOpenPollStats: (pollId: Long, messageId: Long) -> Unit = { _, _ -> },
    onEditNews: (messageId: Long, newText: String) -> Unit = { _, _ -> },
    onDeleteNews: (messageId: Long) -> Unit = {},
    onMarkRead: (messageId: Long) -> Unit = {},
    // §TZ-DESKTOP 0.4.x — search-result navigation для news.
    scrollToNewsId: Long? = null,
    onScrollTargetConsumed: () -> Unit = {},
) {
    // §TZ-DESKTOP-0.1.0 этап 5 — EditDeleteSheet + EditNewsDialog для своих новостей.
    var editDeleteTarget by remember { mutableStateOf<NewsRepository.Item?>(null) }
    var editDialogTarget by remember { mutableStateOf<NewsRepository.Item?>(null) }
    val scope = rememberCoroutineScope()
    val showScrollDown by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) return@derivedStateOf false
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible < total - 2
        }
    }

    // §TZ-DESKTOP-0.1.0 этап 5 — при первом рендере ленты скроллим в низ
    // (самые новые). Повторные рендеры того же количества item'ов не трогаем.
    var lastRenderedSize by remember { mutableStateOf(0) }
    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty() && lastRenderedSize == 0) {
            kotlinx.coroutines.delay(50)
            // Если есть pending search target — не прыгаем вниз, ниже
            // эффект проскроллит точно к нему.
            if (scrollToNewsId == null) {
                listState.scrollToItem(state.items.lastIndex)
            }
        }
        lastRenderedSize = state.items.size
    }

    // §TZ-DESKTOP 0.4.x — search-result navigation: scroll к конкретной
    // новости + amber highlight pulse 2.5s.
    var highlightedNewsId by remember { mutableStateOf<Long?>(null) }
    var lastScrolledTargetId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(scrollToNewsId, state.items.size) {
        val target = scrollToNewsId
        if (target == null || target == lastScrolledTargetId) return@LaunchedEffect
        val index = state.items.indexOfFirst { it.id == target }
        if (index < 0) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        runCatching { listState.animateScrollToItem(index) }
        highlightedNewsId = target
        lastScrolledTargetId = target
        kotlinx.coroutines.delay(2500)
        highlightedNewsId = null
        onScrollTargetConsumed()
    }

    Box(modifier = Modifier.fillMaxSize().background(BgApp)) {
        if (state.isLoading && state.items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
            }
            return@Box
        }
        if (state.items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.lastError.isNotBlank()) "Ошибка: ${state.lastError}" else "Нет новостей",
                    color = TextTertiary,
                    fontSize = 13.sp,
                )
            }
            return@Box
        }

        // Column: pinned pills НЕ скроллятся (находятся ВНЕ LazyColumn как на Android),
        // лента карточек скроллится внутри LazyColumn. Показываем ВСЕ закреплённые
        // (и новости, и опросы) стопкой сверху — каждая раскрывается inline через
        // chevron и внутри три-точки ведут в PollStats (для опроса) или в
        // NewsReaders (для новости), как в Android PinnedPills.
        Column(modifier = Modifier.fillMaxSize()) {
            // §TZ-DESKTOP — максимум 3 закрепа в сумме (news + poll). Если
            // сервер почему-то вернул больше — отображаем только первые 3.
            val visiblePinned = state.pinnedItems.take(MAX_PINNED)
            if (visiblePinned.isNotEmpty()) {
                PinnedPills(
                    items = visiblePinned,
                    role = role,
                    onOpenStats = { item ->
                        if (item.kind == "poll" && item.poll != null) {
                            onOpenPollStats(item.poll.id, item.id)
                        } else {
                            onOpenReaders(item.id)
                        }
                    },
                    onReactionToggle = { itemId, emoji, already ->
                        onToggleReaction(itemId, emoji, already)
                    },
                    onReactionLongPress = onReactionLongPress,
                    onVote = { pollId, optId -> onVote(pollId, optId) },
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 10.dp,
                    bottom = 20.dp,
                ),
            ) {
                items(state.items, key = { it.id }) { item ->
                    val isHighlighted = item.id == highlightedNewsId
                    val highlightBg by androidx.compose.animation.animateColorAsState(
                        targetValue = if (isHighlighted) {
                            Accent.copy(alpha = 0.18f)
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
                        },
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 600),
                        label = "news_highlight",
                    )
                    Box(modifier = Modifier.fillMaxWidth().background(highlightBg)) {
                        CardPadded {
                            NewsCard(
                                item = item,
                                role = role,
                                onOverflowClick = {
                                    if (item.kind == "poll" && item.poll != null) {
                                        onOpenPollStats(item.poll.id, item.id)
                                    } else {
                                        onOpenReaders(item.id)
                                    }
                                },
                                onLongPress = {
                                    val canManage = item.isOwn || role.isAdmin
                                    if (canManage) editDeleteTarget = item
                                },
                                onReactionToggle = { emoji, already -> onToggleReaction(item.id, emoji, already) },
                                onReactionLongPress = onReactionLongPress?.let { cb -> { emoji -> cb(item.id, emoji) } },
                                onVote = { optId -> item.poll?.let { onVote(it.id, optId) } },
                                onTogglePin = { onTogglePin(item.id, !item.isPinned) },
                                onMarkRead = { onMarkRead(item.id) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        // §TZ-DESKTOP 0.3.0 — явный tween(220) для плавного fade+scale. Без
        // явного animationSpec использовался дефолтный short-spring (~75ms)
        // который выглядел «бум» — как мгновенное появление/исчезновение.
        androidx.compose.animation.AnimatedVisibility(
            visible = showScrollDown,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.scaleIn(
                    animationSpec = androidx.compose.animation.core.tween(220),
                    initialScale = 0.8f,
                ),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.scaleOut(
                    animationSpec = androidx.compose.animation.core.tween(220),
                    targetScale = 0.8f,
                ),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    scope.launch {
                        if (state.items.isNotEmpty()) {
                            listState.animateScrollToItem(state.items.lastIndex)
                        }
                    }
                },
                containerColor = BgCard,
                contentColor = TextSecondary,
                shape = CircleShape,
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Вниз")
            }
        }
    }

    // ── §TZ-DESKTOP-0.1.0 этап 5 — EditDeleteSheet (Edit/Delete/Pin) + EditNewsDialog ──
    editDeleteTarget?.let { target ->
        // Для poll редактирование не поддерживается (структура options в edit_message
        // не передаётся). Оставляем только Delete + Pin.
        val canEdit = target.isOwn && target.kind != "poll"
        val canDelete = target.isOwn || role.isAdmin
        val canPin = role.isAdmin
        com.example.otlhelper.desktop.ui.dialogs.EditDeleteSheet(
            canEdit = canEdit,
            canDelete = canDelete,
            canPin = canPin,
            onDismiss = { editDeleteTarget = null },
            onEdit = {
                editDialogTarget = target
                editDeleteTarget = null
            },
            onDelete = {
                onDeleteNews(target.id)
                editDeleteTarget = null
            },
            onPin = {
                onTogglePin(target.id, !target.isPinned)
                editDeleteTarget = null
            },
        )
    }

    editDialogTarget?.let { target ->
        com.example.otlhelper.desktop.ui.dialogs.EditNewsDialog(
            initialText = target.text,
            onCancel = { editDialogTarget = null },
            onConfirm = { newText ->
                if (newText != target.text) onEditNews(target.id, newText)
                editDialogTarget = null
            },
        )
    }
}

@Composable
private fun CardPadded(content: @Composable () -> Unit) {
    Box(modifier = Modifier.padding(horizontal = 10.dp)) { content() }
}

// ── PINNED PILLS ──

/**
 * §TZ-DESKTOP 0.3.0 — итоговая версия закреплённых плашек:
 *
 *  • Collapsed: компактный ряд — [📌] [Новость/Опрос (важно)] [˅]. Только
 *    label + chevron, без three-dot и без превью. Плашка = 1 строка ~30dp.
 *
 *  • Expanded: раскрывается в полную [NewsCard] (дубликат карточки из ленты)
 *    со своим three-dot (работающим — открывает readers/poll-stats), с
 *    реакциями и голосованием для poll. Clicks на chevron / ряд заголовка
 *    — toggle.
 *
 * Логика:
 *  — collapsed ≠ огромная плашка (фидбэк: "кажутся огромными")
 *  — expanded = "дубликат внутри пина и три-точки рабочие" (прямой фидбэк)
 *  — hidePinnedBadge=true в NewsCard внутри пилы, чтобы не показывать второй
 *    раз "Закреплено" badge — сама пила уже закреплена.
 */
@Composable
private fun PinnedPills(
    items: List<NewsRepository.Item>,
    role: Role,
    onOpenStats: (NewsRepository.Item) -> Unit,
    onReactionToggle: (messageId: Long, emoji: String, already: Boolean) -> Unit,
    onReactionLongPress: ((messageId: Long, emoji: String) -> Unit)?,
    onVote: (pollId: Long, optionId: Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgCard)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        items.forEach { item ->
            PinnedTagRow(
                item = item,
                role = role,
                onOpenStats = { onOpenStats(item) },
                onReactionToggle = { emoji, already -> onReactionToggle(item.id, emoji, already) },
                onReactionLongPress = onReactionLongPress?.let { cb -> { emoji -> cb(item.id, emoji) } },
                onVote = { optId -> item.poll?.let { onVote(it.id, optId) } },
            )
        }
    }
    ThinDivider()
}

@Composable
private fun PinnedTagRow(
    item: NewsRepository.Item,
    role: Role,
    onOpenStats: () -> Unit,
    onReactionToggle: (emoji: String, already: Boolean) -> Unit,
    onReactionLongPress: ((emoji: String) -> Unit)?,
    onVote: (optionId: Long) -> Unit,
) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val label = if (item.kind == "poll") "Опрос (важно)" else "Новость (важно)"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AccentSubtle, shape)
            .border(0.5.dp, Accent.copy(alpha = 0.28f), shape),
    ) {
        // §TZ-DESKTOP 0.3.1 — убрали pin-icon слева по фидбэку (значка в пилле
        // не должно быть). Остались только текст "Новость (важно)" / "Опрос
        // (важно)" и chevron справа. Кликабельность — по всей шапке.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = Space.md, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Свернуть" else "Раскрыть",
                tint = Accent,
                modifier = Modifier.padding(end = Space.xs).size(20.dp),
            )
        }
        // Expanded: полная NewsCard-копия. Three-dot ВНУТРИ NewsCard работает
        // (onOverflowClick = onOpenStats). hidePinnedBadge убирает второй
        // "Закреплено" chip (у нас уже pinned-frame снаружи).
        AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.sm)
                        .height(0.5.dp)
                        .background(Accent.copy(alpha = 0.18f)),
                )
                Spacer(Modifier.height(6.dp))
                NewsCard(
                    item = item,
                    role = role,
                    hidePinnedBadge = true,
                    onOverflowClick = onOpenStats,
                    onReactionToggle = onReactionToggle,
                    onReactionLongPress = onReactionLongPress,
                    onVote = onVote,
                    onTogglePin = {},
                    onMarkRead = {},
                )
            }
        }
    }
}

// ── NEWS CARD ──

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun NewsCard(
    item: NewsRepository.Item,
    role: Role,
    hidePinnedBadge: Boolean = false,
    onOverflowClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onReactionToggle: (emoji: String, already: Boolean) -> Unit,
    onReactionLongPress: ((emoji: String) -> Unit)? = null,
    onVote: (optionId: Long) -> Unit,
    onTogglePin: () -> Unit,
    onMarkRead: () -> Unit,
) {
    val borderColor = if (item.isPinned) Accent else BorderDivider
    val borderWidth = if (item.isPinned) 1.dp else 0.5.dp
    val isPoll = item.kind == "poll" && item.poll != null

    // §TZ-DESKTOP 0.2.0 — отметка «прочитано» на первом показе unread item
    // (оптимистично). Убрали UPPERCASE header-plaque "НОВОСТЬ"/"ОПРОС" —
    // тип контента теперь читается по inline Pill в шапке (для poll) и по
    // border-цвету (для pinned). Экономит ~22dp вертикали на карточку.
    LaunchedEffect(item.id, item.isRead) {
        if (!item.isRead && !item.isOwn) onMarkRead()
    }

    // §TZ-DESKTOP 0.2.2 — hover-эффект (Linear/Superhuman-style): при наведении
    // карточка подсвечивается на 1 шаг светлее (BgCard → BgCardHover).
    // Плавная 150ms анимация. Без этого desktop ощущается "мёртвым".
    var hovered by remember { mutableStateOf(false) }
    val bg by androidx.compose.animation.animateColorAsState(
        targetValue = if (hovered) BgCardHover else BgCard,
        animationSpec = androidx.compose.animation.core.tween(150),
        label = "newsCardBg",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(bg, CardShape)
            .border(borderWidth, borderColor, CardShape)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            // §TZ-DESKTOP-0.1.0 этап 5 — ТОЛЬКО right-click на desktop (не combinedClickable,
            // тот забирал short-click у дочерних IconButton/PollOptionRow/реакций).
            .onPointerEvent(PointerEventType.Press) { e ->
                if (e.buttons.isSecondaryPressed) onLongPress()
            }
            .padding(Space.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UserAvatar(
                name = item.senderName,
                avatarUrl = item.senderAvatarUrl,
                presenceStatus = item.senderPresence,
                size = 28.dp,
            )
            Spacer(Modifier.width(Space.sm))
            Column(modifier = Modifier.weight(1f)) {
                // §TZ-DESKTOP 0.3.1 — перенесли "Опрос" pill на вторую строку
                // (рядом с временем). Имя теперь занимает ВСЮ ширину Column —
                // полностью видно типичное "Имя Фамилия" без ellipsis на 280dp
                // панели. На второй строке: [Опрос] · 24.04 9:14 PM.
                // Pill.Sm (10sp) визуально не давит на time.
                //
                // §TZ-DESKTOP 0.3.2 — maxLines=1→2 для имён: если имя длиннее
                // чем умещается в одну строку (редкий случай — длинные
                // двойные фамилии), переносится аккуратно на 2 строки
                // вместо грубого ellipsis. Обычные имена в формате
                // "Имя Отчество" остаются в 1 строку.
                Text(
                    item.senderName,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isPoll) {
                        Pill(
                            text = "Опрос",
                            containerColor = AccentSubtle,
                            contentColor = Accent,
                            size = PillSize.Sm,
                        )
                        Spacer(Modifier.width(Space.xs))
                    }
                    Text(
                        item.createdAtLabel,
                        color = TextTertiary,
                        fontSize = 11.sp,
                    )
                }
            }
            if (!item.isRead && !item.isOwn) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(UnreadGreen))
                Spacer(Modifier.width(Space.sm))
            }
            Tooltip(text = "Статистика") {
                IconButton(onClick = onOverflowClick, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Статистика",
                        tint = TextTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Тело: для news — text; для poll — title (жирный) + description.
        val poll = item.poll
        if (isPoll && poll != null) {
            Spacer(Modifier.height(Space.sm))
            Text(poll.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
            if (poll.description.isNotBlank() && poll.description != poll.title) {
                Spacer(Modifier.height(Space.xs))
                Text(poll.description, color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(Space.md))
            poll.options.forEachIndexed { idx, opt ->
                PollOptionRow(
                    option = opt,
                    selected = poll.myVoteOptionId == opt.id,
                    disabled = poll.myVoteOptionId != null || !poll.isActive,
                    onClick = { onVote(opt.id) },
                )
                if (idx < poll.options.lastIndex) Spacer(Modifier.height(6.dp))
            }
        } else if (item.text.isNotBlank()) {
            Spacer(Modifier.height(Space.sm))
            Text(item.text, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
        }

        if (item.attachments.isNotEmpty()) {
            val hasTextAbove = (isPoll && poll != null) || item.text.isNotBlank()
            Spacer(Modifier.height(if (hasTextAbove) Space.sm else 2.dp))
            AttachmentsView(attachments = item.attachments)
        }

        if (item.isPinned && !hidePinnedBadge) {
            Spacer(Modifier.height(Space.sm))
            Row(
                modifier = Modifier
                    .clickable(enabled = role.isAdmin) { onTogglePin() }
                    .background(AccentSubtle, RoundedCornerShape(6.dp))
                    .padding(horizontal = Space.sm, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PushPin, contentDescription = null, tint = Accent, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(Space.xs))
                // §TZ-DESKTOP 0.3.1 — LineHeightStyle Center+Trim Both для
                // центровки "Закреплено" по baseline'у с pin-иконкой 12dp.
                // Раньше Text сидел чуть ниже иконки (дефолтный lineHeight
                // 1.3x fontSize давал лишний padding под глифом).
                Text(
                    "Закреплено",
                    color = Accent,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 11.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                        ),
                    ),
                )
            }
        }

        // §TZ-DESKTOP 0.2.0 — реакции: если их НЕТ и аддер не нужен, пропускаем
        // всю секцию (экономит ~34dp мёртвого воздуха). С "+" только если admin
        // может ставить реакции или реакции уже есть.
        val hasReactions = item.reactions.any { it.value > 0 }
        if (hasReactions) {
            Spacer(Modifier.height(Space.sm))
            com.example.otlhelper.desktop.ui.components.ReactionsBar(
                aggregate = item.reactions,
                myReactions = item.myReactions,
                onToggle = { emoji -> onReactionToggle(emoji, emoji in item.myReactions) },
                showAddButton = true,
                onChipLongPress = onReactionLongPress,
            )
        } else {
            // Нет реакций — показываем только "+" (в своём компактном варианте
            // без Row'а с чипами). Это Row'ится в ReactionsBar showAddButton mode.
            Spacer(Modifier.height(Space.xs))
            com.example.otlhelper.desktop.ui.components.ReactionsBar(
                aggregate = emptyMap(),
                myReactions = emptySet(),
                onToggle = { emoji -> onReactionToggle(emoji, false) },
                showAddButton = true,
                onChipLongPress = null,
            )
        }
    }
}


@Composable
private fun PollOptionRow(
    option: NewsRepository.PollOption,
    selected: Boolean,
    disabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) PollOptionSelected else PollOptionNormal
    val border = if (selected) Accent else BorderDivider

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg, RoundedCornerShape(10.dp))
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(option.text, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (option.votesCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text("${option.votesCount}", color = TextSecondary, fontSize = 13.sp)
        }
    }
}
