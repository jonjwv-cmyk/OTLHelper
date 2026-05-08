package com.example.otlhelper.desktop.data.scheduled

import com.example.otlhelper.desktop.data.network.ApiClient
import com.example.otlhelper.desktop.util.formatDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * §TZ-DESKTOP-0.1.0 этап 6 — Запланированные сообщения (admin+).
 * Load по запросу (на открытие sheet'а), без polling'а. После
 * schedule/cancel — forceRefresh вручную.
 */
class ScheduledRepository(private val scope: CoroutineScope) {

    data class Item(
        val id: Long,
        val kind: String,                  // "news" | "poll"
        val previewText: String,           // text или title опроса
        val sendAtRaw: String,             // как пришло от сервера (UTC)
        val sendAtLabel: String,           // форматированное "25.04 08:30 AM"
        val status: String,                // "pending" | "sent" | "cancelled"
        val sentAt: String,
        val cancelledAt: String,
    ) {
        val isPending: Boolean get() = status == "pending"
    }

    data class State(
        val items: List<Item> = emptyList(),
        val isLoading: Boolean = false,
        val lastError: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun refresh() {
        _state.value = _state.value.copy(isLoading = true, lastError = "")
        try {
            val resp = ApiClient.listScheduled()
            if (!resp.optBoolean("ok", false)) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    lastError = resp.optString("error", "unknown"),
                )
                return
            }
            val arr = resp.optJSONArray("data") ?: return
            val items = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val payload = o.optJSONObject("payload")
                val kind = o.optString("kind", "news")
                val preview = when (kind) {
                    "poll" -> payload?.optString("title").orEmpty().ifBlank {
                        payload?.optString("description").orEmpty()
                    }
                    else -> payload?.optString("text").orEmpty()
                }
                val sendAt = o.optString("send_at", "")
                Item(
                    id = o.optLong("id", 0),
                    kind = kind,
                    previewText = preview,
                    sendAtRaw = sendAt,
                    sendAtLabel = formatDate(sendAt).ifBlank { sendAt.take(16) },
                    status = o.optString("status", "pending"),
                    sentAt = o.optString("sent_at", ""),
                    cancelledAt = o.optString("cancelled_at", ""),
                )
            }
            _state.value = State(items = items, isLoading = false, lastError = "")
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isLoading = false,
                lastError = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    suspend fun scheduleNews(text: String, sendAtUtc: String): Boolean {
        if (text.isBlank() || sendAtUtc.isBlank()) return false
        return try {
            val resp = ApiClient.scheduleNews(text, sendAtUtc)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) { false }
    }

    suspend fun schedulePoll(
        title: String,
        description: String,
        options: List<String>,
        sendAtUtc: String,
    ): Boolean {
        if (title.isBlank() || options.size < 2 || sendAtUtc.isBlank()) return false
        return try {
            val resp = ApiClient.schedulePoll(title, description, options, sendAtUtc)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) { false }
    }

    suspend fun cancel(id: Long): Boolean {
        return try {
            val resp = ApiClient.cancelScheduled(id)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) { false }
    }
}
