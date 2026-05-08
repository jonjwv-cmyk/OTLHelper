package com.example.otlhelper.desktop.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.data.network.ApiClient
import com.example.otlhelper.desktop.model.Role
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.ui.SessionRepos
import com.example.otlhelper.desktop.ui.main.workspace.WorkspaceDialogs
import com.example.otlhelper.desktop.ui.main.workspace.buildAttachmentsPayload
import com.example.otlhelper.desktop.ui.main.workspace.filterInbox
import com.example.otlhelper.desktop.ui.main.workspace.filterNews
import com.example.otlhelper.desktop.ui.main.workspace.rememberWorkspaceDialogState
import com.example.otlhelper.desktop.ui.tabs.ChatsPanelContent
import com.example.otlhelper.desktop.ui.tabs.NewsPanelContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** §TZ-DESKTOP-0.1.0 — правая панель-клон Android-приложения.
 *
 *  Репозитории приходят из [SessionRepos] (App-уровень) — сворачивание/
 *  разворачивание панели НЕ пересоздаёт их, данные сохраняются мгновенно. */
@Composable
fun WorkspacePanel(
    login: String,
    fullName: String,
    avatarUrl: String,
    role: Role,
    onLogout: () -> Unit,
    repos: SessionRepos,
    currentTab: Tab,
    onTabChange: (Tab) -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    newsUnread: Int,
    chatsUnread: Int,
    // §TZ-DESKTOP 0.3.2 — передаётся из MainScreen. Пустая строка = без
    // фильтра. Применяется case-insensitive substring к senderName +
    // lastText/text. Фильтр УСТАНАВЛИВАЕТСЯ на CLIENT-стороне (никаких
    // сетевых запросов) — важный инвариант для нового разработчика:
    // серверный поиск не подключается здесь.
    searchQuery: String = "",
    // §TZ-DESKTOP 0.4.x — search-result navigation: MainScreen ставит
    // (peerLogin, peerName, messageId?) когда юзер кликнул result в
    // CentralSearchDialog. WorkspacePanel автоматически открывает чат +
    // прокидывает messageId в ConversationRepository.open для scroll+
    // highlight в ConversationScreen.
    pendingOpenConversation: Triple<String, String, Long?>? = null,
    onPendingOpenConsumed: () -> Unit = {},
    // §TZ-DESKTOP 0.4.x — search-result navigation для news. MainScreen ставит
    // newsId когда юзер кликнул news result. NewsPanelContent проскроллит
    // и подсветит item; clearance через onPendingNewsConsumed.
    pendingScrollNewsId: Long? = null,
    onPendingNewsConsumed: () -> Unit = {},
    // §TZ-DESKTOP-UX-2026-04 — версии Android/БД/Desktop с сервера для
    // footer'а правой панели и Settings.
    versionInfo: com.example.otlhelper.desktop.core.update.VersionInfo =
        com.example.otlhelper.desktop.core.update.VersionInfo(),
    onUpdateRequest: () -> Unit = {},
) {
    // §ТЗ-4b — Pair(login, name). login нужен для get_admin_chat(user_login).
    var openConversation by remember { mutableStateOf<Pair<String, String>?>(null) }

    val repoScope = repos.scope
    val inboxRepo = repos.inboxRepo
    val newsRepo = repos.newsRepo
    val convRepo = repos.convRepo
    val scheduledRepo = repos.scheduledRepo
    val inboxState by inboxRepo.state.collectAsState()
    val newsState by newsRepo.state.collectAsState()
    val convState by convRepo.state.collectAsState()

    // §TZ-DESKTOP 0.3.3 — client-side search-фильтрация (см. workspace/WorkspaceSearch.kt).
    // Tokenized AND-match с confusables-нормализацией; haystack = senderName +
    // login + text + poll(title/desc/options).
    val filteredInboxState = remember(searchQuery, inboxState) {
        filterInbox(inboxState, searchQuery)
    }
    val filteredNewsState = remember(searchQuery, newsState) {
        filterNews(newsState, searchQuery)
    }

    // §TZ-CLEANUP-2026-04-26 — все диалоги и их видимость живут тут.
    val dialogState = rememberWorkspaceDialogState()

    // News input bar state
    var newsText by remember { mutableStateOf("") }
    val newsAttachments = remember { mutableStateOf<List<DesktopAttachment>>(emptyList()) }
    // Статус последнего запланирования / лимита pin'ов — показываем в InputBar 4 секунды.
    var scheduleNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scheduleNotice) {
        if (scheduleNotice != null) {
            delay(4_000)
            scheduleNotice = null
        }
    }

    // §TZ-DESKTOP 0.4.x — реакция на pending search-navigation. Открываем
    // конкретный чат + прокидываем messageId. После consumed — clear.
    LaunchedEffect(pendingOpenConversation) {
        val pending = pendingOpenConversation ?: return@LaunchedEffect
        val (login, name, msgId) = pending
        openConversation = login to name
        convRepo.open(peerLogin = login, peerName = name, scrollToMessageId = msgId)
        onPendingOpenConsumed()
    }

    // Если открыта любая редактирующая модалка — ставим polling на паузу,
    // чтобы 10/20-секундные тики не дёргали recompose и не теряли state
    // полей ввода (user жаловался «выбрасывает при создании опроса»).
    val anyModalOpen = dialogState.anyModalOpen(menuOpen)
    LaunchedEffect(anyModalOpen) {
        newsRepo.setPaused(anyModalOpen)
        inboxRepo.setPaused(anyModalOpen)
    }

    // Scroll-стейты hoisted на уровень WorkspacePanel — чтобы переключение
    // между Новостями и Чатами не сбрасывало позицию (раньше LazyListState
    // / ScrollState жили внутри вкладок и теряли state при unmount).
    val newsListState = rememberLazyListState()
    val chatsScrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(BgApp)
            .border(0.5.dp, BorderDivider),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val conv = openConversation
            if (conv != null) {
                ConversationScreen(
                    state = convState,
                    onBack = {
                        openConversation = null
                        convRepo.close()
                    },
                    onSend = { text, atts ->
                        val payload = buildAttachmentsPayload(atts)
                        repoScope.launch {
                            convRepo.send(text, payload)
                            inboxRepo.refresh()
                        }
                    },
                    onToggleReaction = { id, emoji, already ->
                        repoScope.launch { convRepo.toggleReaction(id, emoji, already) }
                    },
                    onReactionLongPress = if (role.canSeeReactionVoters) {
                        { id, _ -> dialogState.reactionVotersMessageId = id }
                    } else null,
                    onScrollTargetConsumed = { convRepo.clearScrollTarget() },
                )
            } else {
                // §TZ-DESKTOP 0.3.1 — ИСПРАВЛЕНО направление анимации:
                // раньше использовали ordinal (Chats=0, News=1) но он НЕ
                // совпадает с визуальным порядком в [BottomTabBar]
                // (Настройки | Новости | Чаты). Пользователь видит Чаты
                // справа, Новости слева. Поэтому завязываемся на конкретную
                // таб-ячейку:
                //   • goingToRightTab = target == Chats (Chats = rightmost)
                //     — новая приходит СПРАВА, старая уходит ВЛЕВО
                //   • иначе (target == News) — новая слева, старая вправо
                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = {
                        val goingToRightTab = targetState == Tab.Chats
                        val dur = 220
                        if (goingToRightTab) {
                            slideInHorizontally(tween(dur)) { w -> w } + fadeIn(tween(dur)) togetherWith
                                slideOutHorizontally(tween(dur)) { w -> -w } + fadeOut(tween(dur))
                        } else {
                            slideInHorizontally(tween(dur)) { w -> -w } + fadeIn(tween(dur)) togetherWith
                                slideOutHorizontally(tween(dur)) { w -> w } + fadeOut(tween(dur))
                        }
                    },
                    label = "tabContent",
                ) { tab ->
                    when (tab) {
                        Tab.Chats -> ChatsPanelContent(
                            state = filteredInboxState,
                            scrollState = chatsScrollState,
                            onOpenConversation = { peerLogin, peerName ->
                                openConversation = peerLogin to peerName
                                // §TZ-0.10.8 — pre-fill peerAvatarUrl из inbox-row
                                // (там уже decode'нутый avatar от prefetch'а), чтобы
                                // header чата сразу рисовал аватарку, не ждал пока
                                // peer пришлёт сообщение.
                                val avatarUrl = inboxState.rows
                                    .firstOrNull { it.senderLogin == peerLogin }
                                    ?.senderAvatarUrl
                                    .orEmpty()
                                convRepo.open(
                                    peerLogin = peerLogin,
                                    peerName = peerName,
                                    peerAvatarUrl = avatarUrl,
                                )
                            },
                        )
                        Tab.News -> NewsPanelContent(
                            state = filteredNewsState,
                            role = role,
                            listState = newsListState,
                            scrollToNewsId = pendingScrollNewsId,
                            onScrollTargetConsumed = onPendingNewsConsumed,
                            onToggleReaction = { id, emoji, already ->
                                repoScope.launch { newsRepo.toggleReaction(id, emoji, already) }
                            },
                            onReactionLongPress = if (role.canSeeReactionVoters) {
                                { id, _ -> dialogState.reactionVotersMessageId = id }
                            } else null,
                            onVote = { pollId, optId ->
                                repoScope.launch { newsRepo.vote(pollId, optId) }
                            },
                            onTogglePin = { id, pin ->
                                // §TZ-DESKTOP — лимит 3 закрепа (news + poll вместе).
                                val already = newsState.pinnedItems.any { it.id == id }
                                if (pin && !already && newsState.pinnedItems.size >= 3) {
                                    scheduleNotice = "Максимум 3 закрепа"
                                } else {
                                    repoScope.launch { newsRepo.togglePin(id, pin) }
                                }
                            },
                            onOpenReaders = { messageId ->
                                dialogState.readersTarget = messageId
                                dialogState.readersIsPinned =
                                    newsState.items.firstOrNull { it.id == messageId }?.isPinned ?: false
                            },
                            onOpenPollStats = { pollId, messageId ->
                                dialogState.pollStatsTarget = pollId to messageId
                                dialogState.pollStatsIsPinned =
                                    newsState.items.firstOrNull { it.id == messageId }?.isPinned ?: false
                            },
                            onEditNews = { id, newText ->
                                repoScope.launch { newsRepo.editNews(id, newText) }
                            },
                            onDeleteNews = { id ->
                                repoScope.launch { newsRepo.deleteNews(id) }
                            },
                            onMarkRead = { id ->
                                repoScope.launch { newsRepo.markRead(id) }
                            },
                        )
                    }
                }
            }
        }

        // InputBar для ленты новостей (только admin+, не в переписке)
        if (openConversation == null && currentTab == Tab.News && role.canCreateNews) {
            InputBar(
                text = newsText,
                onTextChange = { newsText = it },
                hint = "Написать новость...",
                showSend = true,
                showAttach = true,
                attachments = newsAttachments.value,
                notice = scheduleNotice,
                onSend = {
                    val txt = newsText.trim()
                    val attList = newsAttachments.value
                    if (txt.isNotBlank() || attList.isNotEmpty()) {
                        val attPayload = buildAttachmentsPayload(attList)
                        repoScope.launch {
                            val resp = ApiClient.sendNews(txt, attPayload)
                            if (resp.optBoolean("ok", false)) newsRepo.forceRefresh()
                        }
                        newsText = ""
                        newsAttachments.value = emptyList()
                    }
                },
                onAttachClick = { dialogState.attachPickerOpen = true },
                onRemoveAttachment = { a -> newsAttachments.value = newsAttachments.value - a },
                onScheduleClick = if (role.canScheduleMessages) {
                    { dialogState.newsScheduleOpen = true }
                } else null,
                // §TZ-DESKTOP 0.3.0 — drag-and-drop из Finder/Explorer.
                onAttachmentsDropped = { atts ->
                    newsAttachments.value = newsAttachments.value + atts
                },
            )
        }

        // §TZ-DESKTOP-UX-2026-04 — версии больше НЕ показываем под строкой
        // поиска: только в Меню → Настройки → «Последние версии».
        BottomTabBar(
            activeTab = currentTab,
            menuOpen = menuOpen,
            newsUnread = newsUnread,
            chatsUnread = chatsUnread,
            onTabSelected = {
                onTabChange(it)
                onMenuOpenChange(false)
                if (openConversation != null) {
                    openConversation = null
                    convRepo.close()
                }
            },
            onMenuClick = { onMenuOpenChange(true) },
        )
    }

    // §TZ-CLEANUP-2026-04-26 — все модальные диалоги (Menu + sub-sheets +
    // stats + InputBar pickers) вынесены в один composable. Управление
    // видимостью + lazy-loaded данные stats живут в [dialogState].
    WorkspaceDialogs(
        state = dialogState,
        role = role,
        login = login,
        fullName = fullName,
        avatarUrl = avatarUrl,
        repos = repos,
        menuOpen = menuOpen,
        onMenuOpenChange = onMenuOpenChange,
        onLogout = onLogout,
        newsState = newsState,
        versionInfo = versionInfo,
        onUpdateRequest = onUpdateRequest,
        onScheduleNotice = { msg -> scheduleNotice = msg },
        onAttachmentPicked = { att -> newsAttachments.value = newsAttachments.value + att },
        onSchedulePicked = { iso ->
            val txt = newsText.trim()
            if (txt.isBlank()) {
                scheduleNotice = "Введите текст новости перед планированием"
            } else {
                repoScope.launch {
                    val ok = scheduledRepo.scheduleNews(txt, iso)
                    scheduleNotice = if (ok) "Запланировано" else "Не удалось запланировать"
                    if (ok) {
                        newsText = ""
                        newsAttachments.value = emptyList()
                    }
                }
            }
        },
    )
}

