package com.example.otlhelper.domain.model

/**
 * Приоритет новости/опроса (§3.15.a.Б паспорта v3).
 *
 * - [Normal]    — обычный пуш (тихая доставка, без full-screen).
 * - [Important] — крупный бейдж «ВАЖНО», не пробивает DND.
 * - [Urgent]    — full-screen alert даже в DND/silent, канал `otl_urgent_v1`
 *                 с IMPORTANCE_MAX. Отправлять может только developer
 *                 (или admin с `allow_urgent` флагом).
 */
enum class NewsPriority {
    Normal,
    Important,
    Urgent;

    companion object {
        /** Парсер серверной строки приоритета (default — Normal). */
        fun fromString(raw: String?): NewsPriority = when (raw?.trim()?.lowercase()) {
            "urgent" -> Urgent
            "important" -> Important
            else -> Normal
        }
    }
}

/** Каноническое имя в wire-format (для отправки серверу). */
fun NewsPriority.wireName(): String = when (this) {
    NewsPriority.Normal -> "normal"
    NewsPriority.Important -> "important"
    NewsPriority.Urgent -> "urgent"
}

/** Локализованное отображаемое имя (RU). */
fun NewsPriority.displayName(): String = when (this) {
    NewsPriority.Normal -> "Обычный"
    NewsPriority.Important -> "Важный"
    NewsPriority.Urgent -> "Срочный"
}
