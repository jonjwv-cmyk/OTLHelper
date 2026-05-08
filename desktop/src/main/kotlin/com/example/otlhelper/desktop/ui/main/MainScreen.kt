package com.example.otlhelper.desktop.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.otlhelper.desktop.model.Role
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.ui.SessionRepos
import com.example.otlhelper.desktop.ui.components.SearchBar
import com.example.otlhelper.desktop.ui.components.Tooltip
import com.example.otlhelper.desktop.ui.palette.CentralSearchDialog
import com.example.otlhelper.desktop.ui.palette.CommandPalette
import com.example.otlhelper.desktop.ui.palette.LocalShortcuts
import com.example.otlhelper.desktop.ui.palette.PaletteCommand
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Newspaper

enum class Tab { Chats, News }

private val COLLAPSED_WIDTH = 64.dp
private val DEFAULT_PANEL_WIDTH = 280.dp
private val MIN_PANEL_WIDTH = 280.dp
private val MAX_PANEL_WIDTH = 520.dp
private val TOP_CONTROL_HEIGHT = 40.dp

/**
 * §TZ-DESKTOP-0.1.0 — resizable layout:
 * - дефолт 280dp, drag 280–520 dp, при свёрнутой — 40dp рейка с иконками
 *   Menu/News/Chats (та же семантика что в BottomTabBar).
 *
 * currentTab / menuOpen hoisted на этот уровень чтобы переключение
 * collapsed↔expanded сохраняло состояние, и чтобы collapsed-рейка
 * могла переключать таб без перерисовки WorkspacePanel.
 */
