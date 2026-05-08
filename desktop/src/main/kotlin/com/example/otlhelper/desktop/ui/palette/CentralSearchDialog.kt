package com.example.otlhelper.desktop.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.data.chat.ConversationRepository
import com.example.otlhelper.desktop.data.chat.InboxRepository
import com.example.otlhelper.desktop.data.feed.NewsRepository
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BgInput
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import com.example.otlhelper.desktop.ui.AppOverlay

/**
 * §TZ-DESKTOP 0.3.3 — центральный search-модал. Дизайн вдохновлён Claude
 * Code: rounded card, single-column list с timestamp'ами справа, scrim
 * сверху и close-on-outside-click.
 *
 * **§RULE-DESKTOP-OVERLAY-2026-04-26 (rev 2):** рендерится через
 * [AppOverlay] (ComposePanel в JLayeredPane.MODAL_LAYER главного окна),
 * а НЕ через DialogWindow. Причины:
 *   1. **Heavyweight Skiko surface** в MODAL_LAYER корректно покрывает
 *      heavyweight webview NSView (тот же z-order pattern что splash
 *      cover Sheets).
 *   2. **Внутри main app** — двигается / ресайзится с окном (DialogWindow
 *      требовало ручной sync через ComponentListener с заметным lag).
 *   3. **Single-window screenshot** — overlay часть main window, попадает
 *      в один screenshot.
 *   4. **No focus race** — TextField в active Compose tree сразу focusable
 *      без Native window mount delay (исправляет «бум → умб»).
 *
 * Matching: tokenized AND-поиск с confusables-нормализацией ([matchesQuery]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CentralSearchDialog(
    inboxState: InboxRepository.State,
    newsState: NewsRepository.State,
    // §TZ-DESKTOP 0.4.x — ConversationRepository передаётся для full-text
    // поиска по cached chat messages (peers которые юзер уже открывал в
    // этой сессии). Server-side search endpoint отсутствует — это
    // частичный full-archive search.
    conversationRepo: ConversationRepository,
    initialQuery: String = "",
    // Третий параметр messageId — id последнего сообщения чата для
    // navigation: после открытия чата ConversationScreen скроллит к нему
    // и подсвечивает (см. ConversationRepository.scrollToMessageId).
    onOpenChat: (peerLogin: String, peerName: String, messageId: Long?) -> Unit,
    onOpenNewsTab: (newsId: Long?) -> Unit,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    rightInset: Dp = 0.dp,
) {
    AppOverlay(onDismiss = onDismiss) {
        val query = remember { mutableStateOf(initialQuery) }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            // Нет race — overlay ComposePanel мгновенно in tree, focus
            // grabs synchronously после первого composition (~16ms).
            runCatching { focusRequester.requestFocus() }
        }

        // §TZ-DESKTOP-UX-2026-04 — на macOS пока поиск открыт прячем WKWebView
        // целиком. CSS-blur оставлял heavyweight NSView активным и он крал
        // keyboard/mouse events. На Win overlay рисуется отдельным DialogWindow
        // поверх — KCEF browser сам под ним, прятать не нужно.
        DisposableEffect(Unit) {
            val isMac = System.getProperty("os.name")?.lowercase()?.contains("mac") == true
            if (isMac) {
                val bridge = com.example.otlhelper.desktop.sheets.SheetsViewBridge
                bridge.browser?.setVisible(false)
                onDispose { bridge.browser?.setVisible(true) }
            } else {
                onDispose { }
            }
        }

        val q = query.value
        val isQuerying = q.isNotBlank()
        val filteredInbox = remember(q, inboxState) {
            if (q.isBlank()) emptyList() else inboxState.rows.filter { row ->
                matchesQuery(
                    haystack = (row.senderName + " " + row.senderLogin + " " + row.lastText),
                    query = q,
                )
            }.take(8)
        }
        val filteredNews = remember(q, newsState) {
            if (q.isBlank()) emptyList() else newsState.items.filter { item ->
                matchesQuery(haystack = newsHaystack(item), query = q)
            }.take(8)
        }
        // §TZ-DESKTOP 0.4.x — full-text search через cached chat messages.
        val messageHits = remember(q, inboxState) {
            if (q.isBlank()) emptyList() else conversationRepo.searchMessages(q, limit = 8)
        }
        val peerNameByLogin = remember(inboxState) {
            inboxState.rows.associate { it.senderLogin to it.senderName }
        }
        // Recent — только чаты с последним сообщением (`lastText`
        // непустой). Те где никто не писал — мусор для quick-jump.
        val recentInbox = remember(inboxState) {
            inboxState.rows.filter { it.lastText.isNotBlank() }.take(5)
        }
        val recentNews = remember(newsState) {
            newsState.items.filter { it.text.isNotBlank() || it.poll != null }.take(3)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                        onDismiss(); true
                    } else false
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Box(
                modifier = Modifier.fillMaxSize().padding(end = rightInset),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(620.dp)
                        .height(480.dp)
                        .shadow(elevation = 24.dp, shape = RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(BgElevated)
                        .border(0.5.dp, BorderDivider, RoundedCornerShape(16.dp)),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        SearchInputBar(
                            query = q,
                            focusRequester = focusRequester,
                            onChange = { newQ ->
                                query.value = newQ
                                onQueryChange(newQ)
                            },
                            onClear = {
                                query.value = ""
                                onQueryChange("")
                            },
                            onClose = onDismiss,
                        )
                        com.example.otlhelper.desktop.ui.components.ThinDivider()
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            SearchResults(
                                isQuerying = isQuerying,
                                filteredInbox = filteredInbox,
                                filteredNews = filteredNews,
                                messageHits = messageHits,
                                peerNameByLogin = peerNameByLogin,
                                recentInbox = recentInbox,
                                recentNews = recentNews,
                                onOpenChat = onOpenChat,
                                onOpenNewsTab = onOpenNewsTab,
                                onDismiss = onDismiss,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchInputBar(
    query: String,
    focusRequester: FocusRequester,
    onChange: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "Поиск по чатам и новостям…",
                    color = TextTertiary,
                    fontSize = 14.sp,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        if (query.isNotEmpty()) {
            // Cancel (X-в-круге) — визуально отличается от Close (просто X)
            // чтобы юзер не путал «очистить запрос» и «закрыть поиск».
            IconChip(onClick = onClear, icon = Icons.Outlined.Cancel, tooltip = "Очистить")
            Spacer(Modifier.width(6.dp))
        }
        IconChip(onClick = onClose, icon = Icons.Outlined.Close, tooltip = "Закрыть")
    }
}

@Composable
private fun IconChip(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tooltip: String,
) {
    // §TZ-DESKTOP-UX-2026-04 — раньше hover/press считались через
    // `onPointerEvent(Press/Release)` поверх `clickable`. На некоторых
    // Compose Desktop версиях эти модификаторы потребляют тот же event
    // что и clickable → click не срабатывал (юзер: «кнопки окна закрытия
    // не работают»). Фикс: hover/press из MutableInteractionSource через
    // hoverable + clickable — стандартный pattern, click гарантированно
    // приходит в onClick.
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val bg = when {
        pressed -> Accent.copy(alpha = 0.18f)
        hovered -> BgInput
        else -> Color.Transparent
    }
    val tint = if (hovered || pressed) TextPrimary else TextTertiary
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = tooltip, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SearchResults(
    isQuerying: Boolean,
    filteredInbox: List<InboxRepository.Row>,
    filteredNews: List<NewsRepository.Item>,
    messageHits: List<ConversationRepository.MessageHit>,
    peerNameByLogin: Map<String, String>,
    recentInbox: List<InboxRepository.Row>,
    recentNews: List<NewsRepository.Item>,
    onOpenChat: (peerLogin: String, peerName: String, messageId: Long?) -> Unit,
    onOpenNewsTab: (newsId: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    when {
        !isQuerying && recentInbox.isEmpty() && recentNews.isEmpty() -> EmptyHint(
            primary = "Начните вводить запрос",
            secondary = "Поиск по чатам, людям, новостям и опросам",
        )
        !isQuerying -> Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (recentInbox.isNotEmpty()) {
                SectionHeader("Недавние чаты")
                recentInbox.forEach { row ->
                    ChatResultRow(
                        row = row,
                        onClick = {
                            onOpenChat(row.senderLogin, row.senderName, row.lastMessageId.takeIf { it > 0 })
                            onDismiss()
                        },
                    )
                }
            }
            if (recentNews.isNotEmpty()) {
                SectionHeader("Свежие новости")
                recentNews.forEach { item ->
                    NewsResultRow(
                        item = item,
                        onClick = {
                            onOpenNewsTab(item.id.takeIf { it > 0 })
                            onDismiss()
                        },
                    )
                }
            }
        }
        filteredInbox.isEmpty() && filteredNews.isEmpty() && messageHits.isEmpty() -> EmptyHint(
            primary = "Ничего не найдено",
            secondary = "Попробуйте изменить запрос",
        )
        else -> Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (messageHits.isNotEmpty()) {
                SectionHeader("Сообщения")
                messageHits.forEach { hit ->
                    MessageHitRow(
                        hit = hit,
                        peerName = peerNameByLogin[hit.peerLogin] ?: hit.peerLogin,
                        onClick = {
                            onOpenChat(
                                hit.peerLogin,
                                peerNameByLogin[hit.peerLogin] ?: hit.peerLogin,
                                hit.message.id.takeIf { it > 0 },
                            )
                            onDismiss()
                        },
                    )
                }
            }
            if (filteredInbox.isNotEmpty()) {
                SectionHeader("Чаты")
                filteredInbox.forEach { row ->
                    ChatResultRow(
                        row = row,
                        onClick = {
                            onOpenChat(row.senderLogin, row.senderName, row.lastMessageId.takeIf { it > 0 })
                            onDismiss()
                        },
                    )
                }
            }
            if (filteredNews.isNotEmpty()) {
                SectionHeader("Новости")
                filteredNews.forEach { item ->
                    NewsResultRow(
                        item = item,
                        onClick = {
                            onOpenNewsTab(item.id.takeIf { it > 0 })
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label.uppercase(),
        color = TextTertiary,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyHint(primary: String, secondary: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(primary, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                secondary,
                color = TextTertiary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MessageHitRow(
    hit: ConversationRepository.MessageHit,
    peerName: String,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) BgInput else Color.Transparent)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.ChatBubble,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                peerName,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hit.message.text,
                color = TextTertiary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        DateLabel(formatRelativeDate(hit.message.rawCreatedAt))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChatResultRow(row: InboxRepository.Row, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) BgInput else Color.Transparent)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.ChatBubble,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.senderName.ifBlank { row.senderLogin },
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.lastText.isNotBlank()) {
                Text(
                    row.lastText,
                    color = TextTertiary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (row.unreadCount > 0) {
            UnreadBadge(count = row.unreadCount)
            Spacer(Modifier.width(8.dp))
        }
        DateLabel(formatRelativeDate(row.createdAt))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NewsResultRow(item: NewsRepository.Item, onClick: () -> Unit) {
    val isPoll = item.kind == "poll"
    val preview = when {
        isPoll -> item.poll?.title.orEmpty().ifBlank { item.poll?.description.orEmpty() }
        else -> item.text
    }
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) BgInput else Color.Transparent)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Newspaper,
            contentDescription = null,
            tint = if (isPoll) Accent else TextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.senderName,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    color = TextTertiary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (isPoll) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(AccentSubtle)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("Опрос", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(8.dp))
        }
        DateLabel(formatRelativeDate(item.createdAt))
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(AccentSubtle)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(count.toString(), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DateLabel(text: String) {
    if (text.isEmpty()) return
    Text(text, color = TextTertiary, fontSize = 11.sp)
}

/**
 * §TZ-DESKTOP 0.3.4 — relative-date formatter («только что», «5 мин»,
 * «вчера», «6 апр»). Принимает ISO-8601 / millis / LocalDateTime string;
 * на parse failure возвращает пустую строку (label не рисуется).
 */
