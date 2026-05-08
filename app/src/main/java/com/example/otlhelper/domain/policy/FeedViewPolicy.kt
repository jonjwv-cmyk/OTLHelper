package com.example.otlhelper.domain.policy

import com.example.otlhelper.domain.model.FeedDisplayType
import com.example.otlhelper.domain.model.FeedItemView
import com.example.otlhelper.domain.model.Role
import com.example.otlhelper.domain.permissions.Permissions
import org.json.JSONObject

/**
 * Единственное место, где «сырой JSON + роль» превращаются в готовый [FeedItemView] (§4.2).
 *
 * Клиент **не принимает решений о бизнес-логике** самостоятельно — политика здесь:
 * что показывать, какие флаги выставить, какой заголовок.
 *
 * Фаза 2: строит view на клиенте поверх JSONObject.
 * Фаза 7: если сервер начинает отдавать эти поля — становится passthrough
 * (метод читает уже готовые флаги из JSON, а не вычисляет их заново).
 *
 * ⚠ Использует `org.json.JSONObject` — не чистый Kotlin. Для KMP-перехода
 * (Фаза 13) эту зависимость придётся заменить на промежуточную data class.
 */
object FeedViewPolicy {

    /**
     * Построить [FeedItemView] для карточки ленты.
     *
     * @param item сырой JSON от сервера / из кэша
     * @param role канонизированная роль текущего пользователя
     */
    fun toView(item: JSONObject, role: Role): FeedItemView {
        // ── display_type ────────────────────────────────────────────────────
        // Phase 7: будущий сервер пришлёт `display_type` напрямую.
        // Сейчас выводим из `kind`/`type` + fallback на наличие `poll` объекта.
        val serverDisplayType = item.optString("display_type", "")
        val kindRaw = item.optString("kind", "").ifBlank { item.optString("type", "") }
        val displayType = when (serverDisplayType.ifBlank { kindRaw }.lowercase()) {
            "poll", "news_poll" -> FeedDisplayType.POLL
            "news" -> FeedDisplayType.NEWS
            "" -> if (item.optJSONObject("poll") != null) FeedDisplayType.POLL else FeedDisplayType.NEWS
            else -> FeedDisplayType.UNKNOWN
        }

        // ── is_pinned / is_important ─────────────────────────────────────────
        val isPinned = item.optInt("is_pinned", 0) != 0
        // Сервер может явно передать is_important; пока привязано к is_pinned (§3.4).
        val isImportant = item.optInt("is_important", if (isPinned) 1 else 0) != 0

        // ── headerLabel ──────────────────────────────────────────────────────
        // Предпочитаем серверное pinned_label_suffix если есть; иначе «(важно)» по §3.4.
        val typeLabel = when (displayType) {
            FeedDisplayType.POLL -> "ОПРОС"
            FeedDisplayType.NEWS -> "НОВОСТЬ"
            FeedDisplayType.UNKNOWN -> "ЗАПИСЬ"
        }
        val suffix = item.optString(
            "pinned_label_suffix",
            if (isPinned || isImportant) "(важно)" else ""
        )
        val headerLabel = if (suffix.isNotBlank()) "$typeLabel $suffix" else typeLabel

        // ── Автор ────────────────────────────────────────────────────────────
        val authorLabel = item.optString(
            "author_label",
            item.optString("sender_name", item.optString("sender_login", ""))
        )

        return FeedItemView(
            id = item.optLong("id", 0L),
            localItemId = item.optString("local_item_id", ""),
            displayType = displayType,
            headerLabel = headerLabel,
            authorLabel = authorLabel,
            createdAt = item.optString("created_at", ""),
            isPinned = isPinned,
            isImportant = isImportant,
            // Серверные флаги имеют приоритет; иначе — клиентская политика на основе роли.
            canSeeVoteCounts = optBoolean(item, "show_vote_counts")
                ?: Permissions.canSeeVoteCounts(role),
            canOpenMenu = optBoolean(item, "can_see_menu")
                ?: Permissions.canOpenCardMenu(role),
            canPin = optBoolean(item, "can_pin")
                ?: Permissions.canPin(role),
            canSeeFullStats = optBoolean(item, "can_see_stats")
                ?: Permissions.canSeeFullStats(role),
            showAuthor = optBoolean(item, "show_author")
                ?: Permissions.canSeeAuthor(role),
            // Сервер передаёт `edited_at` когда запись хоть раз правилась.
            isEdited = item.optString("edited_at", "").isNotBlank(),
            // Phase 12b: aggregate реакций от сервера (пустой если нет записей).
            reactionsAggregate = item.optJSONObject("reactions")?.let { obj ->
                buildMap {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val n = obj.optInt(k, 0)
                        if (n > 0) put(k, n)
                    }
                }
            } ?: emptyMap(),
            myReactions = item.optJSONArray("my_reactions")?.let { arr ->
                buildSet { for (i in 0 until arr.length()) add(arr.optString(i)) }
            } ?: emptySet(),
        )
    }

    /** Чтение boolean с возможностью отличить «не прислали» (null) от «false». */
    private fun optBoolean(item: JSONObject, key: String): Boolean? =
        if (item.has(key) && !item.isNull(key)) item.optBoolean(key) else null
}