@Composable
fun MainScreen(
    login: String,
    fullName: String,
    avatarUrl: String,
    role: Role,
    onLogout: () -> Unit,
    repos: SessionRepos,
    versionInfo: com.example.otlhelper.desktop.core.update.VersionInfo =
        com.example.otlhelper.desktop.core.update.VersionInfo(),
    onUpdateRequest: () -> Unit = {},
) {
    var panelWidth by remember { mutableStateOf(DEFAULT_PANEL_WIDTH) }
    var isCollapsed by remember { mutableStateOf(false) }
    var currentTab by remember { mutableStateOf(Tab.News) }
    var menuOpen by remember { mutableStateOf(false) }
    // §TZ-DESKTOP 0.3.0 — Cmd+K palette state.
    var paletteOpen by remember { mutableStateOf(false) }
    // §TZ-DESKTOP 0.3.x rev — единственный search modal (CentralSearchDialog).
    // Inline search в развёрнутой панели убран — везде один UX (юзер фидбэк
    // 2026-04-26). searchQuery передаётся в central search как initialQuery.
    var centralSearchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // §TZ-DESKTOP 0.4.x — search-result navigation на конкретный chat
    // message. Triple = (peerLogin, peerName, messageId?). WorkspacePanel
    // подхватит, откроет чат и проскроллит к message.
    var pendingOpenConversation by remember {
        mutableStateOf<Triple<String, String, Long?>?>(null)
    }
    var pendingScrollNewsId by remember { mutableStateOf<Long?>(null) }
    val density = LocalDensity.current

    // §TZ-DESKTOP 0.3.0 — привязка global shortcuts к state-actions на
    // MainScreen-уровне. Main.kt перехватывает KeyEvent, сопоставляет
    // сочетание, дёргает соответствующий lambda ниже. Каждый recomposition
    // перезаписывает lambda'ы — это нужно потому что mutableState (currentTab,
    // isCollapsed) замыкается в лямбду по значению.
    val shortcuts = LocalShortcuts.current
    LaunchedEffect(currentTab, isCollapsed, menuOpen, paletteOpen, centralSearchOpen) {
        shortcuts.onTogglePalette = { paletteOpen = !paletteOpen }
        shortcuts.onSwitchToChats = {
            currentTab = Tab.Chats
            menuOpen = false
            paletteOpen = false
        }
        shortcuts.onSwitchToNews = {
            currentTab = Tab.News
            menuOpen = false
            paletteOpen = false
        }
        shortcuts.onToggleSidebar = { isCollapsed = !isCollapsed }
        shortcuts.onToggleSearch = {
            centralSearchOpen = !centralSearchOpen
            if (!centralSearchOpen) searchQuery = ""
        }
        shortcuts.onCloseModals = {
            when {
                paletteOpen -> paletteOpen = false
                centralSearchOpen -> { centralSearchOpen = false; searchQuery = "" }
                menuOpen -> menuOpen = false
            }
        }
    }

    // §TZ-DESKTOP 0.3.1 — синхронные animationSpec'и для width и content.
    // Раньше: width = default spring, content = AnimatedContent со slide(260).
    // Разные длительности и easing'и → "мало кадров", рывки. Теперь оба
    // tween(280, FastOutSlowInEasing) — визуально гладкая синхронная анимация.
    val effectiveWidth by animateDpAsState(
        targetValue = if (isCollapsed) COLLAPSED_WIDTH else panelWidth,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "panelWidth",
    )
    val sheetsOverlayRightInset = effectiveWidth

    val inboxState by repos.inboxRepo.state.collectAsState()
    val newsState by repos.newsRepo.state.collectAsState()
    val chatsUnread = inboxState.rows.sumOf { it.unreadCount }
    val newsUnread = newsState.items.count { !it.isRead && !it.isOwn }

    // §TZ-DESKTOP 0.3.0 — Box wrap для того чтобы CommandPalette мог
    // рендериться overlay'ем поверх всего Row.
    Box(modifier = Modifier.fillMaxSize()) {
    Row(modifier = Modifier.fillMaxSize().background(BgApp)) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // §TZ-DESKTOP 0.4.0 — Sheets-зона. SheetsWorkspace внутри лениво
            // запускает KCEF (Chromium runtime ~150 MB на первом запуске,
            // фоном) и при готовности грузит Google Sheets с нашей CSS-маской.
            com.example.otlhelper.desktop.sheets.SheetsWorkspace(
                currentUserName = fullName.ifBlank { login },
                modalRightInset = sheetsOverlayRightInset,
            )
        }

        if (!isCollapsed) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(BorderDivider)
                    .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.W_RESIZE_CURSOR)))
                    .pointerInput(Unit) {
                        detectDragGestures { _, drag ->
                            val deltaDp = with(density) { drag.x.toDp() }
                            val newWidth = (panelWidth - deltaDp).coerceIn(MIN_PANEL_WIDTH, MAX_PANEL_WIDTH)
                            panelWidth = newWidth
                        }
                    },
            )
        }

        // §TZ-DESKTOP 0.3.2 — ФИНАЛЬНЫЙ подход к анимации раскрытия панели.
        //
        // Проблема: при Crossfade во время width-анимации ExpandedColumn
        // содержит LazyColumn (NewsPanelContent), который re-measure'ит
        // items на каждый кадр меняющейся ширины → видимые рывки ("глючит
        // с лагом"). Особенно заметно в NewsTab (LazyColumn с sticky-
        // header'ами); Chats использует простой Column+verticalScroll и
        // не страдает.
        //
        // Решение: убрали Crossfade. Rendering — instant switch по
        // isCollapsed. Контент рендерится в inner Box с фиксированной
        // target-width'ой (COLLAPSED_WIDTH или panelWidth) — поэтому
        // LazyColumn всегда измеряется на своём СТАБИЛЬНОМ target-width,
        // никаких re-measure'ов. Outer Box с anim-width + clipToBounds
        // играет роль "занавеса" который открывает/закрывает видимую часть
        // контента. Ширина анимируется гладко (tween 280), контент
        // "проявляется" через растущий clip.
        //
        // Single-state render (не оба одновременно как при Crossfade) →
        // меньше работы на кадр → плавнее. Без fade контент переключается
        // жестко в момент клика, но ширина плавно открывается/закрывается,
        // маскируя жёсткость.
        Box(
            modifier = Modifier
                .width(effectiveWidth)
                .fillMaxHeight()
                .background(BgCard)
                .clipToBounds(),
        ) {
            if (isCollapsed) {
                Box(modifier = Modifier.requiredWidth(COLLAPSED_WIDTH).fillMaxHeight()) {
                    CollapsedStrip(
                        activeTab = currentTab,
                        menuOpen = menuOpen,
                        newsUnread = newsUnread,
                        chatsUnread = chatsUnread,
                        onExpand = { isCollapsed = false },
                        onMenuClick = { menuOpen = true; isCollapsed = false },
                        onTabSelected = { currentTab = it; menuOpen = false; isCollapsed = false },
                        onSearchClick = {
                            // §TZ-DESKTOP 0.3.3 — при свёрнутой панели НЕ
                            // раскрываем её. Открываем центральный
                            // search-модал поверх приложения (фидбэк:
                            // "как было ранее").
                            centralSearchOpen = true
                        },
                    )
                }
            } else {
                Column(modifier = Modifier.requiredWidth(panelWidth).fillMaxHeight()) {
                    // §TZ-DESKTOP 0.3.x rev — search-кнопка открывает central
                    // search overlay (один UX в свёрнутом и раскрытом режиме).
                    PanelToggleBar(
                        onCollapse = { isCollapsed = true },
                        onOpenSearch = { centralSearchOpen = true },
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        WorkspacePanel(
                            login = login,
                            fullName = fullName,
                            avatarUrl = avatarUrl,
                            role = role,
                            onLogout = onLogout,
                            repos = repos,
                            currentTab = currentTab,
                            onTabChange = { currentTab = it },
                            menuOpen = menuOpen,
                            onMenuOpenChange = { menuOpen = it },
                            newsUnread = newsUnread,
                            chatsUnread = chatsUnread,
                            searchQuery = "",
                            pendingOpenConversation = pendingOpenConversation,
                            onPendingOpenConsumed = { pendingOpenConversation = null },
                            pendingScrollNewsId = pendingScrollNewsId,
                            onPendingNewsConsumed = { pendingScrollNewsId = null },
                            versionInfo = versionInfo,
                            onUpdateRequest = onUpdateRequest,
                        )
                    }
                }
            }
        }
    }

    // §TZ-DESKTOP 0.3.3 — Central search modal (для свёрнутой панели).
    // Рендерится поверх всего приложения. При клике на результат:
    //   • Chat → раскрывает панель + переключает на Чаты + user кликает в списке
    //   • News → раскрывает панель + переключает на Новости
    // (opening conversation напрямую требует поднять openConversation state из
    // WorkspacePanel на MainScreen — не стали делать чтобы не ломать архитектуру,
    // UX через tab-switch + visible list приемлем для MVP.)
    if (centralSearchOpen) {
        CentralSearchDialog(
            inboxState = inboxState,
            newsState = newsState,
            conversationRepo = repos.convRepo,
            initialQuery = searchQuery,
            onOpenChat = { peerLogin, peerName, messageId ->
                centralSearchOpen = false
                isCollapsed = false
                currentTab = Tab.Chats
                pendingOpenConversation = Triple(peerLogin, peerName, messageId)
            },
            onOpenNewsTab = { newsId ->
                centralSearchOpen = false
                isCollapsed = false
                currentTab = Tab.News
                pendingScrollNewsId = newsId
            },
            onQueryChange = { searchQuery = it },
            onDismiss = {
                centralSearchOpen = false
                searchQuery = ""
            },
            rightInset = sheetsOverlayRightInset,
        )
    }

    // §TZ-DESKTOP 0.3.0 — Cmd+K palette overlay.
    // Строим список команд каждый render на основе state (currentTab,
    // isCollapsed, menuOpen, role). Команды — простые callbacks, никаких
    // серверных вызовов. Для добавления новой команды:
    //   1. Если она соответствует существующему state-callback → просто
    //      дописать PaletteCommand
    //   2. Если нужен новый action (например "открыть аккаунт") → нужно
    //      вынести state (accountOpen) из WorkspacePanel на MainScreen
    //      или добавить CompositionLocal типа LocalNavActions
    if (paletteOpen) {
        val commands = buildPaletteCommands(
            currentTab = currentTab,
            isCollapsed = isCollapsed,
            menuOpen = menuOpen,
            onSwitchToChats = {
                currentTab = Tab.Chats
                menuOpen = false
            },
            onSwitchToNews = {
                currentTab = Tab.News
                menuOpen = false
            },
            onToggleSidebar = { isCollapsed = !isCollapsed },
            onToggleMenu = { menuOpen = !menuOpen },
            onLogout = onLogout,
        )
        CommandPalette(
            commands = commands,
            onDismiss = { paletteOpen = false },
        )
    }
    }  // end Box
}

