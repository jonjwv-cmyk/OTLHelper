package com.example.otlhelper.desktop.ui.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.PresencePaused
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import com.example.otlhelper.desktop.theme.UnreadGreen
import kotlinx.coroutines.delay
import com.example.otlhelper.desktop.data.chat.ConversationRepository
import com.example.otlhelper.desktop.ui.components.ThinDivider
import com.example.otlhelper.desktop.ui.components.UserAvatar
import com.example.otlhelper.desktop.ui.dialogs.AttachmentPickerSheet
import com.example.otlhelper.desktop.ui.dialogs.ChatMessageActionPopup

/** Alias для совместимости — репо поставляет тот же тип сообщения. */
typealias ConversationMessage = ConversationRepository.Message

/**
 * §TZ-DESKTOP-0.1.0 — desktop-копия AdminConversationTab.
 * Sticky date-headers (Telegram-style), ChatBubble + ChatMessageActionPopup.
 * Контекст меню в чатах — ТОЛЬКО reactions + Ответить + Копировать (по ТЗ).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationScreen(
    state: ConversationRepository.State,
    onBack: () -> Unit,
    onSend: (text: String, attachments: List<DesktopAttachment>) -> Unit,
    // §TZ-DESKTOP-0.1.0 этап 4c — реакция через API (единственная real-операция
    // из popup'а). Edit/Delete запрещены политикой (§policy — ни Android, ни desktop).
    onToggleReaction: (messageId: Long, emoji: String, alreadyReacted: Boolean) -> Unit = { _, _, _ -> },
    onReactionLongPress: ((messageId: Long, emoji: String) -> Unit)? = null,
    // §TZ-DESKTOP 0.4.x — search-result navigation: после scroll+highlight
    // ConversationScreen вызывает callback чтобы repo погасил scrollToMessageId.
    onScrollTargetConsumed: () -> Unit = {},
) {
    val peerLogin = state.peerLogin
    val peerName = state.peerName
    val peerPresence = state.peerPresence
    val messages = state.messages
    var input by remember { mutableStateOf("") }
    var actionOn by remember { mutableStateOf<ConversationMessage?>(null) }
    var replyTarget by remember { mutableStateOf<ConversationMessage?>(null) }
    val attachments = remember { mutableStateOf<List<DesktopAttachment>>(emptyList()) }
    var attachPickerOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // §TZ-DESKTOP-0.1.0 этап 4c — поведение как в Android, но БЕЗ "пустой экран → прыжок вниз":
    //  • При смене peer'а или первом рендере с messages — МГНОВЕННЫЙ scrollToItem(last).
    //    Repo отдаёт cache синхронно, это один фрейм — не видно "загрузки".
    //  • При поступлении новых сообщений (size ↑ на том же peer'е) — animateScrollToItem.
    //  • Если user сам прокрутил вверх — не бесим: animate делаем только если он был
    //    у нижней кромки (near-bottom heuristic).
    var lastRenderedSize by remember { mutableStateOf(0) }
    var lastRenderedPeer by remember { mutableStateOf("") }
    LaunchedEffect(peerLogin, messages.size) {
        if (messages.isEmpty()) {
            lastRenderedSize = 0
            lastRenderedPeer = peerLogin
            return@LaunchedEffect
        }
        // Если открыли чат с целевым messageId (search navigation) — не
        // прыгаем вниз; ниже LaunchedEffect проскроллит точно к target.
        if (state.scrollToMessageId != null && peerLogin != lastRenderedPeer) {
            lastRenderedSize = messages.size
            lastRenderedPeer = peerLogin
            return@LaunchedEffect
        }
        val lastIdx = messages.lastIndex
        val peerChanged = peerLogin != lastRenderedPeer
        if (peerChanged || lastRenderedSize == 0) {
            // Первый рендер для этого peer'а — без анимации сразу к последнему.
            listState.scrollToItem(lastIdx)
        } else if (messages.size > lastRenderedSize) {
            // Новое сообщение: анимируем вниз только если user уже был у дна.
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val wasNearBottom = total > 0 && lastVisible >= total - 2
            if (wasNearBottom) listState.animateScrollToItem(lastIdx)
        }
        lastRenderedSize = messages.size
        lastRenderedPeer = peerLogin
    }

    // §TZ-DESKTOP 0.4.x — scroll к конкретному сообщению + highlight pulse
    // на 2.5s. Триггерится из search dialog click. Ждём пока messages
    // загрузятся (target может быть в cached-list или после refresh).
    var highlightedMessageId by remember { mutableStateOf<Long?>(null) }
    var lastScrolledTargetId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.scrollToMessageId, messages.size, peerLogin) {
        val target = state.scrollToMessageId
        if (target == null || target == lastScrolledTargetId) return@LaunchedEffect
        val index = messages.indexOfFirst { it.id == target }
        if (index < 0) return@LaunchedEffect  // ждём refresh
        kotlinx.coroutines.delay(80)  // даём layout settle
        runCatching { listState.animateScrollToItem(index) }
        highlightedMessageId = target
        lastScrolledTargetId = target
        delay(2500)
        highlightedMessageId = null
        onScrollTargetConsumed()
    }

    // Группируем по dayLabel сохраняя порядок
    val grouped = remember(messages) {
        val map = linkedMapOf<String, MutableList<ConversationMessage>>()
        for (m in messages) map.getOrPut(m.dayLabel) { mutableListOf() }.add(m)
        map
    }

    Column(modifier = Modifier.fillMaxSize().background(BgApp)) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgCard)
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            UserAvatar(name = peerName, avatarUrl = state.peerAvatarUrl, presenceStatus = peerPresence, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    peerName.ifBlank { "Переписка" },
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                val (label, color) = when (peerPresence) {
                    "online" -> "онлайн" to UnreadGreen
                    "paused" -> "был(а) недавно" to PresencePaused
                    else -> "" to TextTertiary
                }
                if (label.isNotBlank()) {
                    Text(label, color = color, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        ThinDivider()

        // Sticky date-grouped messages + scroll-to-bottom FAB
        val scope = rememberCoroutineScope()
        val showScrollDown by remember(messages.size) {
            derivedStateOf {
                val total = listState.layoutInfo.totalItemsCount
                if (total == 0) return@derivedStateOf false
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible < total - 2
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Bottom,
            ) {
                grouped.forEach { (day, list) ->
                    @OptIn(ExperimentalFoundationApi::class)
                    stickyHeader(key = "day_$day") { DayHeader(day) }
                    items(list, key = { it.id }) { m ->
                        // Highlight wash для search-target message — мягкий
                        // amber pulse за bubble. Tween 600ms на enter/exit
                        // даёт «дышащий» feedback без резких прыжков.
                        val isHighlighted = m.id == highlightedMessageId
                        val bg by androidx.compose.animation.animateColorAsState(
                            targetValue = if (isHighlighted) {
                                com.example.otlhelper.desktop.theme.Accent.copy(alpha = 0.18f)
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                            animationSpec = androidx.compose.animation.core.tween(durationMillis = 600),
                            label = "msg_highlight",
                        )
                        Box(modifier = Modifier.fillMaxWidth().background(bg)) {
                            ChatBubble(
                                text = m.text,
                                time = m.time,
                                isOwn = m.isOwn,
                                isRead = m.isRead,
                                reactions = m.reactions,
                                myReactions = m.myReactions,
                                attachments = m.attachments,
                                onReactionToggle = { emoji, already -> onToggleReaction(m.id, emoji, already) },
                                onReactionLongPress = onReactionLongPress?.let { cb -> { emoji -> cb(m.id, emoji) } },
                                onRequestAction = { actionOn = m },
                            )
                        }
                    }
                }
            }
            // §TZ-DESKTOP 0.3.0 — обёрнуто в AnimatedVisibility с tween(220)
            // fade+scale. Раньше было hard-if → FAB появлялся/исчезал мгновенно
            // («сразу бум и всё» — фидбэк юзера). Идентично NewsTab.
            androidx.compose.animation.AnimatedVisibility(
                visible = messages.isNotEmpty() && showScrollDown,
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
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.scrollToBottom() } },
                    containerColor = BgCard,
                    contentColor = TextSecondary,
                    shape = CircleShape,
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Вниз")
                }
            }
        }

        replyTarget?.let {
            ReplyPreviewBar(
                senderName = if (it.isOwn) "Вы" else peerName,
                text = it.text,
                onCancel = { replyTarget = null },
            )
        }

        InputBar(
            text = input,
            onTextChange = { input = it },
            hint = "Ответить...",
            showSend = true,
            showAttach = true,
            attachments = attachments.value,
            onSend = {
                val txt = input.trim()
                val atts = attachments.value
                if (txt.isNotBlank() || atts.isNotEmpty()) {
                    onSend(txt, atts)
                    input = ""
                    replyTarget = null
                    attachments.value = emptyList()
                }
            },
            onAttachClick = { attachPickerOpen = true },
            onRemoveAttachment = { a -> attachments.value = attachments.value - a },
            // §TZ-DESKTOP 0.3.0 — drag-and-drop из Finder/Explorer в чат.
            onAttachmentsDropped = { atts ->
                attachments.value = attachments.value + atts
            },
        )
    }

    actionOn?.let { m ->
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        // §policy — в чатах ТОЛЬКО reactions + Ответить + Копировать.
        // Edit/Delete запрещены (совпадает с Android).
        ChatMessageActionPopup(
            myReactions = m.myReactions,
            canReply = true,
            canCopy = m.text.isNotBlank(),
            canEdit = false,
            canDelete = false,
            onReact = { emoji, already -> onToggleReaction(m.id, emoji, already) },
            onReply = { replyTarget = m },
            onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.text)) },
            onDismiss = { actionOn = null },
        )
    }

    if (attachPickerOpen) {
        AttachmentPickerSheet(
            onDismiss = { attachPickerOpen = false },
            onPicked = { att -> attachments.value = attachments.value + att },
        )
    }
}

/**
 * §ТЗ — мульти-пас скролл к последнему item'у. После первого animateScrollToItem
 * делаем короткие коррекции потому что sticky-header / lazy items могут менять
 * высоту на последующих frame'ах. Stop когда нижняя кромка last item'а ушла
 * за viewportEnd или исчерпан лимит.
 */
private suspend fun LazyListState.scrollToBottom() {
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return
    val lastIndex = total - 1
    animateScrollToItem(lastIndex)
    repeat(5) { pass ->
        delay(if (pass == 0) 50 else 90)
        val nowLast = layoutInfo.totalItemsCount - 1
        if (nowLast < 0) return
        val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == nowLast }
            ?: run {
                animateScrollToItem(nowLast)
                return@repeat
            }
        val overhang = info.offset + info.size - layoutInfo.viewportEndOffset
        if (overhang <= 0) return
        animateScrollBy(overhang.toFloat())
    }
}

/**
 * §ТЗ — прямая копия app/.../MonitoringTab.DateSeparator.
 * Текст по центру: внешний Box + contentAlignment.Center (горизонталь),
 * lineHeight = fontSize (вертикальная baseline без паразитного offset).
 */
@Composable
private fun DayHeader(label: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = TextPrimary.copy(alpha = 0.85f),
            fontSize = 11.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .background(
                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f),
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
