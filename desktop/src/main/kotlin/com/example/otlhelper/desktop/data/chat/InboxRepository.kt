package com.example.otlhelper.desktop.data.chat

import com.example.otlhelper.desktop.data.cache.DiskCache
import com.example.otlhelper.desktop.data.network.ApiClient
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
 * §TZ-DESKTOP-0.1.0 этап 4b — простой inbox repository с polling.
 *
 * Сейчас: получение per-sender last message каждые 10с + forced refresh
 * по запросу (например при WS `new_message`). Sort + dedup делает сервер
 * (после моего INNER JOIN фикса от 2026-04-23 → server шлёт уже готовый
 * список, клиент просто показывает).
 *
 * В этапе 4c добавим WS-хук: на `new_message` / `unread_update` сразу
 * refresh() без ожидания следующего тика.
 */
class InboxRepository(private val scope: CoroutineScope) {

    data class Row(
        val senderLogin: String,
        val senderName: String,
        val senderRole: String,
        val senderPresence: String,
        val senderAvatarUrl: String,
        val lastText: String,
        val unreadCount: Int,
        val lastMessageId: Long,
        val createdAt: String,
    )

    data class State(
        val rows: List<Row> = emptyList(),
        val isLoading: Boolean = true,
        val lastError: String = "",
    )

    private val _state = MutableStateFlow(State(isLoading = true))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // Мгновенный offline-snapshot при создании репо — UI сразу получит
        // последний известный список, не дожидаясь первого сетевого ответа.
        loadFromCache()
    }

    private fun loadFromCache() {
        val text = DiskCache.read(CACHE_KEY) ?: return
        try {
            val arr = JSONArray(text)
            val rows = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                parseRow(o)
            }
            if (rows.isNotEmpty()) {
                _state.value = State(rows = rows, isLoading = true, lastError = "")
            }
        } catch (_: Exception) { /* битый кэш — игнорим */ }
    }

    private fun saveCache(rows: List<Row>) {
        val arr = JSONArray()
        rows.forEach { r ->
            arr.put(
                JSONObject()
                    .put("sender_login", r.senderLogin)
                    .put("sender_name", r.senderName)
                    .put("sender_role", r.senderRole)
                    .put("sender_presence_status", r.senderPresence)
                    .put("sender_avatar_url", r.senderAvatarUrl)
                    .put("text", r.lastText)
                    .put("unread_count", r.unreadCount)
                    .put("id", r.lastMessageId)
                    .put("created_at", r.createdAt)
            )
        }
        DiskCache.write(CACHE_KEY, arr.toString())
    }

    private var pollJob: Job? = null

    @Volatile
    private var paused: Boolean = false
    fun setPaused(p: Boolean) { paused = p }

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

    suspend fun refresh() {
        if (paused) return
        try {
            val resp = ApiClient.getAdminMessages(limit = 100)
            if (!resp.optBoolean("ok", false)) {
                val newError = resp.optString("error", "unknown")
                if (_state.value.isLoading || _state.value.lastError != newError) {
                    _state.value = _state.value.copy(isLoading = false, lastError = newError)
                }
                return
            }
            val arr = resp.optJSONArray("data") ?: return
            val rows = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                parseRow(o)
            }
            val newState = State(rows = rows, isLoading = false, lastError = "")
            if (paused) return
            if (newState != _state.value) {
                _state.value = newState
                saveCache(rows)
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            if (_state.value.isLoading || _state.value.lastError != msg) {
                _state.value = _state.value.copy(isLoading = false, lastError = msg)
            }
        }
    }

    private fun parseRow(o: JSONObject): Row = Row(
        senderLogin = o.optString("sender_login"),
        senderName = o.optString("sender_name").ifBlank { o.optString("sender_login") },
        senderRole = o.optString("sender_role"),
        senderPresence = o.optString("sender_presence_status", "offline"),
        senderAvatarUrl = com.example.otlhelper.desktop.data.security.blobAwareUrl(o, "sender_avatar_url"),
        lastText = o.optString("text"),
        unreadCount = o.optInt("unread_count", 0),
        lastMessageId = o.optLong("id", 0),
        createdAt = o.optString("created_at"),
    )

    companion object {
        // §0.10.26 — был 10s, уменьшен до 3s. Юзер просил мгновенный
        // refresh статусов (presence dot online/paused/offline) в chat
        // list. True realtime (<1s) требует WS presence event broadcast
        // на сервере — отдельный спринт 0.11.0.
        private const val POLL_INTERVAL_MS = 3_000L
        private const val CACHE_KEY = "inbox"
    }
}
