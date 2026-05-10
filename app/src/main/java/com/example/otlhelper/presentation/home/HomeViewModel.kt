package com.example.otlhelper.presentation.home

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otlhelper.ApiClient
import com.example.otlhelper.SessionManager
import com.example.otlhelper.core.push.PushEvent
import com.example.otlhelper.core.push.PushEventBus
import com.example.otlhelper.core.security.BiometricLockManager
import com.example.otlhelper.core.telemetry.Telemetry
import com.example.otlhelper.data.network.WsClient
import com.example.otlhelper.data.pending.PendingAction
import com.example.otlhelper.data.pending.PendingActionFlusher
import com.example.otlhelper.data.repository.FeedRepository
import com.example.otlhelper.data.repository.MolRepository
import com.example.otlhelper.data.sync.BaseSyncManager
import com.example.otlhelper.domain.features.Features
import com.example.otlhelper.domain.limits.Limits
import com.example.otlhelper.domain.model.Role
import com.example.otlhelper.domain.model.displayName
import com.example.otlhelper.domain.permissions.Permissions
import com.example.otlhelper.domain.policy.MonitoringTabPolicy
import com.example.otlhelper.presentation.home.internal.AdminController
import com.example.otlhelper.presentation.home.internal.AppController
import com.example.otlhelper.presentation.home.internal.ChatController
import com.example.otlhelper.presentation.home.internal.FeedController
import com.example.otlhelper.presentation.home.internal.SearchController
import com.example.otlhelper.presentation.home.internal.toJsonList
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Facade that assembles [HomeScreen] state from five domain controllers:
 *  - [ChatController]    — admin inbox, conversations, user chat, send, typing, mark-read
 *  - [FeedController]    — news, polls, reactions, pin, edit/delete, scheduled posts
 *  - [SearchController]  — МОЛ search, pinned warehouse, BaseSync orchestration
 *  - [AppController]     — app_status gate, avatar, heartbeat, lifecycle
 *  - [AdminController]   — users management (CRUD/role/toggle/reset) + system pause
 *
 * HomeViewModel itself keeps only cross-cutting responsibilities:
 *  - tab switching + back-stack history
 *  - drafts / scroll / activeTab persistence (SavedStateHandle + SharedPrefs)
 *  - attachments (shared between NEWS sendNews and MONITORING sendMessage)
 *  - master init() that wires together the splash fast-path, network refresh,
 *    push listener, auto-refresh tick, pending-queue flush, heartbeat
 *
 * Public API is preserved exactly — HomeScreen and dialogs were not touched.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val session: SessionManager,
    private val molRepository: MolRepository,
    private val feedRepository: FeedRepository,
    private val pendingActionFlusher: PendingActionFlusher,
    private val telemetry: Telemetry,
    val wsClient: WsClient,
    val biometricLockManager: BiometricLockManager,
    val appSettings: com.example.otlhelper.core.settings.AppSettings,
    private val pushEventBus: PushEventBus,
    private val savedStateHandle: SavedStateHandle,
    val baseSyncManager: BaseSyncManager,
    val callStateManager: com.example.otlhelper.core.phone.CallStateManager,
    val feedbackService: com.example.otlhelper.core.feedback.FeedbackService,
    // §TZ-2.3.41 — для п.5 (push после logout): деактивировать FCM-токен
    // устройства на сервере ДО очистки auth. См. logout() ниже.
    private val pushTokenManager: com.example.otlhelper.core.push.PushTokenManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // ── Persisted state (SavedStateHandle + SharedPreferences) ───────────────
    private val persistPrefs by lazy {
        context.getSharedPreferences("home_state", Context.MODE_PRIVATE)
    }

    private val _uiState = MutableStateFlow(loadPersistedInitialState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private fun loadPersistedInitialState(): HomeUiState {
        // §TZ-2.3.38 — для роли `client` нет вкладки «Новости», дефолт — Search.
        val defaultTab = if (com.example.otlhelper.domain.permissions.Permissions
                .canViewNews(session.getRoleEnum())) HomeTab.NEWS else HomeTab.SEARCH
        val tabName = savedStateHandle.get<String>(KEY_ACTIVE_TAB)
            ?: persistPrefs.getString(KEY_ACTIVE_TAB, null)
            ?: defaultTab.name
        val tab = runCatching { HomeTab.valueOf(tabName) }.getOrDefault(defaultTab)
            .let { candidate -> if (candidate == HomeTab.NEWS && !com.example.otlhelper.domain.permissions.Permissions
                    .canViewNews(session.getRoleEnum())) defaultTab else candidate }

        val searchQuery = savedStateHandle.get<String>(KEY_SEARCH_QUERY)
            ?: persistPrefs.getString(KEY_SEARCH_QUERY, "") ?: ""

        val draftsCsv = persistPrefs.getString(KEY_DRAFTS, "") ?: ""
        val drafts = if (draftsCsv.isBlank()) emptyMap() else
            draftsCsv.split("§").mapNotNull {
                val parts = it.split("\u0001")
                if (parts.size == 2) {
                    runCatching { HomeTab.valueOf(parts[0]) to parts[1] }.getOrNull()
                } else null
            }.toMap()

        // Avatar: prefer the explicitly stored URL (set after a successful
        // upload), otherwise fall back to the server's public convention URL.
        // The convention URL returns 404 for users who haven't uploaded an
        // avatar — Coil handles that gracefully and the UI shows initials.
        val login = session.getLogin()
        val avatar = session.getAvatarUrl().ifBlank {
            if (login.isNotBlank()) ApiClient.avatarUrlFor(login) else ""
        }

        // Hydrate users list from local cache so UserManagement dialog shows
        // the last-known list the very first time it's opened after a cold
        // launch, instead of flashing "Нет пользователей" while the network
        // fetch resolves. Background loadUsersList() overwrites with fresh
        // data as soon as it arrives.
        val cachedUsersJson = appSettings.cachedUsersListJson
        val cachedUsers: List<JSONObject> = if (cachedUsersJson.isNotBlank()) {
            runCatching {
                val arr = JSONArray(cachedUsersJson)
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            }.getOrDefault(emptyList())
        } else emptyList()

        return HomeUiState(
            activeTab = tab,
            searchQuery = if (tab == HomeTab.SEARCH) searchQuery else "",
            inputDrafts = drafts,
            avatarUrl = avatar,
            usersList = cachedUsers,
        )
    }

    private fun persistTab(tab: HomeTab) {
        savedStateHandle[KEY_ACTIVE_TAB] = tab.name
        persistPrefs.edit().putString(KEY_ACTIVE_TAB, tab.name).apply()
    }

    private fun persistSearchQuery(q: String) {
        savedStateHandle[KEY_SEARCH_QUERY] = q
        persistPrefs.edit().putString(KEY_SEARCH_QUERY, q).apply()
    }

    private fun persistDrafts(drafts: Map<HomeTab, String>) {
        val csv = drafts.entries.joinToString("§") { "${it.key.name}\u0001${it.value}" }
        persistPrefs.edit().putString(KEY_DRAFTS, csv).apply()
    }

    companion object {
        private val INCOMING_CONTENT_PUSH_TYPES = setOf(
            "admin_message", "user_message", "message",
            "news", "news_poll",
        )
        private const val KEY_ACTIVE_TAB = "home.active_tab"
        private const val KEY_SEARCH_QUERY = "home.search_query"
        private const val KEY_DRAFTS = "home.drafts"
        private const val KEY_SCROLL_NEWS = "home.scroll.news"
        private const val KEY_SCROLL_MONITORING = "home.scroll.monitoring"
    }

    fun saveScrollPosition(tab: HomeTab, index: Int, offset: Int) {
        val key = scrollKey(tab) ?: return
        val packed = "$index:$offset"
        savedStateHandle[key] = packed
        persistPrefs.edit().putString(key, packed).apply()
    }

    fun loadScrollPosition(tab: HomeTab): Pair<Int, Int>? {
        val key = scrollKey(tab) ?: return null
        val raw = savedStateHandle.get<String>(key)
            ?: persistPrefs.getString(key, null)
            ?: return null
        val parts = raw.split(":")
        if (parts.size != 2) return null
        val idx = parts[0].toIntOrNull() ?: return null
        val off = parts[1].toIntOrNull() ?: 0
        return idx to off
    }

    private fun scrollKey(tab: HomeTab): String? = when (tab) {
        HomeTab.NEWS -> KEY_SCROLL_NEWS
        HomeTab.MONITORING -> KEY_SCROLL_MONITORING
        HomeTab.SEARCH -> null  // search results are short-lived; don't persist
    }

    // ── Coroutine jobs ───────────────────────────────────────────────────────
    private var autoRefreshJob: Job? = null
    private var pendingFlushJob: Job? = null
    private var searchQueryPersistJob: Job? = null

    // Phase 12c: debounced draft auto-save to server (per-scope).
    private val draftSaveJobs = mutableMapOf<String, Job>()

    // ── Domain controllers ───────────────────────────────────────────────────
    private val chatController = ChatController(
        scope = viewModelScope,
        uiState = _uiState,
        session = session,
        feedRepository = feedRepository,
        wsClient = wsClient,
        currentScopeKey = ::scopeKey,
        hasAdminAccess = ::hasAdminAccess,
        onUnreadCountsChanged = { appController.loadUnreadCounts() },
    )

    private val feedController = FeedController(
        scope = viewModelScope,
        uiState = _uiState,
        session = session,
        feedRepository = feedRepository,
        context = context,
        currentScopeKey = ::scopeKey,
        onUnreadCountsChanged = { appController.loadUnreadCounts() },
        setStatus = { msg -> setStatus(msg) },
    )

    private val searchController = SearchController(
        scope = viewModelScope,
        uiState = _uiState,
        molRepository = molRepository,
        baseSyncManager = baseSyncManager,
        telemetry = telemetry,
        onSplashStatusChanged = { status -> appController.setSplashStatus(status) },
    )

    private val appController = AppController(
        scope = viewModelScope,
        uiState = _uiState,
        session = session,
        context = context,
        molRepository = molRepository,
        baseSyncManager = baseSyncManager,
        onFlushPendingQueue = { viewModelScope.launch { flushPending() } },
        onReloadActiveTab = { loadCurrentTab() },
    )

    private val adminController = AdminController(
        scope = viewModelScope,
        uiState = _uiState,
        appSettings = appSettings,
    )

    // ── Session / role helpers ───────────────────────────────────────────────
    fun getLogin(): String = session.getLogin()
    fun getRole(): String = session.getRole()
    fun getFullName(): String = session.getFullName()

    val role: Role get() = session.getRoleEnum()

    fun hasAdminAccess(): Boolean = Permissions.hasAdminAccess(role)
    fun isDeveloper(): Boolean = Permissions.isDeveloper(role)
    fun getRoleLabel(): String = role.displayName()
    fun monitoringTabLabel(): String = MonitoringTabPolicy.chatTabLabel(role)
    fun getFeatures(): Features = session.getFeatures()

    // ── Init (master orchestrator) ───────────────────────────────────────────
    fun init() {
        val deviceId = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
        ) ?: ""
        ApiClient.setAuth(session.getToken(), deviceId)

        // Phase 8: auto-connect WebSocket if the server enabled the feature.
        // Graceful: if the server didn't provide features or websocket=false →
        // don't connect, heartbeat+FCM keep working as before.
        if (session.getFeatures().webSocketEnabled) {
            wsClient.connect()
        }

        appController.setSplashVisible(true)
        appController.setSplashStatus("Запускаем...")

        viewModelScope.launch {
            // === FAST PATH — show UI instantly from local cache ===
            // This block reads Room (local, fast) and fills UiState. As soon
            // as it's ready — hide the splash. Network catches up in the
            // background. Warm-cache: ~100-200ms vs old ~2-3s.
            val cacheJob = launch { prehydrateFeedCache() }
            launch { searchController.refreshBaseMetadata() }
            cacheJob.join()
            appController.setSplashVisible(false)

            // === BACKGROUND — network requests, do not block UI ===
            // app_status with a 3s timeout: bad network must not hold the
            // user on the first tab. Cached state remains.
            launch { withTimeoutOrNull(3_000L) { appController.checkAppStatus() } }
            launch { appController.refreshOwnProfile() }
            launch { loadCurrentTab() }
            searchController.syncBaseIfNeeded()
        }

        startAutoRefresh()
        startPendingFlush()
        appController.startHeartbeat()
        startPushListener()
        appController.startAppLifecycleRefresh()
        searchController.observeBaseSyncStatus()
    }

    // ── Push event listener ──────────────────────────────────────────────────
    // Reacts to FCM arrivals in real time instead of waiting for the next
    // auto-refresh tick (8-30s). Push type → what to reload so the relevant
    // tab and unread counters update within a few hundred ms of Firebase
    // delivering the message.
    private fun startPushListener() {
        pushEventBus.events
            .onEach { event ->
                if (event !is PushEvent.Received) return@onEach
                // Always refresh unread counts — the bottom-tab glow depends
                // on them and they're cheap.
                appController.loadUnreadCounts()
                val type = event.type
                // §TZ-2.3.9 — SF-2026 sound theme: входящее сообщение / новость →
                // ping-sound (если юзер включил «Звуки интерфейса» в Settings).
                // typing_start / app_version / остальные служебные пуши — без звука.
                if (type in INCOMING_CONTENT_PUSH_TYPES) {
                    feedbackService.receive()
                }
                // §0.11.0 — presence_change targeted update. WS event приходит
                // когда юзер connect/disconnect/changes presence. Точечно
                // обновляем presence в usersList state — UserList dots/labels
                // меняются мгновенно. Также влияет на admin chat list (sender
                // presence через _uiState.adminMessages refresh ниже).
                if (type == "presence_change" || type == "presence_update") {
                    val login = event.data["login"].orEmpty()
                    val status = event.data["status"]?.ifBlank { null } ?: "online"
                    val lastSeenAt = event.data["last_seen_at"].orEmpty()
                    if (login.isNotBlank()) {
                        _uiState.update { st ->
                            val newUsers = st.usersList.map { u ->
                                if (u.optString("login") == login) {
                                    val copy = org.json.JSONObject(u.toString())
                                    copy.put("presence_status", status)
                                    if (lastSeenAt.isNotBlank()) copy.put("last_seen_at", lastSeenAt)
                                    copy
                                } else u
                            }
                            st.copy(usersList = newUsers)
                        }
                    }
                }
                // Route the push to the domain that owns it. First claim wins.
                chatController.handlePush(type, event.data) ||
                    feedController.handlePush(type) ||
                    appController.handlePush(type)
            }
            .launchIn(viewModelScope)
    }

    // ── Tab history ──────────────────────────────────────────────────────────
    // Bounded queue: keeps the last 5 tab transitions so back-press can rewind
    // through the user's actual journey (instead of always falling back to SEARCH).
    private val tabHistory = ArrayDeque<HomeTab>()
    private val tabHistoryLimit = 5

    fun popPreviousTab(): HomeTab? = if (tabHistory.isNotEmpty()) tabHistory.removeLast() else null

    private fun pushTabHistory(tab: HomeTab) {
        if (tabHistory.lastOrNull() == tab) return
        tabHistory.addLast(tab)
        while (tabHistory.size > tabHistoryLimit) tabHistory.removeFirst()
    }

    // ── Scope key ────────────────────────────────────────────────────────────
    /** Scope key for the per-scope feed cache. */
    private fun scopeKey(): String {
        val state = _uiState.value
        val tab = state.activeTab
        val adminConvOpen = state.adminConversationOpen
        val adminConvLogin = state.selectedAdminUserLogin
        return when {
            tab == HomeTab.NEWS -> "news"
            tab == HomeTab.MONITORING && adminConvOpen && !adminConvLogin.isNullOrBlank() ->
                "admin_conv:$adminConvLogin"
            tab == HomeTab.MONITORING && hasAdminAccess() -> "admin_inbox"
            tab == HomeTab.MONITORING -> "user_chat"
            else -> ""
        }
    }

    // ── Tab switching ────────────────────────────────────────────────────────
    //
    // Paspport §3.12 "all tabs preserve state across switches".
    //
    // UX contract:
    // 1. feedItems hydrate from per-scope cache instantly — no flicker.
    // 2. Scroll position is preserved (LazyListState lives in HomeScreen).
    // 3. loadCurrentTab() fires ONLY when the cache is empty (first visit).
    //    Already-visited tabs refresh in the background via push + heartbeat —
    //    matches §3.12 "tabs are live, backed by a hot StateFlow".
    fun switchTab(tab: HomeTab, recordHistory: Boolean = true) {
        val previous = _uiState.value.activeTab
        if (recordHistory && previous != tab) pushTabHistory(previous)

        // Compute the scope we're switching INTO and pull cached items for it.
        val nextScope = scopeKeyFor(tab)
        val cached = _uiState.value.feedItemsCache[nextScope].orEmpty()

        _uiState.update {
            it.copy(
                activeTab = tab,
                accountScreenOpen = false,
                adminConversationOpen = if (tab != HomeTab.MONITORING) false else it.adminConversationOpen,
                selectedAdminUserLogin = if (tab != HomeTab.MONITORING) null else it.selectedAdminUserLogin,
                selectedAdminUserName = if (tab != HomeTab.MONITORING) null else it.selectedAdminUserName,
                // Keep search results and searchQuery — user returning to SEARCH sees last hits.
                feedItems = cached,
                feedLoading = cached.isEmpty(),  // only show loader when there's nothing to show
                feedError = "",
                // Do NOT clear pinnedWarehouse — preserve the pinned bar state when
                // bouncing between tabs during an active search session.
                statusMessage = "",
            )
        }
        persistTab(tab)
        // Phase 11 telemetry: screen_open event with tab name (no user payload).
        telemetry.event("screen_open", mapOf("screen" to tab.name.lowercase()))
        // First visit (empty cache) shows skeleton; otherwise UI stays on
        // cached data while the network response silently refreshes
        // presence/badges. Without this, badges and green/yellow dots in the
        // chat list lagged and only appeared on the first auto-refresh tick (§3.7).
        loadCurrentTab()
        appController.loadUnreadCounts()
    }

    /** Precompute scope key for a given target tab (used before we flip activeTab). */
    private fun scopeKeyFor(targetTab: HomeTab): String {
        val state = _uiState.value
        return when {
            targetTab == HomeTab.NEWS -> "news"
            targetTab == HomeTab.MONITORING && state.adminConversationOpen && !state.selectedAdminUserLogin.isNullOrBlank() ->
                "admin_conv:${state.selectedAdminUserLogin}"
            targetTab == HomeTab.MONITORING && hasAdminAccess() -> "admin_inbox"
            targetTab == HomeTab.MONITORING -> "user_chat"
            else -> ""
        }
    }

    fun loadCurrentTab() {
        when (_uiState.value.activeTab) {
            HomeTab.NEWS -> feedController.loadNews()
            HomeTab.SEARCH -> { /* search triggered by user input */ }
            HomeTab.MONITORING -> {
                if (hasAdminAccess()) chatController.loadAdminMessages()
                else chatController.loadUserChat()
            }
        }
    }

    /**
     * Pre-warm the in-memory `feedItemsCache` for every tab this user can see
     * from the Room on-disk cache. Runs once at init. Without this, the first
     * switchTab into a tab you didn't open last session paints with an empty
     * list (cache map has no entry yet) and the user sees a skeleton/empty
     * flash over what should be the cached list. With it every tab switch is
     * cache-first.
     */
    private suspend fun prehydrateFeedCache() {
        val login = session.getLogin()
        // News is global; admin_inbox / user_chat depend on role.
        val scopes = buildList {
            add("news")
            if (hasAdminAccess()) add("admin_inbox:$login") else add("user_chat:$login")
        }
        val activeTab = _uiState.value.activeTab
        for (scope in scopes) {
            try {
                val cached = feedRepository.getCachedFeed(scope)
                if (cached.length() == 0) continue
                val list = cached.toJsonList()
                val cacheKey = when {
                    scope.startsWith("admin_inbox") -> "admin_inbox"
                    scope.startsWith("user_chat") -> "user_chat"
                    else -> scope
                }
                val deduped = if (cacheKey == "admin_inbox") {
                    list.distinctBy { it.optString("sender_login") }
                } else list
                // §TZ-2.3.5 final — если мы prehydrate'им scope АКТИВНОГО tab'а,
                // сразу ставим feedItems = кеш. Иначе первое рендерирование таба
                // видит пустой список (→ skeleton/empty) до того как
                // loadAdminMessages успеет async-way подставить cache. Теперь
                // tab с первого frame видит cache → без пустого кадра.
                val isCacheForActiveTab = when (activeTab) {
                    HomeTab.NEWS -> cacheKey == "news"
                    HomeTab.MONITORING -> cacheKey == "admin_inbox" || cacheKey == "user_chat"
                    HomeTab.SEARCH -> false
                }
                _uiState.update {
                    it.copy(
                        feedItemsCache = it.feedItemsCache + (cacheKey to deduped),
                        feedItems = if (isCacheForActiveTab && it.feedItems.isEmpty()) deduped else it.feedItems,
                        feedFromCache = if (isCacheForActiveTab) true else it.feedFromCache,
                    )
                }
            } catch (_: Exception) { /* ignore; loadXxx will repopulate */ }
        }
    }

    // ── Drafts ───────────────────────────────────────────────────────────────
    /** Save a draft for a tab (called from UI when input text changes in NEWS/MONITORING). */
    fun saveDraft(tab: HomeTab, text: String) {
        _uiState.update {
            val newDrafts = it.inputDrafts.toMutableMap().apply {
                if (text.isBlank()) remove(tab) else put(tab, text)
            }
            it.copy(inputDrafts = newDrafts)
        }
        persistDrafts(_uiState.value.inputDrafts)

        // Phase 12c: server-side draft sync with debounce (Limits.DRAFT_AUTOSAVE_MS).
        // Scope string follows the server convention: 'news' / 'chat:peer' / etc.
        val scope = scopeForDraft(tab)
        if (scope.isBlank()) return
        draftSaveJobs[scope]?.cancel()
        draftSaveJobs[scope] = viewModelScope.launch {
            delay(Limits.DRAFT_AUTOSAVE_MS)
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ApiClient.saveDraft(scope, text)
                }
            } catch (_: Exception) { /* offline — local draft survives */ }
        }
    }

    /** Map the UI tab to the server-side draft scope string (§3.15.a.А). */
    private fun scopeForDraft(tab: HomeTab): String = when (tab) {
        HomeTab.NEWS -> if (hasAdminAccess()) "news" else ""
        HomeTab.MONITORING -> {
            val peer = _uiState.value.selectedAdminUserLogin.orEmpty()
            if (hasAdminAccess() && peer.isNotBlank()) "chat:$peer" else "chat"
        }
        else -> ""
    }

    // ── Attachments ──────────────────────────────────────────────────────────
    // Business rules:
    //   • max 5 attachments total per news/poll/message
    //   • among media: max 1 video + 1 GIF + 1 image
    //   • any number of "document" files (pdf, doc, apk, …) within the 5 total
    //
    // When the user tries to add something that would break a rule, we do NOT
    // add the item and instead surface a status message so they understand why.
    private fun categorize(mime: String, name: String): String {
        val m = mime.lowercase()
        val n = name.lowercase()
        return when {
            m.contains("gif") || n.endsWith(".gif") -> "gif"
            m.startsWith("image") -> "image"
            m.startsWith("video") -> "video"
            else -> "file"
        }
    }

    fun addAttachment(item: AttachmentItem) {
        val current = _uiState.value.pendingAttachments
        if (current.size >= 5) {
            _uiState.update { it.copy(statusMessage = "Можно прикрепить не более 5 файлов") }
            return
        }
        val newCat = categorize(item.mimeType, item.fileName)
        if (newCat in setOf("video", "gif", "image")) {
            val alreadyOfKind = current.any { categorize(it.mimeType, it.fileName) == newCat }
            if (alreadyOfKind) {
                val label = when (newCat) {
                    "video" -> "видео"
                    "gif" -> "GIF"
                    else -> "изображение"
                }
                _uiState.update {
                    it.copy(statusMessage = "Уже прикреплено $label — удалите его, чтобы добавить новое")
                }
                return
            }
        }
        _uiState.update { it.copy(pendingAttachments = current + item, statusMessage = "") }
    }

    fun removeAttachment(item: AttachmentItem) {
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments - item) }
    }

    fun clearAttachments() {
        _uiState.update { it.copy(pendingAttachments = emptyList()) }
    }

    // ── Status / account screen / splash ─────────────────────────────────────
    fun setStatus(msg: String) = appController.setStatus(msg)
    fun openAccountScreen() = appController.openAccountScreen()
    fun closeAccountScreen() = appController.closeAccountScreen()
    fun dismissSoftUpdate() = appController.dismissSoftUpdate()

    // ── Search delegates ─────────────────────────────────────────────────────
    fun onSearchQueryChanged(query: String) {
        searchController.onSearchQueryChanged(query)
        // Persist search query from HomeViewModel (the place that owns prefs).
        searchQueryPersistJob?.cancel()
        persistSearchQuery(query)
    }

    fun togglePinnedWarehouseExpanded() = searchController.togglePinnedWarehouseExpanded()
    fun triggerBaseSyncManual() = searchController.triggerBaseSyncManual()

    // ── Chat delegates ───────────────────────────────────────────────────────
    fun loadUserChat(forceNetwork: Boolean = false) = chatController.loadUserChat(forceNetwork)
    fun loadAdminMessages(forceNetwork: Boolean = false) = chatController.loadAdminMessages(forceNetwork)
    fun loadAdminConversation(forceNetwork: Boolean = false) = chatController.loadAdminConversation(forceNetwork)
    fun openAdminConversation(userLogin: String, userName: String) =
        chatController.openAdminConversation(userLogin, userName)
    fun closeAdminConversation() = chatController.closeAdminConversation()

    fun sendMessage(
        text: String,
        receiverLogin: String? = null,
        attachments: JSONArray = JSONArray(),
        replyToId: Long = 0L,
        onResult: (Boolean, String) -> Unit,
    ) = chatController.sendMessage(text, receiverLogin, attachments, replyToId, onResult)

    fun markMessageRead(id: Long) = chatController.markMessageRead(id)
    /** §TZ-2.3.38 — для новостей/опросов; безусловный decrement бейджа. */
    fun markNewsRead(id: Long) = chatController.markNewsRead(id)
    fun sendTypingStart(peerLogin: String) = chatController.sendTypingStart(peerLogin)
    fun sendTypingStop(peerLogin: String) = chatController.sendTypingStop(peerLogin)

    // ── Feed delegates ───────────────────────────────────────────────────────
    fun loadNews(forceNetwork: Boolean = false) = feedController.loadNews(forceNetwork)

    fun sendNews(text: String, attachments: JSONArray = JSONArray(), onResult: (Boolean, String) -> Unit) =
        feedController.sendNews(text, attachments, onResult)

    fun votePoll(pollId: Long, optionIds: List<Long>, onResult: (Boolean, String) -> Unit) =
        feedController.votePoll(pollId, optionIds, onResult)

    fun createPoll(description: String, options: List<String>, attachments: JSONArray = JSONArray(), onResult: (Boolean, String) -> Unit) =
        feedController.createPoll(description, options, attachments, onResult)

    fun editMessage(messageId: Long, newText: String, onResult: (Boolean, String) -> Unit) =
        feedController.editMessage(messageId, newText, onResult)

    fun softDeleteMessage(messageId: Long, onResult: (Boolean, String) -> Unit) =
        feedController.softDeleteMessage(messageId, onResult)

    fun undeleteMessage(messageId: Long, onResult: (Boolean, String) -> Unit) =
        feedController.undeleteMessage(messageId, onResult)

    fun togglePin(messageId: Long, pin: Boolean, onResult: (Boolean, String) -> Unit) =
        feedController.togglePin(messageId, pin, onResult)

    fun toggleReaction(messageId: Long, emoji: String, alreadyReacted: Boolean) =
        feedController.toggleReaction(messageId, emoji, alreadyReacted)

    suspend fun loadReactions(messageId: Long): JSONObject? = feedController.loadReactions(messageId)

    fun getNewsReaders(messageId: Long, onResult: (JSONObject?) -> Unit) =
        feedController.getNewsReaders(messageId, onResult)

    fun getPollStats(pollId: Long, onResult: (JSONObject?) -> Unit) =
        feedController.getPollStats(pollId, onResult)

    fun schedulePost(kind: String, text: String, sendAtUtc: String, onResult: (Boolean, String) -> Unit) =
        feedController.schedulePost(kind, text, sendAtUtc, onResult)

    // ── App delegates ────────────────────────────────────────────────────────
    fun uploadAvatar(bytes: ByteArray, mimeType: String, fileName: String) =
        appController.uploadAvatar(bytes, mimeType, fileName)

    /**
     * §TZ-2.3.41 — фикс п.5: перед вызовом серверного `logout` деактивируем
     * push_subscriptions для текущего FCM-токена. Иначе сервер продолжает
     * слать пуши на устройство после выхода (токен остаётся `is_active=1`).
     *
     * Порядок критичен: unregister_push_token требует валидного auth-токена,
     * а [AppController.logout] сразу после своего запроса `ApiClient.logout()`
     * вызывает `clearAuth()`. Поэтому unregister делаем ПЕРЕД.
     */
    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { pushTokenManager.unregisterCurrentToken() }
            appController.logout(onDone)
        }
    }

    // ── Admin delegates (users management + system state) ────────────────────
    fun getSystemState(onResult: (JSONObject?) -> Unit) = adminController.getSystemState(onResult)
    fun setAppPause(title: String, message: String, onResult: (Boolean) -> Unit) =
        adminController.setAppPause(title, message, onResult)
    fun clearAppPause(onResult: (Boolean) -> Unit) = adminController.clearAppPause(onResult)
    fun getUsers(onResult: (JSONObject?) -> Unit) = adminController.getUsers(onResult)

    // Users management — preserve all legacy signatures used by existing dialogs.
    fun loadUsersList(onResult: (List<JSONObject>) -> Unit = {}) = adminController.loadUsersList(onResult)
    fun renameUser(targetLogin: String, fullName: String, onResult: (Boolean) -> Unit) =
        adminController.renameUser(targetLogin, fullName, onResult)
    fun changeUserLogin(targetLogin: String, newLogin: String, onResult: (Boolean, String) -> Unit) =
        adminController.changeUserLogin(targetLogin, newLogin, onResult)
    fun changeUserRole(targetLogin: String, newRole: String, onResult: (Boolean) -> Unit) =
        adminController.changeUserRole(targetLogin, newRole, onResult)
    fun createUser(newLogin: String, fullName: String, password: String, role: String, mustChangePassword: Boolean, onResult: (Boolean, String) -> Unit) =
        adminController.createUser(newLogin, fullName, password, role, mustChangePassword, onResult)
    fun createUserAdmin(newLogin: String, fullName: String, password: String, role: String, mustChange: Boolean, onResult: (Boolean, String) -> Unit) =
        adminController.createUser(newLogin, fullName, password, role, mustChange, onResult)
    fun toggleUser(targetLogin: String, onResult: (Boolean, String) -> Unit) =
        adminController.toggleUser(targetLogin, onResult)
    fun toggleUserAdmin(targetLogin: String, onResult: (Boolean) -> Unit) =
        adminController.toggleUserSilent(targetLogin, onResult)
    fun resetPassword(targetLogin: String, newPassword: String, onResult: (Boolean, String) -> Unit) =
        adminController.resetPassword(targetLogin, newPassword, onResult)
    fun resetPasswordAdmin(targetLogin: String, newPassword: String, onResult: (Boolean) -> Unit) =
        adminController.resetPasswordSilent(targetLogin, newPassword, onResult)
    fun deleteUserAdmin(targetLogin: String, onResult: (Boolean) -> Unit) =
        adminController.deleteUser(targetLogin, onResult)
    // §0.11.3 — reset password login counter (developer/superadmin only).
    // Используется в UserRow menu для разблокировки юзера после превышения
    // лимита парольных входов на ПК.
    fun resetPasswordLoginCounter(targetLogin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val resp = runCatching {
                ApiClient.resetPasswordLoginCounter(targetLogin)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                onResult(resp?.optBoolean("ok", false) == true)
            }
        }
    }

    // ── Auto-refresh ─────────────────────────────────────────────────────────
    // Tight polling for the active chat view: admins watching an open
    // conversation OR a user on their chat see new messages / typing / presence
    // changes within ~5s. Other tabs keep the 30s cadence to preserve battery.
    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                val state = _uiState.value
                val isActiveChat = state.activeTab == HomeTab.MONITORING && !state.accountScreenOpen
                val interval = if (isActiveChat) 5_000L else 30_000L
                delay(interval)

                if (_uiState.value.accountScreenOpen) continue

                when (_uiState.value.activeTab) {
                    HomeTab.NEWS -> feedController.loadNews(forceNetwork = true)
                    HomeTab.MONITORING -> {
                        val s = _uiState.value
                        if (hasAdminAccess()) {
                            if (s.adminConversationOpen) chatController.loadAdminConversation(forceNetwork = true)
                            else chatController.loadAdminMessages(forceNetwork = true)
                        } else {
                            chatController.loadUserChat(forceNetwork = true)
                        }
                    }
                    HomeTab.SEARCH -> { /* no auto-refresh for search */ }
                }
                appController.loadUnreadCounts()
            }
        }
    }

    // ── Pending flush ────────────────────────────────────────────────────────
    //
    // Handles everything queued while the device was offline — messages, votes,
    // polls, news posts, read receipts. Fires on a timer AND is kicked from
    // heartbeat/onResume so the queue drains within seconds of reconnect,
    // not 60s+ later.
    private fun startPendingFlush() {
        pendingFlushJob?.cancel()
        pendingFlushJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000L)
                flushPending()
            }
        }
    }

    // Drain the offline queue. The PendingActionFlusher encapsulates the
    // per-action transport; we only decide which tab to refresh afterwards.
    private suspend fun flushPending() {
        var shouldReloadNews = false
        var shouldReloadChat = false
        val result = pendingActionFlusher.flush { action ->
            when (action) {
                is PendingAction.SendNews,
                is PendingAction.CreatePoll,
                is PendingAction.VotePoll -> shouldReloadNews = true
                is PendingAction.SendMessage -> shouldReloadChat = true
                is PendingAction.MarkRead,
                is PendingAction.LogError,
                is PendingAction.LogActivity,
                is PendingAction.Unknown -> Unit
                is PendingAction.EditMessage,
                is PendingAction.SoftDeleteMessage,
                is PendingAction.UndeleteMessage -> {
                    shouldReloadNews = true
                    shouldReloadChat = true
                }
            }
        }
        if (result.total == 0) return
        if (shouldReloadNews && _uiState.value.activeTab == HomeTab.NEWS) {
            feedController.loadNews(forceNetwork = true)
        }
        if (shouldReloadChat && _uiState.value.activeTab == HomeTab.MONITORING) {
            loadCurrentTab()
        }
        appController.loadUnreadCounts()
    }

    override fun onCleared() {
        super.onCleared()
        autoRefreshJob?.cancel()
        pendingFlushJob?.cancel()
        searchController.cancel()
        appController.cancel()
    }
}