/** §TZ-DESKTOP 0.3.0 — fabrика команд для CommandPalette. Вынесено в отдельную
 *  функцию для тестируемости и читабельности MainScreen. Labels зависят от
 *  текущего state (напр. "Скрыть панель" / "Раскрыть панель"). */
private fun buildPaletteCommands(
    currentTab: Tab,
    isCollapsed: Boolean,
    menuOpen: Boolean,
    onSwitchToChats: () -> Unit,
    onSwitchToNews: () -> Unit,
    onToggleSidebar: () -> Unit,
    onToggleMenu: () -> Unit,
    onLogout: () -> Unit,
): List<PaletteCommand> = buildList {
    if (currentTab != Tab.Chats) {
        add(PaletteCommand(
            id = "switch.chats",
            label = "Открыть чаты",
            icon = Icons.Outlined.ChatBubble,
            shortcut = "⌘1",
            keywords = listOf("chats", "сообщения", "inbox"),
            action = onSwitchToChats,
        ))
    }
    if (currentTab != Tab.News) {
        add(PaletteCommand(
            id = "switch.news",
            label = "Открыть новости",
            icon = Icons.Outlined.Newspaper,
            shortcut = "⌘2",
            keywords = listOf("news", "лента", "feed"),
            action = onSwitchToNews,
        ))
    }
    add(PaletteCommand(
        id = "sidebar.toggle",
        label = if (isCollapsed) "Раскрыть панель" else "Скрыть панель",
        icon = Icons.Outlined.Menu,
        shortcut = "⌘\\",
        keywords = listOf("panel", "sidebar"),
        action = onToggleSidebar,
    ))
    add(PaletteCommand(
        id = "menu.toggle",
        label = if (menuOpen) "Закрыть меню" else "Открыть меню",
        icon = Icons.Outlined.Menu,
        keywords = listOf("menu", "settings", "настройки"),
        action = onToggleMenu,
    ))
    add(PaletteCommand(
        id = "search",
        label = "Поиск (скоро)",
        icon = Icons.Outlined.Search,
        keywords = listOf("search", "поиск", "find"),
        action = {},  // stub — будет интегрировано со search-bar в сайдбаре
    ))
    add(PaletteCommand(
        id = "logout",
        label = "Выйти",
        icon = Icons.AutoMirrored.Outlined.Logout,
        keywords = listOf("logout", "выход", "sign out"),
        action = onLogout,
    ))
}

