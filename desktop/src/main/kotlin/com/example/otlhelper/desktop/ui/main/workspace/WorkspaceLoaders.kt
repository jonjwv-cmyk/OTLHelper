package com.example.otlhelper.desktop.ui.main.workspace

import com.example.otlhelper.desktop.data.network.ApiClient
import com.example.otlhelper.desktop.ui.dialogs.PollStatsData
import com.example.otlhelper.desktop.ui.dialogs.PollStatsOption
import com.example.otlhelper.desktop.ui.dialogs.PollVoter
import com.example.otlhelper.desktop.ui.dialogs.ReaderMock
import com.example.otlhelper.desktop.ui.dialogs.ReactionVoter
import com.example.otlhelper.desktop.ui.dialogs.ReadersData
import com.example.otlhelper.desktop.util.formatDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * §TZ-DESKTOP-0.1.0 — async data-конверсия для stats-диалогов.
 *
 * Все три loader'а имеют одинаковый контракт:
 *   • Зовутся из `LaunchedEffect` в [WorkspacePanel] при открытии stats-диалога
 *   • Возвращают `null` при ошибке (UI закрывает диалог)
 *   • Read-only — не меняют серверное состояние
 *
 * Вынесено из `WorkspacePanel.kt` чтобы отделить **data-mapping** от
 * UI-orchestration: логика "JSON → SheetX" не должна быть рядом с
 * Compose-хуками управления модалками.
 */

internal suspend fun loadReactionVoters(messageId: Long): List<ReactionVoter>? = withContext(Dispatchers.IO) {
    try {
        val resp = ApiClient.getReactions(messageId)
        if (!resp.optBoolean("ok", false)) return@withContext null
        val d = resp.optJSONObject("data") ?: return@withContext null
        val votersObj = d.optJSONObject("voters") ?: return@withContext emptyList()
        val list = mutableListOf<ReactionVoter>()
        val emojiKeys = votersObj.keys()
        while (emojiKeys.hasNext()) {
            val emoji = emojiKeys.next()
            val arr = votersObj.optJSONArray(emoji) ?: continue
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                val name = v.optString("full_name").ifBlank { v.optString("user_login") }
                list += ReactionVoter(
                    name = name,
                    emoji = emoji,
                    time = formatDate(v.optString("created_at")),
                )
            }
        }
        list.sortedBy { it.emoji }
    } catch (_: Exception) { null }
}

internal suspend fun loadNewsReaders(messageId: Long): ReadersData? = withContext(Dispatchers.IO) {
    try {
        val resp = ApiClient.getNewsReaders(messageId)
        if (!resp.optBoolean("ok", false)) return@withContext null
        val d = resp.optJSONObject("data") ?: return@withContext null
        val readArr = d.optJSONArray("read_users") ?: JSONArray()
        val unreadArr = d.optJSONArray("unread_users") ?: JSONArray()
        val read = (0 until readArr.length()).mapNotNull { i ->
            val o = readArr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("full_name").ifBlank { o.optString("user_login") }
            ReaderMock(name, formatDate(o.optString("read_at")))
        }
        val unread = (0 until unreadArr.length()).mapNotNull { i ->
            val o = unreadArr.optJSONObject(i) ?: return@mapNotNull null
            o.optString("full_name").ifBlank { o.optString("user_login") }.takeIf { it.isNotBlank() }
        }
        ReadersData(read = read, unread = unread)
    } catch (_: Exception) { null }
}

internal suspend fun loadPollStats(pollId: Long): PollStatsData? = withContext(Dispatchers.IO) {
    try {
        val resp = ApiClient.getPollStats(pollId)
        if (!resp.optBoolean("ok", false)) return@withContext null
        val d = resp.optJSONObject("data") ?: return@withContext null
        val optionsArr = d.optJSONArray("options") ?: JSONArray()
        val options = (0 until optionsArr.length()).mapNotNull { i ->
            val o = optionsArr.optJSONObject(i) ?: return@mapNotNull null
            PollStatsOption(
                text = o.optString("option_text"),
                votesCount = o.optInt("votes_count", 0),
            )
        }
        val votersArr = d.optJSONArray("voters") ?: JSONArray()
        val voters = (0 until votersArr.length()).mapNotNull { i ->
            val o = votersArr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("full_name").ifBlank { o.optString("user_login") }
            val selectedArr = o.optJSONArray("selected_option_texts") ?: JSONArray()
            val selected = (0 until selectedArr.length()).map { selectedArr.optString(it, "") }
                .filter { it.isNotBlank() }
            PollVoter(
                name = name,
                votedAt = formatDate(o.optString("voted_at")),
                selected = selected,
            )
        }
        val nonVotersArr = d.optJSONArray("non_voters") ?: JSONArray()
        val nonVoters = (0 until nonVotersArr.length()).mapNotNull { i ->
            val o = nonVotersArr.optJSONObject(i) ?: return@mapNotNull null
            o.optString("full_name").ifBlank { o.optString("user_login") }.takeIf { it.isNotBlank() }
        }
        val totalVoters = d.optInt("total_voters", 0)
        val totalSelections = d.optInt("total_selections", 0)
        val totalAudience = voters.size + nonVoters.size
        PollStatsData(
            totalVoters = totalVoters,
            totalSelections = totalSelections,
            totalAudience = totalAudience,
            options = options,
            voters = voters,
            nonVoters = nonVoters,
        )
    } catch (_: Exception) { null }
}
