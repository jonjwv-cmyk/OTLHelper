package com.example.otlhelper.domain.features

import org.json.JSONObject

/**
 * Парсер серверных feature-flags из JSON. Tolerant: незнакомые/отсутствующие
 * поля игнорируются, остаются default'ы из [Features.DEFAULT] (§3.15.a.З).
 *
 * Серверный контракт: `features: { "polls_enabled": true, ... }` в ответе
 * `login` / `me`.
 *
 * ⚠ Использует `org.json.JSONObject` — для KMP-перехода (Фаза 13) этот
 * маппер надо будет заменить на Kotlinx Serialization (`@Serializable data class`).
 */
object FeaturesParser {

    /** Построить [Features] из JSON. Null/пустой/мусор → [Features.DEFAULT]. */
    fun parse(json: JSONObject?): Features {
        if (json == null) return Features.DEFAULT
        val base = Features.DEFAULT
        return Features(
            pollsEnabled = json.optBoolean("polls_enabled", base.pollsEnabled),
            fileUploadEnabled = json.optBoolean("file_upload_enabled", base.fileUploadEnabled),
            urgentNewsEnabled = json.optBoolean("urgent_news", base.urgentNewsEnabled),
            biometricLockEnabled = json.optBoolean("biometric_lock", base.biometricLockEnabled),
            reactionsEnabled = json.optBoolean("reactions", base.reactionsEnabled),
            mentionsEnabled = json.optBoolean("mentions", base.mentionsEnabled),
            editMessagesEnabled = json.optBoolean("edit_messages", base.editMessagesEnabled),
            scheduledSendEnabled = json.optBoolean("scheduled_send", base.scheduledSendEnabled),
            fullTextSearchEnabled = json.optBoolean("fts_search", base.fullTextSearchEnabled),
            webSocketEnabled = json.optBoolean("websocket", base.webSocketEnabled),
        )
    }

    /** Серилизация в JSON для кэширования в SharedPreferences. */
    fun serialize(features: Features): JSONObject = JSONObject().apply {
        put("polls_enabled", features.pollsEnabled)
        put("file_upload_enabled", features.fileUploadEnabled)
        put("urgent_news", features.urgentNewsEnabled)
        put("biometric_lock", features.biometricLockEnabled)
        put("reactions", features.reactionsEnabled)
        put("mentions", features.mentionsEnabled)
        put("edit_messages", features.editMessagesEnabled)
        put("scheduled_send", features.scheduledSendEnabled)
        put("fts_search", features.fullTextSearchEnabled)
        put("websocket", features.webSocketEnabled)
    }
}
