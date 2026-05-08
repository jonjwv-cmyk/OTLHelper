package com.example.otlhelper.domain.features

/**
 * Server-driven feature flags (§3.15.a.Е паспорта v3).
 *
 * Сервер возвращает массив `features: {...}` в ответе `login` и `me`.
 * Developer может включать/отключать функции для всех или подмножества пользователей
 * без релиза клиента.
 *
 * **Принцип клиента:** UI **всегда** проверяет флаг перед показом фичи.
 * Если сервер не прислал поле — используется safe default (консервативно —
 * включено для уже-обычных функций, выключено для новых/опасных).
 *
 * Пакет чистый Kotlin, без Android-импортов — готов к KMP.
 */
data class Features(
    /** Создание опросов (admin/developer). Дефолт: включено — фича давно работает. */
    val pollsEnabled: Boolean = true,
    /** Загрузка файлов-вложений к сообщениям/новостям. Дефолт: включено. */
    val fileUploadEnabled: Boolean = true,
    /** Приоритет urgent для новостей (§3.15.a.Б). Дефолт: выключено до рассылки. */
    val urgentNewsEnabled: Boolean = false,
    /** Биометрический замок при входе (§3.15.a.Д). Дефолт: выключено. */
    val biometricLockEnabled: Boolean = false,
    /** Реакции на сообщения/новости (§3.15.a.А). Дефолт: выключено. */
    val reactionsEnabled: Boolean = false,
    /** Упоминания @login (§3.15.a.А). Дефолт: выключено. */
    val mentionsEnabled: Boolean = false,
    /** Редактирование сообщений в 24ч окне (§3.15.a.А). Дефолт: выключено. */
    val editMessagesEnabled: Boolean = false,
    /** Отложенная отправка (§3.15.a.А). Дефолт: выключено. */
    val scheduledSendEnabled: Boolean = false,
    /** Full-text search по истории чата/ленты (§3.15.a.Г). Дефолт: выключено. */
    val fullTextSearchEnabled: Boolean = false,
    /** WebSocket-канал для presence/typing (§3.7 + §3.15.a.А). Дефолт: выключено. */
    val webSocketEnabled: Boolean = false,
) {
    companion object {
        /** Дефолтный снапшот для случая когда сервер флаги не прислал. */
        val DEFAULT: Features = Features()
    }
}

/**
 * Универсальный геттер — читает булевый флаг по имени. Удобно для случаев
 * когда фича добавляется на сервере раньше, чем для неё появляется поле в
 * [Features] (graceful forward compat, §3.15.a.З).
 *
 * Для известных полей — делегирует на соответствующий property; для неизвестных —
 * возвращает [default]. Имена совпадают с серверным wire-format.
 */
fun Features.isEnabled(name: String, default: Boolean = false): Boolean = when (name) {
    "polls_enabled" -> pollsEnabled
    "file_upload_enabled" -> fileUploadEnabled
    "urgent_news" -> urgentNewsEnabled
    "biometric_lock" -> biometricLockEnabled
    "reactions" -> reactionsEnabled
    "mentions" -> mentionsEnabled
    "edit_messages" -> editMessagesEnabled
    "scheduled_send" -> scheduledSendEnabled
    "fts_search" -> fullTextSearchEnabled
    "websocket" -> webSocketEnabled
    else -> default
}
