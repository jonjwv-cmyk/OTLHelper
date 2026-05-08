package com.example.otlhelper.presentation.home.internal

import com.example.otlhelper.ApiClient
import com.example.otlhelper.SessionManager
import com.example.otlhelper.data.network.WsClient
import com.example.otlhelper.data.repository.FeedRepository
import com.example.otlhelper.domain.limits.Limits
import com.example.otlhelper.presentation.home.HomeUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Chat domain slice of HomeViewModel.
 *
 * Owns:
 *  - admin inbox list (admin role)
 *  - admin conversation (admin ↔ one user)
 *  - user chat (user ↔ all admins)
 *  - sendMessage + optimistic pending append
 *  - mark-message-read (with offline queue fallback)
 *  - typing indicators (WS out + FCM in)
 *
 * Mutates the shared [uiState] under fields: feedItems, feedLoading,
 * feedError, feedFromCache, feedItemsCache (chat scopes), monitoringUnreadCount,
 * adminConversationOpen, selectedAdminUser*, typingFromLogins.
 */
internal class ChatController(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<HomeUiState>,
    private val session: SessionManager,
    private val feedRepository: FeedRepository,
    private val wsClient: WsClient,
    private val currentScopeKey: () -> String,
    private val hasAdminAccess: () -> Boolean,
    private val onUnreadCountsChanged: () -> Unit,
) {

    // In-memory pending IDs for mark-as-read deduplication. Multiple rapid
    // scroll-into-view events for the same message (recompose storms) must
    // not trigger multiple network calls.
    private val readInFlightIds = mutableSetOf<Long>()

    // §TZ-2.3.38 — debounced reconciliation of unread counts. Множественные
    // mark-as-read (быстрая прокрутка) не должны спамить getUnreadCounts
    // параллельно — иначе race с оптимистичными декрементами даёт визуально
    // «залипший» бейдж (последний ответ сервера со stale-значением затирает
    // правильный локальный 0). Debounce 500ms = после последнего mark'а
    // делаем ОДИН fetch, ловим актуальное значение.
    @Volatile private var unreadReconcileJob: kotlinx.coroutines.Job? = null
    private fun scheduleUnreadReconcile() {
        unreadReconcileJob?.cancel()
        unreadReconcileJob = scope.launch {
            delay(500)
            onUnreadCountsChanged()
        }
    }

    // ── Admin messages list ──────────────────────────────────────────────────
    fun loadAdminMessages(forceNetwork: Boolean = false) {
        scope.launch {
            // §TZ-2.3.5 final — Telegram-flow. Instant показ кеша если он
            // есть; сетевой ответ потом update'ит feedItems в-точке, Tab
            // автоматически пересортирует по lastMessageId DESC. Прыжка
            // «skeleton → список → один догружается» больше нет:
            //   * Кеш в feedItems → рендер мгновенно при заходе в tab.
            //   * Сеть возвращает тот же набор + свежие поля → sort тот же,
            //     LazyColumn item-skipping → нет визуального движения.
            //   * Новое сообщение от контакта → его lastMessageId поднялся →
            //     sortByDescending автоматически поднимает его наверх,
            //     placement-spring плавно переставляет.
            val inMemoryCache = uiState.value.feedItemsCache["admin_inbox"].orEmpty()
            val alreadyHasItems = uiState.value.feedItems.isNotEmpty() || inMemoryCache.isNotEmpty()
            if (!alreadyHasItems) {
                uiState.update { it.copy(feedLoading = true, feedError = "", adminConversationOpen = false) }
            } else {
                uiState.update {
                    it.copy(
                        // Подставляем кеш в feedItems моментально (если там пусто).
                        // Без этого cold-start не видел cache до settled-сети —
                        // юзер получал «пустой кадр → список».
                        feedItems = if (it.feedItems.isEmpty()) inMemoryCache else it.feedItems,
                        feedError = "",
                        adminConversationOpen = false,
                    )
                }
            }
            try {
                val scopeName = "admin_inbox:${session.getLogin()}"
                // Only re-hydrate from the on-disk Room cache if in-memory
                // state is STILL empty. Otherwise we'd stomp a fresher,
                // possibly-edited in-memory list with a stale disk snapshot
                // — the root cause of "show 2, then load 3rd, then
                // reorder" the user reported on tab-return.
                val needsDiskHydrate = !forceNetwork
                    && uiState.value.feedItems.isEmpty()
                    && uiState.value.feedItemsCache["admin_inbox"].orEmpty().isEmpty()
                if (needsDiskHydrate) {
                    val cached = feedRepository.getCachedFeed(scopeName)
                    if (cached.length() > 0) {
                        // Server returns every admin_message row (not one per contact),
                        // so the same sender_login can appear multiple times.
                        // Dedupe before rendering — the server orders by id DESC,
                        // so the first occurrence is the newest.
                        val list = cached.toJsonList()
                            .distinctBy { it.optString("sender_login") }
                        // §TZ-2.3.5+ — при cache-hit помечаем `feedFromCache=true`.
                        // AdminInboxTab НЕ считает это «первым settled загрузом»,
                        // skeleton остаётся пока сетевой ответ не пришёл. Иначе
                        // юзер видит cache-список, потом сеть добавляет нового
                        // контакта → эффект «1 пользователь догружается».
                        uiState.update {
                            it.copy(
                                feedItems = if (currentScopeKey() == "admin_inbox") list else it.feedItems,
                                feedItemsCache = it.feedItemsCache + ("admin_inbox" to list),
                                feedLoading = false,
                                feedFromCache = true,
                            )
                        }
                    }
                }
                val response = withContext(Dispatchers.IO) { ApiClient.getAdminMessages(limit = 100) }
                val ok = response.optBoolean("ok", false)
                if (ok) {
                    val data = response.optJSONArray("data") ?: JSONArray()
                    feedRepository.cacheFeed(scopeName, data)
                    val list = data.toJsonList()
                        .distinctBy { it.optString("sender_login") }
                    uiState.update {
                        it.copy(
                            feedItems = if (currentScopeKey() == "admin_inbox") list else it.feedItems,
                            feedItemsCache = it.feedItemsCache + ("admin_inbox" to list),
                            feedLoading = false,
                            feedFromCache = false,
                        )
                    }
                } else {
                    uiState.update { it.copy(feedLoading = false) }
                }
            } catch (_: Exception) {
                uiState.update { it.copy(feedLoading = false) }
            }
        }
    }

    // ── Admin conversation ───────────────────────────────────────────────────
    fun openAdminConversation(userLogin: String, userName: String) {
        val cacheScope = "admin_conv:$userLogin"
        val cached = uiState.value.feedItemsCache[cacheScope].orEmpty()
        // Try to resolve the peer's avatar from the inbox cache first, then
        // from the conversation cache. The header composable reads this field
        // directly — keeps the avatar consistent with the inbox row the user
        // just tapped.
        val avatarUrl = run {
            val fromInbox = uiState.value.feedItemsCache["admin_inbox"].orEmpty()
                .firstOrNull { it.optString("sender_login", "") == userLogin }
                ?.let { com.example.otlhelper.core.security.blobAwareUrl(it, "sender_avatar_url") }
                ?.takeIf { it.isNotBlank() }
            fromInbox ?: cached
                .firstOrNull { it.optString("sender_login", "") == userLogin }
                ?.let { com.example.otlhelper.core.security.blobAwareUrl(it, "sender_avatar_url") }
                ?.takeIf { it.isNotBlank() }
        }
        uiState.update {
            it.copy(
                adminConversationOpen = true,
                selectedAdminUserLogin = userLogin,
                selectedAdminUserName = userName,
                selectedAdminUserAvatarUrl = avatarUrl,
                feedItems = cached,
                feedLoading = cached.isEmpty(),
            )
        }
        loadAdminConversation()
        // NOTE: we DO NOT mass-mark the conversation as read here. Each message
        // is marked read individually when it actually scrolls into view — that
        // logic lives in AdminConversationTab's LazyColumn item composable.
    }

    fun closeAdminConversation() {
        val cached = uiState.value.feedItemsCache["admin_inbox"].orEmpty()
        uiState.update {
            it.copy(
                adminConversationOpen = false,
                selectedAdminUserLogin = null,
                selectedAdminUserName = null,
                feedItems = cached,
                feedLoading = cached.isEmpty(),
            )
        }
        // Don't force a network refresh on close — the 8s auto-refresh cycle
        // picks up any new messages. Immediate reload caused visible flicker:
        // user saw cached list, then network response replaced items causing
        // contacts to appear/disappear.
        if (cached.isEmpty()) loadAdminMessages()
    }

    fun loadAdminConversation(forceNetwork: Boolean = false) {
        val userLogin = uiState.value.selectedAdminUserLogin ?: return
        val cacheScope = "admin_conv:$userLogin"
        scope.launch {
            uiState.update { it.copy(feedLoading = true) }
            try {
                val scopeName = "admin_chat:$userLogin"
                if (!forceNetwork) {
                    val cached = feedRepository.getCachedFeed(scopeName)
                    if (cached.length() > 0) {
                        val list = cached.toJsonList()
                        uiState.update {
                            it.copy(
                                feedItems = if (currentScopeKey() == cacheScope) list else it.feedItems,
                                feedItemsCache = it.feedItemsCache + (cacheScope to list),
                            )
                        }
                    }
                }
                val response = withContext(Dispatchers.IO) { ApiClient.getAdminChat(userLogin, limit = 100) }
                val ok = response.optBoolean("ok", false)
                if (ok) {
                    val data = response.optJSONArray("data") ?: JSONArray()
                    feedRepository.cacheFeed(scopeName, data)
                    val serverList = data.toJsonList()
                    // Preserve optimistic pending messages that the server
                    // hasn't persisted yet — avoids the "send → message
                    // flashes → disappears" race between our optimistic append
                    // and the post-send reload (the server may reply before
                    // committing the new row).
                    val list = mergeWithPending(serverList, uiState.value.feedItems)
                    uiState.update {
                        it.copy(
                            feedItems = if (currentScopeKey() == cacheScope) list else it.feedItems,
                            feedItemsCache = it.feedItemsCache + (cacheScope to list),
                            feedLoading = false,
                            feedFromCache = false,
                        )
                    }
                } else {
                    uiState.update { it.copy(feedLoading = false) }
                }
            } catch (_: Exception) {
                uiState.update { it.copy(feedLoading = false) }
            }
        }
    }

    // ── User chat ────────────────────────────────────────────────────────────
    fun loadUserChat(forceNetwork: Boolean = false) {
        scope.launch {
            // Only show loading spinner when feed is truly empty — prevents
            // welcome/skeleton flicker on auto-refresh.
            val alreadyHasItems = uiState.value.feedItems.isNotEmpty()
            uiState.update { it.copy(feedLoading = !alreadyHasItems, feedError = "") }
            try {
                val scopeName = "user_chat:${session.getLogin()}"
                if (!forceNetwork) {
                    val cached = feedRepository.getCachedFeed(scopeName)
                    if (cached.length() > 0) {
                        val list = cached.toJsonList()
                        uiState.update {
                            it.copy(
                                feedItems = if (currentScopeKey() == "user_chat") list else it.feedItems,
                                feedItemsCache = it.feedItemsCache + ("user_chat" to list),
                            )
                        }
                    }
                }
                val response = withContext(Dispatchers.IO) { ApiClient.getUserChat(limit = 100) }
                val ok = response.optBoolean("ok", false)
                if (ok) {
                    val data = response.optJSONArray("data") ?: JSONArray()
                    feedRepository.cacheFeed(scopeName, data)
                    val serverList = data.toJsonList()
                    // Preserve optimistic pending messages that the server
                    // hasn't persisted yet (same race as loadAdminConversation).
                    val list = mergeWithPending(serverList, uiState.value.feedItems)
                    uiState.update {
                        it.copy(
                            feedItems = if (currentScopeKey() == "user_chat") list else it.feedItems,
                            feedItemsCache = it.feedItemsCache + ("user_chat" to list),
                            feedLoading = false,
                            feedFromCache = false,
                        )
                    }
                } else {
                    uiState.update { it.copy(feedLoading = false) }
                }
            } catch (_: Exception) {
                val scopeName = "user_chat:${session.getLogin()}"
                val cached = feedRepository.getCachedFeed(scopeName)
                val list = cached.toJsonList()
                uiState.update {
                    it.copy(
                        feedItems = if (list.isNotEmpty() && currentScopeKey() == "user_chat") list else it.feedItems,
                        feedItemsCache = if (list.isNotEmpty()) it.feedItemsCache + ("user_chat" to list) else it.feedItemsCache,
                        feedLoading = false,
                        feedFromCache = true,
                    )
                }
            }
        }
    }

    // ── Send ─────────────────────────────────────────────────────────────────
    fun sendMessage(
        text: String,
        receiverLogin: String? = null,
        attachments: JSONArray = JSONArray(),
        replyToId: Long = 0L,
        onResult: (Boolean, String) -> Unit,
    ) {
        if (text.isBlank() && attachments.length() == 0) return
        // Optimistic append — UI updates instantly. Server confirms via
        // push + auto-refresh (5-8s), not via blocking reload. Historical
        // note: we used to reload the whole chat on success, which added
        // +2-5s on bad networks. Push-FCM or the next polling tick picks
        // up the server id and swaps the optimistic row.
        val localId = UUID.randomUUID().toString()
        appendLocalPendingMessage(text, receiverLogin, localId)
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    // §TZ-2.3.11 — передаём localId как idempotency key. При
                    // network timeout OkHttp retry'ит, но сервер обнаружит
                    // дубль по (sender, local_item_id) и вернёт existing row.
                    ApiClient.sendMessage(text, receiverLogin, attachments, replyToId, localId)
                }
                val ok = response.optBoolean("ok", false)
                val err = response.optString("error", "")
                if (ok) {
                    onResult(true, "")
                } else {
                    onResult(false, err.ifBlank { "Ошибка отправки" })
                }
            } catch (_: Exception) {
                val queuedId = feedRepository.enqueuePendingMessage(session.getLogin(), text, receiverLogin, localId)
                if (queuedId > 0L) onResult(true, "Сохранено локально")
                else onResult(false, "Не удалось сохранить")
            }
        }
    }

    internal fun appendLocalPendingMessage(text: String, receiverLogin: String?, localId: String) {
        val now = nowUtcIso()
        val item = JSONObject().apply {
            put("local_item_id", localId)
            put("kind", "message")
            put("text", text)
            put("created_at", now)
            put("sender_login", session.getLogin())
            put("sender_name", session.getFullName())
            put("receiver_login", receiverLogin.orEmpty())
            put("is_pending", true)
        }
        uiState.update { it.copy(feedItems = it.feedItems + item) }
    }

    // ── Mark read ────────────────────────────────────────────────────────────
    //
    // Triggered per-message when a chat bubble or news/poll card actually
    // scrolls into view (see ChatBubble/NewsCard LaunchedEffect on itemId).
    //
    // Always does an immediate optimistic local update — the UI shows "read"
    // without waiting for the network round-trip. Then:
    //   • success → reconcile unread counts with server
    //   • network error → enqueue a pending `mark_message_read`, processed
    //     by flushPending() on the next heartbeat / tick so the read stays
    //     applied after reconnect.
    /**
     * §TZ-2.3.38 — отдельный метод для новостей/опросов. Вызывается из NewsTab
     * только когда item ещё unread (isUnread check там выше). Безусловно
     * декрементит newsUnreadCount — чинит залипание бейджа при race с
     * refresh'ем feedItems. Защита от double-decrement через readInFlightIds.
     */
    fun markNewsRead(id: Long) {
        if (id <= 0L || !readInFlightIds.add(id)) return
        // Optimistic: независимо от target'а — безусловный decrement, потому
        // что caller (NewsTab LaunchedEffect) уже убедился что item unread.
        uiState.update { state ->
            val updatedItems = state.feedItems.map { item ->
                if (item.optLong("id", -1L) == id) {
                    JSONObject(item.toString()).apply {
                        put("is_read", 1)
                        put("status", "read")
                    }
                } else item
            }
            state.copy(
                feedItems = updatedItems,
                newsUnreadCount = (state.newsUnreadCount - 1).coerceAtLeast(0),
            )
        }
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.markMessageRead(id) }
                if (response.optBoolean("ok", false)) {
                    scheduleUnreadReconcile()
                } else {
                    feedRepository.enqueuePendingMarkRead(id)
                }
            } catch (_: Exception) {
                feedRepository.enqueuePendingMarkRead(id)
            } finally {
                readInFlightIds.remove(id)
            }
        }
    }

    fun markMessageRead(id: Long) {
        if (id <= 0L || !readInFlightIds.add(id)) return

        // §TZ-2.3.38 — optimistic local update. Расширили поиск target'а:
        // ищем не только в текущем state.feedItems, но и во всех кешах
        // (news/admin_inbox/admin_conv/user_chat). Это чинит залипание
        // бейджа новостей при быстрой прокрутке, когда state.feedItems
        // мог быть свежее-refresh'нут сервером и item уже с is_read=1 там,
        // а бейдж newsUnreadCount ещё не обнулён.
        uiState.update { state ->
            // Поиск target'а по ВСЕМ источникам — feedItems + все кеши.
            val target = state.feedItems.firstOrNull { it.optLong("id", -1L) == id }
                ?: state.feedItemsCache.values.asSequence()
                    .flatten()
                    .firstOrNull { it.optLong("id", -1L) == id }

            // Определяем kind заранее — даже если target не нашёлся (item
            // был скроллен а затем рефрешнут с удалением из списка), клиент
            // знает что markMessageRead вызван именно для news/poll/message.
            val kind = target?.optString("kind", "")?.lowercase().orEmpty()
            val isNews = kind.contains("news") || kind.contains("poll")
            val isMsg = kind.contains("message") || kind == "admin_message"

            // alreadyRead only блокирует обновление feedItems, но НЕ
            // бейдж-счётчик — он обновляется независимо.
            val alreadyRead = target != null && (
                target.optInt("is_read", 0) != 0 ||
                    target.optString("status", "") == "read"
                )

            val updatedItems = if (alreadyRead || target == null) state.feedItems else
                state.feedItems.map { item ->
                    if (item.optLong("id", -1L) == id) {
                        JSONObject(item.toString()).apply {
                            put("is_read", 1)
                            put("status", "read")
                            put("unread_count", 0)
                        }
                    } else item
                }

            // Optimistic per-contact decrement в admin_inbox кеше.
            val newCache = if (isMsg && target != null && !alreadyRead) {
                val senderLogin = target.optString("sender_login", "")
                val inbox = state.feedItemsCache["admin_inbox"]
                if (senderLogin.isNotBlank() && inbox != null) {
                    val patchedInbox = inbox.map { item ->
                        if (item.optString("sender_login", "") == senderLogin) {
                            val cur = item.optInt("unread_count", 0)
                            if (cur > 0) JSONObject(item.toString()).apply {
                                put("unread_count", (cur - 1).coerceAtLeast(0))
                            } else item
                        } else item
                    }
                    state.feedItemsCache + ("admin_inbox" to patchedInbox)
                } else state.feedItemsCache
            } else state.feedItemsCache

            // §TZ-2.3.38 — бейдж декрементим ТОЛЬКО если item был unread
            // (alreadyRead=false). Это защищает от двойного декремента если
            // вдруг markMessageRead вызывается повторно.
            val shouldDecrementBadge = !alreadyRead
            state.copy(
                feedItems = updatedItems,
                feedItemsCache = newCache,
                newsUnreadCount = if (isNews && shouldDecrementBadge)
                    (state.newsUnreadCount - 1).coerceAtLeast(0)
                else state.newsUnreadCount,
                monitoringUnreadCount = if (isMsg && shouldDecrementBadge)
                    (state.monitoringUnreadCount - 1).coerceAtLeast(0)
                else state.monitoringUnreadCount,
            )
        }

        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.markMessageRead(id) }
                if (response.optBoolean("ok", false)) {
                    // §TZ-2.3.38 — debounced reconciliation. Быстрая прокрутка
                    // генерит N запросов подряд; без debounce загоняем N
                    // параллельных getUnreadCounts → race → залипание бейджа.
                    scheduleUnreadReconcile()
                } else {
                    feedRepository.enqueuePendingMarkRead(id)
                }
            } catch (_: Exception) {
                feedRepository.enqueuePendingMarkRead(id)
            } finally {
                readInFlightIds.remove(id)
            }
        }
    }

    // ── Typing (Phase 8, §3.15.a.А) ──────────────────────────────────────────
    fun sendTypingStart(peerLogin: String) {
        if (peerLogin.isBlank()) return
        wsClient.sendTypingStart(peerLogin)
    }

    fun sendTypingStop(peerLogin: String) {
        if (peerLogin.isBlank()) return
        wsClient.sendTypingStop(peerLogin)
    }

    /** FCM push: another user started typing. Auto-expires via [Limits.TYPING_INDICATOR_TTL_MS]. */
    fun onTypingStartPushed(fromLogin: String) {
        if (fromLogin.isBlank()) return
        uiState.update { it.copy(typingFromLogins = it.typingFromLogins + fromLogin) }
        // Auto-clear via TTL — safety net if the stop event is lost.
        scope.launch {
            delay(Limits.TYPING_INDICATOR_TTL_MS)
            uiState.update { it.copy(typingFromLogins = it.typingFromLogins - fromLogin) }
        }
    }

    fun onTypingStopPushed(fromLogin: String) {
        if (fromLogin.isBlank()) return
        uiState.update { it.copy(typingFromLogins = it.typingFromLogins - fromLogin) }
    }

    // ── Push event routing ───────────────────────────────────────────────────
    /** Returns true if the push belongs to chat domain and was handled. */
    fun handlePush(type: String, data: Map<String, String>): Boolean {
        return when (type) {
            "admin_message", "user_message", "message" -> {
                val s = uiState.value
                if (s.activeTab == com.example.otlhelper.presentation.home.HomeTab.MONITORING) {
                    if (hasAdminAccess()) {
                        if (s.adminConversationOpen) loadAdminConversation(forceNetwork = true)
                        else loadAdminMessages(forceNetwork = true)
                    } else {
                        loadUserChat(forceNetwork = true)
                    }
                }
                true
            }
            "typing_start" -> {
                onTypingStartPushed(data["from_login"].orEmpty())
                true
            }
            "typing_stop" -> {
                onTypingStopPushed(data["from_login"].orEmpty())
                true
            }
            else -> false
        }
    }
}
