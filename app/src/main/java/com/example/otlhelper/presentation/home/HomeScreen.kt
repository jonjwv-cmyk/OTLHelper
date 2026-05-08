package com.example.otlhelper.presentation.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AccentSubtle
import com.example.otlhelper.core.theme.BgApp
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.theme.UnreadGreen
import com.example.otlhelper.core.ui.BottomTabBar
import com.example.otlhelper.presentation.home.components.BlockOverlay
import com.example.otlhelper.presentation.home.components.SplashOverlay
import com.example.otlhelper.presentation.home.internal.HomeInputBarWiring
import com.example.otlhelper.presentation.home.internal.HomeScrollRestoration
import com.example.otlhelper.presentation.home.internal.HomeTabHost
import com.example.otlhelper.presentation.home.internal.scrollToBottom
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun HomeScreen(
    onNavigateToLogin: () -> Unit,
    onNavigateToChangePassword: () -> Unit,
    onThemeChange: (com.example.otlhelper.core.theme.ThemeMode) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // List states — initial scroll positions read from prefs, so on cold start
    // the list inflates at the exact saved offset (no visible jump-then-scroll).
    val (newsInitIdx, newsInitOff) = remember { viewModel.loadScrollPosition(HomeTab.NEWS) ?: (0 to 0) }
    val (monInitIdx, monInitOff) = remember { viewModel.loadScrollPosition(HomeTab.MONITORING) ?: (0 to 0) }
    val newsListState = rememberLazyListState(
        initialFirstVisibleItemIndex = newsInitIdx,
        initialFirstVisibleItemScrollOffset = newsInitOff
    )
    val monitoringListState = rememberLazyListState(
        initialFirstVisibleItemIndex = monInitIdx,
        initialFirstVisibleItemScrollOffset = monInitOff
    )
    val conversationListState = rememberLazyListState()
    val searchListState = rememberLazyListState()

    // All dialog visibility + target-bound state lives in a single @Stable holder.
    // See HomeDialogsHost.kt for the field list and rendering wiring.
    val dialogs = rememberHomeDialogsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Search warehouse pill expanded state — hoisted here to survive tab switches.
    var searchPillExpanded by remember { mutableStateOf(false) }

    // Reply draft — message being replied to (null = no reply in progress).
    // Kept as explicit MutableState so ChatMessageActionPopup (in HomeDialogsHost)
    // can write back from outside HomeScreen.
    val replyTargetState = remember { mutableStateOf<JSONObject?>(null) }

    // §TZ-2.3.6 — in-app подтверждение звонка. После «Да» запрашиваем
    // CALL_PHONE и запускаем ACTION_CALL; дальше системный dialer берёт
    // управление (SIM picker, сам звонок, громкая связь, завершение).
    var pendingCall by remember {
        mutableStateOf<com.example.otlhelper.core.phone.PhoneCallRequest?>(null)
    }
    val callPermissionsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        val req = pendingCall
        pendingCall = null
        if (granted && req != null) {
            viewModel.callStateManager.startCall(req.number)
        } else if (!granted) {
            viewModel.setStatus("Для звонка нужно разрешение «Телефон»")
        }
    }
    val onPhoneCallRequested: (com.example.otlhelper.core.phone.PhoneCallRequest) -> Unit = { req ->
        pendingCall = req
    }

    // §TZ-2.3.5+ — inputText с per-tab initial. `remember(activeTab)` пересчитывает
    // initial value синхронно при смене tab: InputBar на ПЕРВОМ же recompose
    // нового tab'а получает уже правильный text → active флаг стабилен → нет
    // мигания жёлтой Send-кнопки.
    val inputTextState = remember(state.activeTab) {
        mutableStateOf(
            when (state.activeTab) {
                HomeTab.SEARCH -> state.searchQuery
                else -> state.inputDrafts[state.activeTab] ?: ""
            }
        )
    }

    // Init on first composition
    LaunchedEffect(Unit) {
        viewModel.init()
    }

    // Defensive: при переходе в SEARCH — аттачи из чат-черновика туда не
    // нужны (InputBar на Search не показывается; старые аттачи могли
    // остаться от предыдущей сессии).
    LaunchedEffect(state.activeTab) {
        if (state.activeTab == HomeTab.SEARCH && state.pendingAttachments.isNotEmpty()) {
            viewModel.clearAttachments()
        }
    }

    // §TZ-CLEANUP-2026-04-26 — scroll persistence + restoration + auto-scroll
    // живут в отдельном composable. Раньше тут было 4 inline LaunchedEffect.
    HomeScrollRestoration(
        state = state,
        viewModel = viewModel,
        newsListState = newsListState,
        monitoringListState = monitoringListState,
        conversationListState = conversationListState,
    )

    // Smart back-stack: pop overlay layers first (account screen, conversation),
    // then rewind through tab history (e.g. NEWS → MONITORING → back returns to NEWS),
    // then fall through to the system handler (which exits the app).
    BackHandler {
        when {
            state.accountScreenOpen -> viewModel.closeAccountScreen()
            state.adminConversationOpen -> viewModel.closeAdminConversation()
            else -> {
                val prev = viewModel.popPreviousTab()
                if (prev != null && prev != state.activeTab) {
                    viewModel.switchTab(prev, recordHistory = false)
                } else if (state.activeTab != HomeTab.SEARCH) {
                    // Fallback: no history — go to default tab
                    viewModel.switchTab(HomeTab.SEARCH, recordHistory = false)
                }
                // else: at SEARCH with empty history → let system handle (exit)
            }
        }
    }

    // Glass-frost: blur the underlying app surface when any modal sheet/dialog
    // is open. Real BlurEffect requires API 31+; on older devices it's a no-op,
    // and the M3 ModalBottomSheet's built-in scrim still provides separation.
    val anyOverlayOpen = dialogs.showActionMenu || dialogs.showPollBuilder || dialogs.showSystemControl ||
        dialogs.showUserManagement || dialogs.showAttachPicker || dialogs.showSettings ||
        dialogs.showChangePasswordSheet ||
        (dialogs.pollStatsTarget != null) || (dialogs.newsReadersTarget != null) ||
        (dialogs.confirmVoteTarget != null) || dialogs.showLogoutConfirm || state.accountScreenOpen
    val backdropBlur by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (anyOverlayOpen) 14.dp else 0.dp,
        animationSpec = com.example.otlhelper.core.ui.animations.AppMotion.SpringStandardDp,
        label = "backdrop_blur"
    )

    // Gate every feed video under this Box. While splash OR block overlay
    // is drawn on top, the feed is still composed below — otherwise ExoPlayer
    // keeps playing with sound under the covered UI.
    val videoGateAllowed = !state.splashVisible && !state.blockOverlayVisible
    androidx.compose.runtime.CompositionLocalProvider(
        com.example.otlhelper.core.ui.LocalVideoPlaybackGate provides videoGateAllowed,
        com.example.otlhelper.core.phone.LocalPhoneCallHandler provides onPhoneCallRequested,
        com.example.otlhelper.core.feedback.LocalFeedback provides viewModel.feedbackService,
    ) {
        Box(modifier = Modifier.fillMaxSize().background(BgApp)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
                    .then(if (backdropBlur.value > 0.5f) Modifier.blur(backdropBlur) else Modifier)
            ) {
                // Status subtitle
                AnimatedVisibility(visible = state.statusMessage.isNotBlank()) {
                    Text(
                        state.statusMessage,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().background(BgCard).padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                // Soft-update banner — tap opens in-app download+install dialog; × dismisses.
                AnimatedVisibility(
                    visible = state.softUpdateAvailable && state.softUpdateUrl.isNotBlank(),
                    enter = com.example.otlhelper.core.ui.animations.AppMotion.SlideInFromTop,
                    exit = com.example.otlhelper.core.ui.animations.AppMotion.SlideOutToTop
                ) {
                    val apkCached = remember(state.softUpdateVersion) {
                        com.example.otlhelper.core.update.AppUpdate.isApkReadyFor(
                            context, state.softUpdateVersion
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AccentSubtle)
                            .clickable { dialogs.showSoftUpdateDialog = true }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Доступно обновление" +
                                    if (state.softUpdateVersion.isNotBlank()) " v${state.softUpdateVersion}"
                                    else "",
                                color = Accent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Text(
                                if (apkCached) "Файл загружен — нажмите, чтобы установить"
                                else "Нажмите, чтобы скачать и установить",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                        IconButton(
                            onClick = { viewModel.dismissSoftUpdate() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Скрыть",
                                tint = TextTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Main content. SaveableStateHolder keyed by tab — preserves
                // rememberSaveable state inside each tab across tab-switches.
                val savedStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                Box(modifier = Modifier.weight(1f)) {
                    savedStateHolder.SaveableStateProvider(state.activeTab.name) {
                        HomeTabHost(
                            state = state,
                            viewModel = viewModel,
                            dialogs = dialogs,
                            newsListState = newsListState,
                            monitoringListState = monitoringListState,
                            conversationListState = conversationListState,
                            searchListState = searchListState,
                            searchPillExpanded = searchPillExpanded,
                            onSearchPillToggle = { searchPillExpanded = !searchPillExpanded },
                            onSearchPillReset = { searchPillExpanded = false },
                        )
                    }

                    // Scroll-down FAB — ТОЛЬКО на NEWS tab. Активно short-circuit'им
                    // derivedStateOf по activeTab: без этого вычисление layoutInfo
                    // продолжает триггериться когда tab неактивен и AnimatedVisibility
                    // видит устаревший ghost-FAB в layout-tree соседнего tab'а.
                    val isNewsTab = state.activeTab == HomeTab.NEWS && !state.accountScreenOpen
                    val showScrollDown by remember(state.feedItems.size, isNewsTab) {
                        derivedStateOf {
                            if (!isNewsTab) return@derivedStateOf false
                            val total = newsListState.layoutInfo.totalItemsCount
                            if (total == 0) return@derivedStateOf false
                            val lastVisible = newsListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                            lastVisible < total - 2
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isNewsTab && state.feedItems.isNotEmpty() && showScrollDown,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                        enter = com.example.otlhelper.core.ui.animations.AppMotion.ScaleInSheet,
                        exit = com.example.otlhelper.core.ui.animations.AppMotion.ScaleOutSheet
                    ) {
                        val tint = if (state.newsUnreadCount > 0) UnreadGreen else TextSecondary
                        val (scrollDownSrc, scrollDownMod) = com.example.otlhelper.core.ui.components.rememberAppPressFeel()
                        SmallFloatingActionButton(
                            onClick = {
                                coroutineScope.launch { newsListState.scrollToBottom() }
                            },
                            containerColor = BgCard,
                            contentColor = tint,
                            shape = CircleShape,
                            interactionSource = scrollDownSrc,
                            modifier = scrollDownMod
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, null)
                        }
                    }

                    // Chat scroll-to-bottom FAB — visible on MONITORING tab when viewing
                    // actual messages (user chat OR admin conversation), not on the inbox
                    // list.
                    val isUserChatView = state.activeTab == HomeTab.MONITORING &&
                        !viewModel.hasAdminAccess() && !state.accountScreenOpen
                    val isAdminConversationView = state.activeTab == HomeTab.MONITORING &&
                        viewModel.hasAdminAccess() && state.adminConversationOpen &&
                        !state.accountScreenOpen
                    val chatListState = when {
                        isAdminConversationView -> conversationListState
                        isUserChatView -> monitoringListState
                        else -> null
                    }
                    val showChatScrollDown by remember(state.feedItems.size, chatListState, state.activeTab) {
                        derivedStateOf {
                            if (state.activeTab != HomeTab.MONITORING) return@derivedStateOf false
                            val ls = chatListState ?: return@derivedStateOf false
                            val total = ls.layoutInfo.totalItemsCount
                            if (total == 0) return@derivedStateOf false
                            val lastVisible = ls.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                            lastVisible < total - 2
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = chatListState != null && state.feedItems.isNotEmpty() && showChatScrollDown,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                        enter = com.example.otlhelper.core.ui.animations.AppMotion.ScaleInSheet,
                        exit = com.example.otlhelper.core.ui.animations.AppMotion.ScaleOutSheet
                    ) {
                        val (chatScrollSrc, chatScrollMod) = com.example.otlhelper.core.ui.components.rememberAppPressFeel()
                        SmallFloatingActionButton(
                            onClick = {
                                val ls = chatListState ?: return@SmallFloatingActionButton
                                coroutineScope.launch { ls.scrollToBottom() }
                            },
                            containerColor = BgCard,
                            contentColor = TextSecondary,
                            shape = CircleShape,
                            interactionSource = chatScrollSrc,
                            modifier = chatScrollMod
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, null)
                        }
                    }
                }

                // §TZ-CLEANUP-2026-04-26 — InputBar wiring (config + reply preview +
                // composer + onSend ветвление по tab) живёт в отдельном composable.
                HomeInputBarWiring(
                    state = state,
                    viewModel = viewModel,
                    dialogs = dialogs,
                    inputTextState = inputTextState,
                    replyTargetState = replyTargetState,
                )

                // Bottom tab bar — 4 items: Menu + News + Search + Monitoring.
                // §TZ-2.3.6 — при смене таба тактильный тик (если включён в Settings).
                val hostView = androidx.compose.ui.platform.LocalView.current
                BottomTabBar(
                    activeTab = state.activeTab,
                    newsUnreadCount = state.newsUnreadCount,
                    monitoringUnreadCount = state.monitoringUnreadCount,
                    monitoringTabLabel = viewModel.monitoringTabLabel(),
                    // §TZ-2.3.38 — для роли `client` скрываем вкладку Новости.
                    showNewsTab = com.example.otlhelper.domain.permissions.Permissions
                        .canViewNews(viewModel.role),
                    onMenuClick = {
                        viewModel.feedbackService.tap(hostView)
                        dialogs.showActionMenu = true
                    },
                    onTabSelected = { tab ->
                        // §TZ-2.3.22 — tap (22мс/amp220) вместо tick — tab-switch
                        // это сознательное действие, юзер ждёт ощутимого отклика.
                        if (tab != state.activeTab) {
                            viewModel.feedbackService.tap(hostView)
                        }
                        viewModel.switchTab(tab)
                    }
                )
            }

            // Splash overlay — premium fade-out via spring-based scale + fade
            AnimatedVisibility(
                visible = state.splashVisible,
                enter = fadeIn(animationSpec = com.example.otlhelper.core.ui.animations.AppMotion.SpringStandard),
                exit = androidx.compose.animation.scaleOut(
                    animationSpec = com.example.otlhelper.core.ui.animations.AppMotion.SpringStandard,
                    targetScale = 1.05f
                ) + fadeOut(animationSpec = com.example.otlhelper.core.ui.animations.AppMotion.SpringStandard)
            ) {
                SplashOverlay(status = state.splashStatus)
            }

            // Block overlay
            AnimatedVisibility(
                visible = state.blockOverlayVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                BlockOverlay(
                    title = state.blockTitle,
                    message = state.blockMessage,
                    updateUrl = state.updateUrl,
                    updateVersion = state.blockUpdateVersion,
                )
            }
        }

        // §TZ-2.3.6 — CallConfirmDialog. Показывается когда юзер тапнул номер.
        // На «Да» → проверяем permissions, запрашиваем недостающие, при grant
        // запускаем CallStateManager.startCall().
        pendingCall?.let { req ->
            val hostViewForCall = androidx.compose.ui.platform.LocalView.current
            com.example.otlhelper.presentation.home.components.CallConfirmDialog(
                rawPhone = req.number,
                contactName = req.contactName,
                onCancel = {
                    viewModel.feedbackService.tick(hostViewForCall)
                    pendingCall = null
                },
                onConfirm = {
                    viewModel.feedbackService.confirm(hostViewForCall)
                    val missing = viewModel.callStateManager.missingPermissions()
                    if (missing.isEmpty()) {
                        viewModel.callStateManager.startCall(req.number)
                        pendingCall = null
                    } else {
                        callPermissionsLauncher.launch(missing.toTypedArray())
                    }
                },
            )
        }

        // §TZ-2.3.22 — HomeDialogsHost и Snackbar ВНУТРИ CompositionLocalProvider,
        // иначе LocalFeedback.current = null в диалогах → tap()/tick() становятся
        // null-safe no-op. Вибрации не было несмотря на корректный код в callers.
        HomeDialogsHost(
            dialogs = dialogs,
            viewModel = viewModel,
            state = state,
            snackbarHostState = snackbarHostState,
            inputTextState = inputTextState,
            replyTarget = replyTargetState,
            onNavigateToLogin = onNavigateToLogin,
            onThemeChange = onThemeChange,
        )

        // Snackbar host — overlay поверх Box с контентом
        Box(modifier = Modifier.fillMaxSize()) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
            )
        }
    } // end CompositionLocalProvider(LocalVideoPlaybackGate / LocalFeedback)
}
