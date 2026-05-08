package com.example.otlhelper.desktop.data.feed

import com.example.otlhelper.desktop.data.cache.DiskCache
import com.example.otlhelper.desktop.data.network.ApiClient
import com.example.otlhelper.desktop.util.formatDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * §TZ-DESKTOP-0.1.0 этап 5 — полный репозиторий ленты (новости + опросы + pinned).
 *
 * Polling 20 сек. Сервер возвращает items с уже сортировкой pinned сверху,
 * клиент не додумывает. Реакции/пин/голос → мгновенный forceRefresh после операции.
 */
class NewsRepository(
    private val scope: CoroutineScope,
    private val myLogin: String,
) {

    data class PollOption(
        val id: Long,
        val text: String,
        val votesCount: Int,
    )

    data class Poll(
        val id: Long,
        val title: String,
        val description: String,
        val options: List<PollOption>,
        val myVoteOptionId: Long?,       // null если не голосовал
        val totalVoters: Int,
        val isActive: Boolean,
    )

    data class Attachment(
        val url: String,
        val fileName: String,
        val fileType: String,            // MIME или ""
        val fileSize: Long,
    ) {
        val isImage: Boolean get() = fileType.startsWith("image") ||
            fileName.endsWithAnyCi(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp")
        val isVideo: Boolean get() = fileType.startsWith("video") ||
            fileName.endsWithAnyCi(".mp4", ".mov", ".avi", ".webm", ".mkv")
    }

    /** ВАЖНО: поля data class'а участвуют в equals — не добавляй сюда типы
     *  с reference-equality (JSONObject/JSONArray), иначе content-diff
     *  в refresh() будет всегда давать `!=` и каждый poll-тик будет
     *  дёргать recompose, ломая поля ввода в модалках. */
    data class Item(
        val id: Long,
        val kind: String,                // "news" | "poll"
        val senderLogin: String,
        val senderName: String,
        val senderAvatarUrl: String,
        val senderPresence: String,
        val text: String,
        val createdAt: String,
        val createdAtLabel: String,      // форматированная "23.04 10:00 AM"
        val isRead: Boolean,
        val isPinned: Boolean,
        val reactions: Map<String, Int>,
        val myReactions: Set<String>,
        val attachments: List<Attachment>,
        val poll: Poll?,                 // null если kind=news
        val isOwn: Boolean,
    )

    data class State(
        val items: List<Item> = emptyList(),
        val pinnedItems: List<Item> = emptyList(),
        val isLoading: Boolean = true,
        val lastError: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // Offline-snapshot: парсим последний сохранённый server response
        // чтобы лента мгновенно была видна на старте, а polling дальше обновит.
        val cached = DiskCache.read(CACHE_KEY)
        if (cached != null) try {
            val arr = JSONArray(cached)
            val rawItems = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                parseItem(o)
            }
            if (rawItems.isNotEmpty()) {
                val items = rawItems.sortedBy { it.createdAt }
                _state.value = State(
                    items = items,
                    pinnedItems = items.filter { it.isPinned },
                    isLoading = true, // остаёмся в загрузке — polling обновит
                    lastError = "",
                )
            }
        } catch (_: Exception) { /* битый кэш — ок, перезатрётся */ }
    }

    private var pollJob: Job? = null

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun forceRefresh() = refresh()

    /** Пауза/возобновление polling'а — используется когда пользователь печатает
     *  в модалке (PollBuilder/UserManagement/Account и т.п.), чтобы тикающий
     *  refresh не дёргал recomposition и текст-инпуты оставались стабильными. */
    @Volatile
    private var paused: Boolean = false
    fun setPaused(p: Boolean) { paused = p }

    private suspend fun refresh() {
        if (paused) return
        try {
            val resp = ApiClient.getNews(limit = 100)
            if (!resp.optBoolean("ok", false)) {
                val newError = resp.optString("error", "unknown")
                if (_state.value.isLoading || _state.value.lastError != newError) {
                    _state.value = _state.value.copy(isLoading = false, lastError = newError)
                }
                return
            }
            val arr = resp.optJSONArray("data") ?: return
            val rawItems = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                parseItem(o)
            }
            // §TZ-DESKTOP-0.1.0 этап 5 fix — Android (FeedMerge.sortFeedItems)
            // сортирует по created_at ASC: самые старые сверху, новые снизу.
            // Сервер отдаёт DESC — переворачиваем здесь.
            val items = rawItems.sortedBy { it.createdAt }
            val newState = State(
                items = items,
                pinnedItems = items.filter { it.isPinned },
                isLoading = false,
                lastError = "",
            )
            // Двойной guard: если пока шла сеть — юзер открыл модалку, не
            // эмитим вообще (пусть следующий tick обновит когда модалка
            // закроется). Плюс content-diff.
            if (paused) return
            if (newState != _state.value) {
                _state.value = newState
                DiskCache.write(CACHE_KEY, arr.toString())
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            if (_state.value.isLoading || _state.value.lastError != msg) {
                _state.value = _state.value.copy(isLoading = false, lastError = msg)
            }
        }
    }

    private fun parseItem(o: JSONObject): Item {
        val senderLogin = o.optString("sender_login")
        val createdAt = o.optString("created_at")
        return Item(
            id = o.optLong("id", 0),
            kind = o.optString("kind", "news"),
            senderLogin = senderLogin,
            senderName = o.optString("sender_name").ifBlank { senderLogin },
            senderAvatarUrl = com.example.otlhelper.desktop.data.security.blobAwareUrl(o, "sender_avatar_url"),
            senderPresence = o.optString("sender_presence_status", "offline"),
            text = o.optString("text"),
            createdAt = createdAt,
            createdAtLabel = formatDate(createdAt).ifBlank { createdAt.take(16) },
            isRead = o.optInt("is_read", 0) == 1,
            isPinned = o.optInt("is_pinned", 0) == 1,
            reactions = parseReactions(o),
            myReactions = parseMyReactions(o),
            attachments = parseAttachments(o),
            poll = parsePoll(o),
            isOwn = senderLogin == myLogin,
        )
    }

    private fun parseAttachments(o: JSONObject): List<Attachment> {
        // Сервер шлёт attachments как JSONArray в `attachments`, либо строкой `attachments_json`.
        val arr = o.optJSONArray("attachments") ?: run {
            val s = o.optString("attachments_json", "")
            if (s.isBlank() || s == "null" || s == "[]") return emptyList()
            try { JSONArray(s) } catch (_: Exception) { return emptyList() }
        }
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            // §TZ-2.3.28 — URL композитится с blob key/nonce в fragment, если
            // attachment encrypted (сервер шлёт blob_key_b64 + blob_nonce_b64
            // соседними полями). Плейн URL остаётся как есть.
            val url = com.example.otlhelper.desktop.data.security.blobAwareUrl(a, "file_url")
            if (url.isBlank()) return@mapNotNull null
            Attachment(
                url = url,
                fileName = a.optString("file_name"),
                fileType = a.optString("file_type"),
                fileSize = a.optLong("file_size", 0L),
            )
        }
    }

    private fun parseReactions(o: JSONObject): Map<String, Int> {
        val agg = o.optJSONObject("reactions") ?: return emptyMap()
        val map = mutableMapOf<String, Int>()
        val keys = agg.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = agg.optInt(k, 0)
            if (v > 0) map[k] = v
        }
        return map
    }

    private fun parseMyReactions(o: JSONObject): Set<String> {
        val arr = o.optJSONArray("my_reactions") ?: return emptySet()
        val set = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "")
            if (v.isNotBlank()) set += v
        }
        return set
    }

    private fun parsePoll(o: JSONObject): Poll? {
        // Сервер кладёт poll-данные внутри `poll` или отдельным объектом — проверим оба.
        val p = o.optJSONObject("poll") ?: return null
        val optsArr = p.optJSONArray("options") ?: JSONArray()
        val options = (0 until optsArr.length()).mapNotNull { i ->
            val opt = optsArr.optJSONObject(i) ?: return@mapNotNull null
            PollOption(
                id = opt.optLong("id", 0),
                text = opt.optString("option_text").ifBlank { opt.optString("text") },
                votesCount = opt.optInt("votes_count", 0),
            )
        }
        val myVote = p.optLong("my_vote_option_id", 0L).takeIf { it > 0L }
        return Poll(
            id = p.optLong("id", 0),
            title = p.optString("title"),
            description = p.optString("description"),
            options = options,
            myVoteOptionId = myVote,
            totalVoters = p.optInt("total_voters", 0),
            isActive = p.optInt("is_active", 1) == 1,
        )
    }

    // ── §TZ-DESKTOP-0.1.0 этап 5 — операции, после ok → forceRefresh ──

    suspend fun toggleReaction(messageId: Long, emoji: String, alreadyReacted: Boolean): Boolean {
        return try {
            val resp = if (alreadyReacted) ApiClient.removeReaction(messageId, emoji)
            else ApiClient.addReaction(messageId, emoji)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) { false }
    }

    suspend fun sendNews(text: String): Boolean {
        if (text.isBlank()) return false
        return try {
            val resp = ApiClient.sendNews(text)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) { false }
    }

    suspend fun createPoll(title: String, description: String, options: List<String>): Boolean {
        if (title.isBlank() || description.isBlank() || options.size < 2) return false
        return try {
            val resp = ApiClient.createNewsPoll(title, description, options)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) { false }
    }

    suspend fun vote(pollId: Long, optionId: Long): Boolean {
        return try {
            val resp = ApiClient.voteNewsPoll(pollId, optionId)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) { false }
    }

    suspend fun togglePin(messageId: Long, pin: Boolean): Boolean {
        return try {
            val resp = if (pin) ApiClient.pinMessage(messageId) else ApiClient.unpinMessage(messageId)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) { false }
    }

    suspend fun editNews(messageId: Long, newText: String): Boolean {
        if (newText.isBlank()) return false
        return try {
            val resp = ApiClient.editNews(messageId, newText)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) { false }
    }

    suspend fun deleteNews(messageId: Long): Boolean {
        return try {
            val resp = ApiClient.deleteNews(messageId)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) { false }
    }

    suspend fun markRead(messageId: Long): Boolean {
        return try {
            val resp = ApiClient.markMessageRead(messageId)
            resp.optBoolean("ok", false)
        } catch (_: Exception) { false }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 20_000L
        private const val CACHE_KEY = "news"

        private fun String.endsWithAnyCi(vararg suffixes: String): Boolean {
            val lower = lowercase()
            return suffixes.any { lower.endsWith(it) }
        }
    }
}

// extension для использования внутри Attachment data class
private fun String.endsWithAnyCi(vararg suffixes: String): Boolean {
    val lower = lowercase()
    return suffixes.any { lower.endsWith(it) }
}