private fun formatRelativeDate(createdAt: String): String {
    if (createdAt.isBlank()) return ""
    val instant = parseToInstant(createdAt) ?: return ""
    val now = java.time.Instant.now()
    val diff = java.time.Duration.between(instant, now)
    return when {
        diff.isNegative -> ""
        diff.toMinutes() < 1 -> "только что"
        diff.toMinutes() < 60 -> "${diff.toMinutes()} мин"
        diff.toHours() < 24 -> "${diff.toHours()} ч"
        diff.toDays() < 2 -> "вчера"
        diff.toDays() < 7 -> "${diff.toDays()} дн"
        else -> {
            val zoned = instant.atZone(java.time.ZoneId.systemDefault())
            zoned.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
        }
    }
}

private fun parseToInstant(raw: String): java.time.Instant? {
    runCatching { return java.time.Instant.parse(raw) }
    runCatching { return java.time.Instant.ofEpochMilli(raw.toLong()) }
    runCatching {
        return java.time.LocalDateTime.parse(raw)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
    }
    return null
}

// ── Match utilities ────────────────────────────────────────────────────────

private val cyrToLat = mapOf(
    'а' to 'a', 'в' to 'b', 'с' to 'c', 'е' to 'e', 'н' to 'h',
    'к' to 'k', 'м' to 'm', 'о' to 'o', 'р' to 'p', 'т' to 't',
    'у' to 'y', 'х' to 'x',
    'ё' to 'e',
)

private fun normalizeForSearch(s: String): String {
    val lower = s.lowercase()
    val sb = StringBuilder(lower.length)
    for (ch in lower) {
        sb.append(cyrToLat[ch] ?: ch)
    }
    return sb.toString()
}

fun matchesQuery(haystack: String, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    val tokens = q.split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { normalizeForSearch(it) }
    if (tokens.isEmpty()) return true
    val normalized = normalizeForSearch(haystack)
    return tokens.all { normalized.contains(it) }
}

fun newsHaystack(item: NewsRepository.Item): String = buildString {
    append(item.senderName).append(' ')
    append(item.text).append(' ')
    item.poll?.let { poll ->
        append(poll.title).append(' ')
        append(poll.description).append(' ')
        poll.options.forEach { opt -> append(opt.text).append(' ') }
    }
    item.attachments.forEach { att ->
        append(att.fileName).append(' ')
    }
}
