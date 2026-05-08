package com.example.otlhelper.presentation.home.internal

import android.content.Context
import com.example.otlhelper.ApiClient
import com.example.otlhelper.SessionManager
import com.example.otlhelper.data.pending.PendingAction
import com.example.otlhelper.data.repository.FeedRepository
import com.example.otlhelper.domain.limits.Limits
import com.example.otlhelper.presentation.home.HomeUiState
import com.example.otlhelper.presentation.widget.OtlHomeWidgetBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Feed domain slice of HomeViewModel.
 *
 * Owns:
 *  - news + poll feed loading
 *  - sending news / creating polls / voting
 *  - edit / soft-delete / undelete (24h window)
 *  - pin / unpin (MAX_PINNED enforcement)
 *  - reactions (toggle + optimistic local apply)
 *  - admin inspection: news readers, poll stats
 *  - scheduled post creation
 *
 * Mutates the shared [uiState] under fields: feedItems, feedLoading,
 * feedError, feedFromCache, feedItemsCache["news"], pinnedFeedItems.
 */
internal class FeedController(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<HomeUiState>,
    private val session: SessionManager,
    private val feedRepository: FeedRepository,
    private val context: Context,
    private val currentScopeKey: () -> String,
    private val onUnreadCountsChanged: () -> Unit,
    private val setStatus: (String) -> Unit,
) {

    // ── News feed ────────────────────────────────────────────────────────────
    fun loadNews(forceNetwork: Boolean = false) {
        scope.launch {
            uiState.update { it.copy(feedLoading = true, feedError = "") }
            try {
                val scopeName = "news:${session.getLogin()}"
                if (!forceNetwork) {
                    val cached = feedRepository.getCachedFeed(scopeName)
                    if (cached.length() > 0) {
                        // Show cache instantly while the fresh fetch is in
                        // flight. feedFromCache is intentionally NOT set here —
                        // the "Оффлайн" banner must only appear when the live
                        // fetch actually fails (see the catch block below).
                        uiState.update { it.copy(feedItems = cached.toJsonList()) }
                    }
                }
                val response = withContext(Dispatchers.IO) { ApiClient.getNews(limit = 50) }
                val ok = response.optBoolean("ok", false)
                if (ok) {
                    val data = response.optJSONArray("data") ?: JSONArray()
                    val serverItems = sortFeedItems(data.toJsonList())
                    // Same anti-race as chat: keep optimistic pending news/poll
                    // items that outran the server commit.
                    val items = mergeWithPending(serverItems, uiState.value.feedItems)
                    feedRepository.cacheFeed(scopeName, JSONArray(items))
                    // §3.5: max Limits.MAX_PINNED pinned items, server order preserved.
                    val pinned = items.filter { it.optInt("is_pinned", 0) != 0 }.take(Limits.MAX_PINNED)
                    uiState.update {
                        it.copy(
                            feedItems = if (currentScopeKey() == "news") items else it.feedItems,
                            feedItemsCache = it.feedItemsCache + ("news" to items),
                            feedLoading = false,
                            feedFromCache = false,
                            pinnedFeedItems = pinned,
                        )
                    }
                    // Widget: publish up to 3 pinned previews (title-only lines)
                    val pinnedLines = pinned.take(3).map { item ->
                        val kind = item.optString("kind", "news")
                        val raw = item.optString("text", "").ifBlank {
                            item.optJSONObject("poll")?.optString("description", "") ?: ""
                        }
                        if (kind == "poll") "Опрос: $raw" else raw
                    }
                    OtlHomeWidgetBridge.publishPinnedList(context, pinnedLines)
                    onUnreadCountsChanged()
                } else {
                    // Server responded (we ARE online), just the action failed —
                    // clear the cache indicator so users don't see a stale "Оффлайн".
                    uiState.update { it.copy(feedLoading = false, feedFromCache = false) }
                }
            } catch (_: Exception) {
                val scopeName = "news:${session.getLogin()}"
                val cached = feedRepository.getCachedFeed(scopeName)
                uiState.update {
                    it.copy(
                        feedItems = if (cached.length() > 0) cached.toJsonList() else it.feedItems,
                        feedLoading = false,
                        feedFromCache = true,
                    )
                }
            }
        }
    }

    // ── Send news / polls ────────────────────────────────────────────────────
    fun sendNews(text: String, attachments: JSONArray = JSONArray(), onResult: (Boolean, String) -> Unit) {
        if (text.isBlank() && attachments.length() == 0) return
        // Optimistic append — UI updates instantly while the network call
        // is in flight. The subsequent loadNews(true) replaces this local
        // row with the server-assigned id / ordering on success.
        val localId = UUID.randomUUID().toString()
        appendLocalPendingNews(text, localId)
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.sendNews(text, attachments) }
                val ok = response.optBoolean("ok", false)
                val err = response.optString("error", "")
                if (ok) {
                    setStatus("")
                    loadNews(true)
                    onUnreadCountsChanged()
                    onResult(true, "")
                } else {
                    onResult(false, err.ifBlank { "Ошибка отправки новости" })
                }
            } catch (_: Exception) {
                val queuedId = feedRepository.enqueuePendingNews(session.getLogin(), text, localId)
                if (queuedId > 0L) onResult(true, "Сохранено локально")
                else onResult(false, "Не удалось сохранить")
            }
        }
    }

    /**
     * Simplified poll creation — only the question + answer options + optional
     * attachments. Legacy flags (multiple-select / allow-revoting / include-admins)
     * are gone from the UI; we always send single-choice, no-revote, include-admins=true.
     */
    fun createPoll(
        description: String,
        options: List<String>,
        attachments: JSONArray = JSONArray(),
        onResult: (Boolean, String) -> Unit,
    ) {
        val title = description  // keep existing server contract: title = description
        val selectionMode = "single"
        val allowRevoting = false
        val includeAdmins = true
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.createNewsPoll(title, description, options, selectionMode, allowRevoting, includeAdmins, attachments = attachments)
                }
                val ok = response.optBoolean("ok", false)
                val err = response.optString("error", "")
                if (ok) {
                    loadNews(true)
                    onResult(true, "")
                } else {
                    onResult(false, err.ifBlank { "Ошибка создания опроса" })
                }
            } catch (_: Exception) {
                val localId = UUID.randomUUID().toString()
                val queuedId = feedRepository.enqueuePendingCreatePoll(
                    session.getLogin(), title, description, selectionMode, allowRevoting, includeAdmins, options, localId,
                )
                if (queuedId > 0L) {
                    appendLocalPendingPoll(title, description, selectionMode, allowRevoting, includeAdmins, options, localId)
                    onResult(true, "Опрос сохранён локально")
                } else {
                    onResult(false, "Не удалось сохранить опрос")
                }
            }
        }
    }

    fun votePoll(pollId: Long, optionIds: List<Long>, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.voteNewsPoll(pollId, optionIds) }
                val ok = response.optBoolean("ok", false)
                val err = response.optString("error", "")
                if (ok) {
                    loadNews(true)
                    onResult(true, "Голос учтён")
                } else {
                    onResult(false, err.ifBlank { "Не удалось сохранить голос" })
                }
            } catch (_: Exception) {
                val queuedId = feedRepository.enqueuePendingVote(pollId, optionIds)
                if (queuedId > 0L) onResult(true, "Голос сохранён локально. Отправим при появлении сети")
                else onResult(false, "Не удалось сохранить голос")
            }
        }
    }

    // ── Edit / soft-delete / undo (Phase 12a, §3.15.a.А) ─────────────────────
    fun editMessage(messageId: Long, newText: String, onResult: (Boolean, String) -> Unit) {
        if (messageId <= 0L || newText.isBlank()) {
            onResult(false, "Пустой текст"); return
        }
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { ApiClient.editMessage(messageId, newText) }
                val ok = r.optBoolean("ok", false)
                if (ok) {
                    // Optimistic local patch so the edit is visible before the
                    // forthcoming loadNews reload finishes.
                    uiState.update { state ->
                        val patched = state.feedItems.map { item ->
                            if (item.optLong("id", -1L) == messageId) {
                                JSONObject(item.toString()).also { it.put("text", newText) }
                            } else item
                        }
                        state.copy(feedItems = patched)
                    }
                    loadNews(forceNetwork = true)
                    onResult(true, "")
                } else {
                    val err = r.optString("error", "")
                    val msg = when (err) {
                        "edit_window_expired" -> "Прошло больше 24 часов"
                        "forbidden" -> "Нет прав на правку"
                        else -> err.ifBlank { "Не удалось отредактировать" }
                    }
                    onResult(false, msg)
                }
            } catch (_: Exception) {
                feedRepository.enqueuePendingAction(
                    PendingAction.EditMessage(messageId, newText).actionType,
                    PendingAction.EditMessage(messageId, newText).toJson(),
                    messageId.toString(),
                )
                onResult(true, "Сохранится когда появится сеть")
            }
        }
    }

    /**
     * Soft-delete with 7-second undo window. Client UI decides when to close
     * the undo window; the server does not physically delete — only sets
     * status='deleted' until undelete is called.
     */
    fun softDeleteMessage(messageId: Long, onResult: (Boolean, String) -> Unit) {
        if (messageId <= 0L) { onResult(false, "id пустой"); return }
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { ApiClient.softDeleteMessage(messageId) }
                val ok = r.optBoolean("ok", false)
                if (ok) {
                    loadNews(forceNetwork = true)
                    onResult(true, "")
                } else {
                    val err = r.optString("error", "")
                    val msg = when (err) {
                        "forbidden" -> "Нет прав"
                        else -> err.ifBlank { "Не удалось удалить" }
                    }
                    onResult(false, msg)
                }
            } catch (_: Exception) {
                feedRepository.enqueuePendingAction(
                    PendingAction.SoftDeleteMessage(messageId).actionType,
                    PendingAction.SoftDeleteMessage(messageId).toJson(),
                    messageId.toString(),
                )
                onResult(true, "Удалится когда появится сеть")
            }
        }
    }

    fun undeleteMessage(messageId: Long, onResult: (Boolean, String) -> Unit) {
        if (messageId <= 0L) { onResult(false, "id пустой"); return }
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { ApiClient.undeleteMessage(messageId) }
                if (r.optBoolean("ok", false)) {
                    loadNews(forceNetwork = true)
                    onResult(true, "")
                } else onResult(false, r.optString("error", "Не удалось отменить"))
            } catch (_: Exception) {
                feedRepository.enqueuePendingAction(
                    PendingAction.UndeleteMessage(messageId).actionType,
                    PendingAction.UndeleteMessage(messageId).toJson(),
                    messageId.toString(),
                )
                onResult(true, "Отменится когда появится сеть")
            }
        }
    }

    // ── Pin / unpin ──────────────────────────────────────────────────────────
    fun togglePin(messageId: Long, pin: Boolean, onResult: (Boolean, String) -> Unit) {
        // Client guard on MAX_PINNED — instant rejection without a round-trip
        // (§3.5). Server also enforces the limit via max_pinned_reached, but
        // this avoids the extra request when we already know there are
        // Limits.MAX_PINNED pinned items.
        if (pin) {
            val alreadyPinned = uiState.value.pinnedFeedItems.size
            val isAlreadyThis = uiState.value.pinnedFeedItems.any { it.optLong("id", -1L) == messageId }
            if (!isAlreadyThis && alreadyPinned >= Limits.MAX_PINNED) {
                onResult(false, "Максимум ${Limits.MAX_PINNED} закреплённых")
                return
            }
        }
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    if (pin) ApiClient.pinMessage(messageId) else ApiClient.unpinMessage(messageId)
                }
                val ok = response.optBoolean("ok", false)
                val err = response.optString("error", "")
                if (ok) {
                    loadNews(true)
                    onResult(true, "")
                } else {
                    val msg = when {
                        err.contains("max", ignoreCase = true) -> "Максимум ${Limits.MAX_PINNED} закреплённых"
                        else -> err.ifBlank { if (pin) "Не удалось закрепить" else "Не удалось открепить" }
                    }
                    onResult(false, msg)
                }
            } catch (_: Exception) {
                onResult(false, "Нет соединения с сервером")
            }
        }
    }

    // ── Reactions (Phase 12b, §3.15.a.А) ─────────────────────────────────────
    /**
     * Toggle reaction with optimistic "present/absent" semantics. Server is
     * idempotent (UNIQUE constraint), so double-sends don't corrupt state.
     * On network failure the auto-refresh tick re-syncs with server truth.
     */
    fun toggleReaction(messageId: Long, emoji: String, alreadyReacted: Boolean) {
        if (messageId <= 0L) return

        applyReactionLocally(messageId, emoji, alreadyReacted)

        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    if (alreadyReacted) ApiClient.removeReaction(messageId, emoji)
                    else ApiClient.addReaction(messageId, emoji)
                }
                if (!r.optBoolean("ok", false)) {
                    // Revert on failure — rare because server UNIQUE is idempotent.
                    applyReactionLocally(messageId, emoji, !alreadyReacted)
                }
            } catch (_: Exception) {
                // Offline — auto-refresh cycle will re-sync with server state.
            }
        }
    }

    private fun applyReactionLocally(messageId: Long, emoji: String, wasReacted: Boolean) {
        uiState.update { state ->
            val updated = state.feedItems.map { item ->
                val id = item.optLong("id", 0L)
                if (id != messageId) return@map item
                val clone = JSONObject(item.toString())
                val reactions = clone.optJSONObject("reactions") ?: JSONObject()
                val myReactions = clone.optJSONArray("my_reactions") ?: JSONArray()
                val current = reactions.optInt(emoji, 0)
                if (wasReacted) {
                    val next = (current - 1).coerceAtLeast(0)
                    if (next == 0) reactions.remove(emoji) else reactions.put(emoji, next)
                    val filtered = JSONArray()
                    for (i in 0 until myReactions.length()) {
                        val v = myReactions.optString(i, "")
                        if (v != emoji && v.isNotBlank()) filtered.put(v)
                    }
                    clone.put("my_reactions", filtered)
                } else {
                    reactions.put(emoji, current + 1)
                    var has = false
                    for (i in 0 until myReactions.length()) {
                        if (myReactions.optString(i, "") == emoji) { has = true; break }
                    }
                    if (!has) myReactions.put(emoji)
                    clone.put("my_reactions", myReactions)
                }
                clone.put("reactions", reactions)
                clone
            }
            state.copy(feedItems = updated)
        }
    }

    suspend fun loadReactions(messageId: Long): JSONObject? = try {
        withContext(Dispatchers.IO) { ApiClient.getReactions(messageId) }
    } catch (_: Exception) { null }

    // ── Admin inspection ─────────────────────────────────────────────────────
    fun getNewsReaders(messageId: Long, onResult: (JSONObject?) -> Unit) {
        android.util.Log.i("FeedController", "getNewsReaders: messageId=$messageId")
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.getNewsReaders(messageId) }
                android.util.Log.i(
                    "FeedController",
                    "getNewsReaders resp: ok=${response.optBoolean("ok", false)} err=${response.optString("error")}",
                )
                onResult(response)
            } catch (e: Exception) {
                android.util.Log.w("FeedController", "getNewsReaders threw", e)
                onResult(null)
            }
        }
    }

    fun getPollStats(pollId: Long, onResult: (JSONObject?) -> Unit) {
        android.util.Log.i("FeedController", "getPollStats: pollId=$pollId")
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.getPollStats(pollId) }
                android.util.Log.i(
                    "FeedController",
                    "getPollStats resp: ok=${response.optBoolean("ok", false)} err=${response.optString("error")}",
                )
                onResult(response)
            } catch (e: Exception) {
                android.util.Log.w("FeedController", "getPollStats threw", e)
                onResult(null)
            }
        }
    }

    // ── Scheduled send (Phase 12c, §3.15.a.А) ─────────────────────────────────
    /**
     * Schedule a news/poll post. [sendAtUtc] must be 'YYYY-MM-DD HH:MM:SS' UTC;
     * the server validates that the time is in the future.
     */
    fun schedulePost(kind: String, text: String, sendAtUtc: String, onResult: (Boolean, String) -> Unit) {
        if (text.isBlank() || sendAtUtc.isBlank()) {
            onResult(false, "Пустой текст или время"); return
        }
        scope.launch {
            try {
                val payload = JSONObject().apply { put("text", text) }
                val r = withContext(Dispatchers.IO) { ApiClient.scheduleMessage(kind, payload, sendAtUtc) }
                if (r.optBoolean("ok", false)) onResult(true, "")
                else onResult(false, r.optString("error", "Не удалось"))
            } catch (_: Exception) {
                onResult(false, "Нет соединения")
            }
        }
    }

    // ── Optimistic append helpers ────────────────────────────────────────────
    internal fun appendLocalPendingNews(text: String, localId: String) {
        val now = nowUtcIso()
        val item = JSONObject().apply {
            put("local_item_id", localId)
            put("kind", "news")
            put("text", text)
            put("created_at", now)
            put("sender_login", session.getLogin())
            put("sender_name", session.getFullName())
            put("is_pending", true)
        }
        uiState.update { it.copy(feedItems = it.feedItems + item) }
    }

    internal fun appendLocalPendingPoll(
        title: String, description: String, selectionMode: String,
        allowRevoting: Boolean, includeAdmins: Boolean, options: List<String>, localId: String,
    ) {
        val now = nowUtcIso()
        val optionsJson = JSONArray().apply {
            options.forEach { put(JSONObject().apply { put("text", it); put("id", -1) }) }
        }
        val item = JSONObject().apply {
            put("local_item_id", localId)
            put("kind", "news_poll")
            put("title", title)
            put("text", description)
            put("created_at", now)
            put("sender_login", session.getLogin())
            put("is_pending", true)
            put("poll", JSONObject().apply {
                put("title", title)
                put("description", description)
                put("selection_mode", selectionMode)
                put("allow_revoting", allowRevoting)
                put("options", optionsJson)
            })
        }
        uiState.update { it.copy(feedItems = it.feedItems + item) }
    }

    // ── Push event routing ───────────────────────────────────────────────────
    /** Returns true if the push belongs to feed domain and was handled. */
    fun handlePush(type: String): Boolean {
        return when (type) {
            "news", "poll", "news_poll" -> {
                if (uiState.value.activeTab == com.example.otlhelper.presentation.home.HomeTab.NEWS) {
                    loadNews(forceNetwork = true)
                }
                true
            }
            else -> false
        }
    }
}
