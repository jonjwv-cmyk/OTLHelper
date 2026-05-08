package com.example.otlhelper.domain.model

/**
 * Фиксированный набор реакций (§3.15.a.А паспорта v3).
 * Изменение этого списка требует синхронного обновления ALLOWED_EMOJIS
 * на сервере (`handlers-reactions.js`).
 */
object Reactions {
    val ALLOWED: List<String> = listOf("👍", "❤️", "😂", "🎉", "✅")
}