/**
 * Верхняя полоска панели. Два режима:
 *   • Нормальный: [Collapse] — — — [Search-button]
 *   • Active-search: [Collapse] [inline TextField] [Close-X]
 *
 * §TZ-DESKTOP 0.3.3 — search-input теперь INLINE в том же 36dp ряду что
 * collapse-кнопка (фидбэк "в одну строку напротив"). Auto-focus на TextField
 * когда searchActive становится true.
 */
@Composable
private fun PanelToggleBar(
    onCollapse: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOP_CONTROL_HEIGHT)
                .background(BgCard)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SidebarToggleButton(
                icon = Icons.AutoMirrored.Outlined.MenuOpen,
                contentDescription = "Скрыть панель (⌘\\)",
                onClick = onCollapse,
            )
            Spacer(Modifier.weight(1f))
            SidebarToggleButton(
                icon = Icons.Outlined.Search,
                contentDescription = "Поиск (⌘F)",
                onClick = onOpenSearch,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(BorderDivider),
        )
    }
}

/**
 * Свёрнутая панель (COLLAPSED_WIDTH=64dp): кнопка Menu (expand) + search-icon
 * сверху, CompactTabRail снизу. Даёт возможность открыть меню, запустить
 * поиск, переключать вкладки — не раскрывая панель.
 *
 * §TZ-DESKTOP 0.3.2 — добавлена search-иконка рядом с expand-кнопкой.
 * Клик: раскрывает панель + открывает SearchBar (делает это вызывающая
 * сторона через onSearchClick).
 */
@Composable
private fun CollapsedStrip(
    activeTab: Tab,
    menuOpen: Boolean,
    newsUnread: Int,
    chatsUnread: Int,
    onExpand: () -> Unit,
    onMenuClick: () -> Unit,
    onTabSelected: (Tab) -> Unit,
    onSearchClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgCard)
            .border(0.5.dp, BorderDivider),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // §TZ-DESKTOP 0.3.3 — по фидбэку кнопки теперь в РЯД (не стопкой):
        // [Menu] [Search]. В 64dp panel'и помещается: 2×28dp + 4dp gap = 60dp,
        // +2dp inner padding по краям. Compact и симметрично.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOP_CONTROL_HEIGHT)
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SidebarToggleButton(
                icon = Icons.Outlined.Menu,
                contentDescription = "Раскрыть панель",
                onClick = onExpand,
            )
            SidebarToggleButton(
                icon = Icons.Outlined.Search,
                contentDescription = "Поиск (⌘F)",
                onClick = onSearchClick,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(BorderDivider),
        )
        Spacer(Modifier.weight(1f))
        CompactTabRail(
            activeTab = activeTab,
            menuOpen = menuOpen,
            newsUnread = newsUnread,
            chatsUnread = chatsUnread,
            onMenuClick = onMenuClick,
            onTabSelected = onTabSelected,
        )
    }
}

/**
 * Квадратная кнопка с hover-подсветкой фона (Claude-style):
 *   — обычное состояние: прозрачный фон
 *   — hover: BgElevated + tint TextPrimary
 *   — shape 8dp, tap-target 28×28
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SidebarToggleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    accentActive: Boolean = false,
) {
    var hovered by remember { mutableStateOf(false) }
    // §TZ-DESKTOP 0.3.2 — accentActive: когда true, кнопка подсвечена (fill +
    // Accent tint). Используется для search-кнопки чтобы показать что поиск
    // открыт.
    val bg by animateColorAsState(
        targetValue = when {
            accentActive -> com.example.otlhelper.desktop.theme.AccentSubtle
            hovered -> BgElevated
            else -> Color.Transparent
        },
        label = "toggleBg",
    )
    val tint by animateColorAsState(
        targetValue = when {
            accentActive -> com.example.otlhelper.desktop.theme.Accent
            hovered -> TextPrimary
            else -> TextSecondary
        },
        label = "toggleTint",
    )

    Tooltip(text = contentDescription) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)))
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
