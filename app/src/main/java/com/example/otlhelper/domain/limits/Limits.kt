package com.example.otlhelper.domain.limits

/**
 * Константы бизнес-правил SF-2026.
 *
 * Источник истины — сервер: если сервер пришлёт конфликтующее значение, побеждает сервер.
 * Эти значения — клиентские defaults / guards до того как сервер подтвердил своё значение.
 *
 * Пакет чистый Kotlin, без Android-импортов.
 */
object Limits {

    // ── Ленты / сообщения ────────────────────────────────────────────────────
    /** Максимум одновременно закреплённых новостей / опросов (§3.5). */
    const val MAX_PINNED: Int = 3

    /** Максимум файлов на одно сообщение / новость / опрос (§3.9). */
    const val MAX_ATTACHMENTS_PER_MESSAGE: Int = 5

    /** Окно редактирования сообщения / новости / опроса после создания (§3.15.a.А). */
    const val EDIT_WINDOW_MS: Long = 24L * 60L * 60L * 1_000L // 24 часа

    /** Окно undo для soft-delete (§3.15.a.А). */
    const val UNDO_DELETE_WINDOW_MS: Long = 7_000L

    // ── Realtime ─────────────────────────────────────────────────────────────
    /** Интервал heartbeat пока WebSocket недоступен (§3.7). */
    const val HEARTBEAT_INTERVAL_MS: Long = 25_000L

    /** Время жизни «печатает…» после последнего keystroke (§3.15.a.А). */
    const val TYPING_INDICATOR_TTL_MS: Long = 3_000L

    /** Сколько ждать до падения в heartbeat+FCM fallback если WebSocket молчит. */
    const val WS_SILENT_FALLBACK_MS: Long = 60_000L

    /** Максимальная задержка между попытками WebSocket reconnect (экспоненциальный backoff). */
    const val WS_RECONNECT_MAX_DELAY_MS: Long = 30_000L

    /** WebSocket heartbeat-ping. */
    const val WS_PING_INTERVAL_MS: Long = 20_000L

    // ── Черновики (§3.15.a.А) ────────────────────────────────────────────────
    /** Интервал auto-save черновика. */
    const val DRAFT_AUTOSAVE_MS: Long = 2_000L

    // ── Поиск (§3.15.a.Г) ────────────────────────────────────────────────────
    /** Дебаунс ввода поискового запроса. */
    const val SEARCH_DEBOUNCE_MS: Long = 200L

    /** Максимальное расстояние Левенштейна для fuzzy-match по МОЛ. */
    const val SEARCH_FUZZY_MAX_DISTANCE: Int = 2

    /** Размер top-N подсказок по префиксу. */
    const val SEARCH_SUGGESTIONS_TOP_N: Int = 5

    // ── Loading states UX (§3.15.a.Л) ────────────────────────────────────────
    /** Не показывать спиннер если ответ пришёл раньше этого порога. */
    const val LOADING_SPINNER_DELAY_MS: Long = 250L

    /** Показывать текст «Загружаем…» в skeleton после этого порога. */
    const val LOADING_TEXT_DELAY_MS: Long = 2_000L

    /** Показывать кнопку «Отменить» в скелетоне после этого порога. */
    const val LOADING_CANCEL_DELAY_MS: Long = 10_000L

    // ── Кэш (§3.11, §3.9) ────────────────────────────────────────────────────
    /** Максимальный размер зашифрованного кэша вложений. */
    const val ENCRYPTED_CACHE_MAX_BYTES: Long = 500L * 1024L * 1024L // 500 MB
}
