package com.example.otlhelper.desktop.data.chat

import com.example.otlhelper.desktop.data.network.ApiClient
import com.example.otlhelper.desktop.util.formatTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * §TZ-DESKTOP-0.1.0 этап 4b.2 — переписка с одним юзером.
 * Polling 5 сек пока чат открыт. На этапе 4c добавим хук из WsClient
 * (на `new_message` с `sender_login == peerLogin` — forced refresh).
 */
class ConversationRepository(
    private val scope: CoroutineScope,
    private val myLogin: String,
) {

    data class Message(
        val id: Long,
        val text: String,
        val time: String,        // "h:mm a" Екат.
        val isOwn: Boolean,
        val isRead: Boolean,
        val reactions: Map<String, Int>,
        val myReactions: Set<String>,
        val attachments: List<com.example.otlhelper.desktop.data.feed.NewsRepository.Attachment>,
        val dayLabel: String,    // "Сегодня" / "Вчера" / "22 апр"
        val rawCreatedAt: String,
    )

    data class State(
        val peerLogin: String = "",
        val peerName: String = "",
        val peerPresence: String = "offline",
        val peerAvatarUrl: String = "",
        val messages: List<Message> = emptyList(),
        val isLoading: Boolean = false,
        val lastError: String = "",
        // §TZ-DESKTOP 0.4.x — when not null, ConversationScreen scrolls
        // к сообщению с этим id и подсвечивает его (search-result navigation).
        val scrollToMessageId: Long? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var pollJob: Job? = null

    // §TZ-DESKTOP-0.1.0 этап 4c — messages cache per peer. Чтобы при повторном
    // открытии чата UI мгновенно рисовал последние видимые сообщения (как Android:
    // feedItems уже в ViewModel-памяти), а refresh() фоном обновлял.
    private val cache = mutableMapOf<String, List<Message>>()
    private val cachedPresence = mutableMapOf<String, String>()

    fun open(
        peerLogin: String,
        peerName: String,
        scrollToMessageId: Long? = null,
        peerAvatarUrl: String = "",
    ) {
        // §TZ-0.10.8 — peerAvatarUrl передаётся из inbox-row → чтобы header
        // ConversationScreen рисовал аватарку peer'а сразу, не ждал пока
        // peer пришлёт сообщение (admin-юзер чат: первое open() — только
        // own messages, sender_avatar_url ещё нет, аватарка отсутствовала).
        val cached = cache[peerLogin].orEmpty()
        val presence = cachedPresence[peerLogin] ?: "offline"
        _state.value = State(
            peerLogin = peerLogin,
            peerName = peerName,
            peerPresence = presence,
            peerAvatarUrl = peerAvatarUrl,
            messages = cached,
            isLoading = cached.isEmpty(),
            lastError = "",
            scrollToMessageId = scrollToMessageId,
        )
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** ConversationScreen вызывает после scroll+highlight — гасит target. */
    fun clearScrollTarget() {
        if (_state.value.scrollToMessageId != null) {
            _state.value = _state.value.copy(scrollToMessageId = null)
        }
    }

    /**
     * §TZ-DESKTOP 0.4.x — search по in-memory message cache. Возвращает
     * messages чьи text матчит query (используя те же tokenization +
     * confusables-нормализацию что CentralSearchDialog.matchesQuery —
     * but inline здесь чтобы не было циркулярной зависимости).
     *
     * **Scope ограничен**: cache содержит ТОЛЬКО peers которые юзер
     * открывал в этой сессии. Full archive search требует server
     * endpoint `/chat/search` (TODO).
     */
    data class MessageHit(val peerLogin: String, val message: Message)

    fun searchMessages(query: String, limit: Int = 8): List<MessageHit> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val tokens = q.split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { normalizeForSearch(it) }
        if (tokens.isEmpty()) return emptyList()
        val hits = mutableListOf<MessageHit>()
        cache.forEach { (peerLogin, messages) ->
            messages.forEach { msg ->
                if (msg.text.isBlank()) return@forEach
                val haystack = normalizeForSearch(msg.text)
                if (tokens.all { haystack.contains(it) }) {
                    hits.add(MessageHit(peerLogin, msg))
                }
            }
        }
        // Newest first by rawCreatedAt (ISO-8601 lexicographically sortable).
        return hits.sortedByDescending { it.message.rawCreatedAt }.take(limit)
    }

    private fun normalizeForSearch(s: String): String {
        val lower = s.lowercase()
        val sb = StringBuilder(lower.length)
        for (ch in lower) {
            sb.append(cyrToLat[ch] ?: ch)
        }
        return sb.toString()
    }

    private val cyrToLat = mapOf(
        'а' to 'a', 'в' to 'b', 'с' to 'c', 'е' to 'e', 'н' to 'h',
        'к' to 'k', 'м' to 'm', 'о' to 'o', 'р' to 'p', 'т' to 't',
        'у' to 'y', 'х' to 'x', 'ё' to 'e',
    )

    fun close() {
        pollJob?.cancel()
        pollJob = null
        // Cache оставляем — при следующем open() restore будет мгновенным.
        _state.value = State()
    }

    suspend fun send(
        text: String,
        attachments: org.json.JSONArray = org.json.JSONArray(),
    ): Boolean {
        val peer = _state.value.peerLogin
        if (peer.isBlank()) return false
        if (text.isBlank() && attachments.length() == 0) return false
        return try {
            val resp = ApiClient.sendMessageToUser(peer, text, attachments = attachments)
            if (resp.optBoolean("ok", false)) {
                refresh()
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    suspend fun markRead(messageId: Long) {
        runCatching { ApiClient.markMessageRead(messageId) }
    }

    private suspend fun refresh() {
        val peer = _state.value.peerLogin
        if (peer.isBlank()) return
        try {
            val resp = ApiClient.getAdminChat(peer)
            if (!resp.optBoolean("ok", false)) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    lastError = resp.optString("error", "unknown"),
                )
                return
            }
            val arr = resp.optJSONArray("data") ?: return
            var peerPresence = _state.value.peerPresence
            var peerAvatar = _state.value.peerAvatarUrl
            val messages = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val senderLogin = o.optString("sender_login")
                val receiverLogin = o.optString("receiver_login")
                // §TZ-0.10.8 — peer_avatar обновляется и когда peer = receiver
                // (admin → user сообщения). Раньше только sender_avatar_url проверялся,
                // и если admin первым писал peer'у — peer_avatar в state оставался
                // пустым → header чата показывал инициалы вместо аватарки.
                if (senderLogin == peer) {
                    peerPresence = o.optString("sender_presence_status", peerPresence)
                    val a = com.example.otlhelper.desktop.data.security.blobAwareUrl(o, "sender_avatar_url")
                    if (a.isNotBlank()) peerAvatar = a
                } else if (receiverLogin == peer) {
                    val a = com.example.otlhelper.desktop.data.security.blobAwareUrl(o, "receiver_avatar_url")
                    if (a.isNotBlank()) peerAvatar = a
                    val rp = o.optString("receiver_presence_status", "")
                    if (rp.isNotBlank()) peerPresence = rp
                }
                Message(
                    id = o.optLong("id", 0),
                    text = o.optString("text"),
                    time = formatTime(o.optString("created_at")).ifBlank { "" },
                    isOwn = senderLogin == myLogin,
                    isRead = o.optInt("is_read", 0) == 1,
                    reactions = parseReactions(o),
                    myReactions = parseMyReactions(o),
                    attachments = parseAttachments(o),
                    dayLabel = dayLabelOf(o.optString("created_at")),
                    rawCreatedAt = o.optString("created_at"),
                )
            }
            // §TZ-0.10.11 — markRead для всех unread сообщений peer'а (server
            // обновляет status='read' в БД). Раньше desktop НЕ вызывал это
            // вообще → телефон видел unread даже после прочтения на ПК.
            // Fire-and-forget: не блокируем UI; если упадёт — ok, repeat
            // на следующий poll-tick.
            messages.filter { !it.isOwn && !it.isRead }.forEach { msg ->
                scope.launch {
                    runCatching { ApiClient.markMessageRead(msg.id) }
                }
            }

            // Обновляем cache ТОЛЬКО при успешном refresh — иначе оставляем
            // последний валидный snapshot, не заменяя empty на-время-сбоя.
            cache[peer] = messages
            cachedPresence[peer] = peerPresence
            _state.value = State(
                peerLogin = peer,
                peerName = _state.value.peerName,
                peerPresence = peerPresence,
                peerAvatarUrl = peerAvatar,
                messages = messages,
                isLoading = false,
                lastError = "",
                // Preserve pending scroll target — может быть установлен open()
                // и messages только что прилетели после refresh; ConversationScreen
                // подхватит и проскроллит.
                scrollToMessageId = _state.value.scrollToMessageId,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isLoading = false,
                lastError = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    private fun parseReactions(o: org.json.JSONObject): Map<String, Int> {
        // Сервер иногда шлёт `reactions` (get_admin_chat), иногда `reactions_aggregate`.
        val agg = o.optJSONObject("reactions") ?: o.optJSONObject("reactions_aggregate") ?: return emptyMap()
        val map = mutableMapOf<String, Int>()
        val keys = agg.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = agg.optInt(k, 0)
            if (v > 0) map[k] = v
        }
        return map
    }

    private fun parseMyReactions(o: org.json.JSONObject): Set<String> {
        val arr = o.optJSONArray("my_reactions") ?: return emptySet()
        val set = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "")
            if (v.isNotBlank()) set += v
        }
        return set
    }

    private fun parseAttachments(o: org.json.JSONObject): List<com.example.otlhelper.desktop.data.feed.NewsRepository.Attachment> {
        val arr = o.optJSONArray("attachments") ?: run {
            val s = o.optString("attachments_json", "")
            if (s.isBlank() || s == "null" || s == "[]") return emptyList()
            try { org.json.JSONArray(s) } catch (_: Exception) { return emptyList() }
        }
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = com.example.otlhelper.desktop.data.security.blobAwareUrl(a, "file_url")
            if (url.isBlank()) return@mapNotNull null
            com.example.otlhelper.desktop.data.feed.NewsRepository.Attachment(
                url = url,
                fileName = a.optString("file_name"),
                fileType = a.optString("file_type"),
                fileSize = a.optLong("file_size", 0L),
            )
        }
    }

    // §TZ-DESKTOP-0.1.0 этап 4c — реакции. Edit/delete запрещены политикой.

    suspend fun toggleReaction(messageId: Long, emoji: String, alreadyReacted: Boolean): Boolean {
        return try {
            val resp = if (alreadyReacted) ApiClient.removeReaction(messageId, emoji)
            else ApiClient.addReaction(messageId, emoji)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /** Публичный refresh — вызывается из внешних WS-хуков (будущая версия). */
    suspend fun forceRefresh() = refresh()

    /**
     * §TZ-DESKTOP-UX-2026-04 — фоновый prefetch чатов всех peer'ов из
     * inbox. После этого [searchMessages] возвращает попадания из ВСЕХ
     * чатов, а не только из тех что юзер успел открыть в текущей сессии.
     *
     * Грузим последовательно с small delay между peer'ами — не задрочить
     * server и не съесть всю сеть, если юзер сразу после login полез
     * пользоваться приложением. Параллельно с [open] работает безопасно:
     * пишем в [cache] (per-peer key), `_state` не трогаем — он принадлежит
     * текущему открытому чату.
     *
     * Idempotent: если для peer'а cache уже наполнен — пропускаем.
     */
    suspend fun prefetchPeers(peerLogins: List<String>, limitPerPeer: Int = 200) {
        for (peer in peerLogins) {
            if (peer.isBlank()) continue
            // Skip если уже что-то закэшировано (open() мог наполнить).
            // Дальнейший polling open() обновит свежей версией.
            if ((cache[peer]?.size ?: 0) >= limitPerPeer / 2) continue
            try {
                val resp = ApiClient.getAdminChat(peer, limit = limitPerPeer)
                if (!resp.optBoolean("ok", false)) continue
                val arr = resp.optJSONArray("data") ?: continue
                val messages = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val senderLogin = o.optString("sender_login")
                    Message(
                        id = o.optLong("id", 0),
                        text = o.optString("text"),
                        time = formatTime(o.optString("created_at")).ifBlank { "" },
                        isOwn = senderLogin == myLogin,
                        isRead = o.optInt("is_read", 0) == 1,
                        reactions = parseReactions(o),
                        myReactions = parseMyReactions(o),
                        attachments = parseAttachments(o),
                        dayLabel = dayLabelOf(o.optString("created_at")),
                        rawCreatedAt = o.optString("created_at"),
                    )
                }
                cache[peer] = messages
            } catch (_: Exception) {
                // Сетевые сбои — pre-fetch best-effort; полный поиск
                // останется ограниченным до следующего раза.
            }
            // 150ms throttle — N peers * 150ms у нас обычно < 30 сек.
            delay(150)
        }
    }

    /** Вызывается из WS-хука на `new_message`: если чат с [fromLogin]
     *  сейчас открыт — тут же рефрешим. Если не открыт — тихо игнорим
     *  (inbox обновится из своего onNewMessage-хука). */
    suspend fun refreshIfOpenWith(fromLogin: String) {
        if (_state.value.peerLogin == fromLogin) refresh()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
        private val yekZone = TimeZone.getTimeZone("Asia/Yekaterinburg").toZoneId()
        private val dayFmt = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))

        private fun dayLabelOf(raw: String): String {
            if (raw.isBlank()) return ""
            return try {
                val iso = if (raw.contains('T')) {
                    if (raw.endsWith("Z") || raw.contains('+')) raw else "${raw}Z"
                } else "${raw.replace(' ', 'T')}Z"
                val zdt = ZonedDateTime.parse(iso).withZoneSameInstant(yekZone)
                val today = java.time.LocalDate.now(yekZone)
                val date = zdt.toLocalDate()
                when (date) {
                    today -> "Сегодня"
                    today.minusDays(1) -> "Вчера"
                    else -> zdt.format(dayFmt)
                }
            } catch (_: Exception) { "" }
        }
    }
}
