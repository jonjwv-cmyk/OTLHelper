package com.example.otlhelper.shared.api

/**
 * §TZ-CLEANUP-2026-04-25 — type-safe envelope для ответов сервера.
 *
 * Server contract: каждый ответ имеет shape `{ ok: Boolean, error?: String, data?: ... }`.
 * До этого Android/Desktop клиенты парсили это руками каждый раз —
 * `if (resp.optBoolean("ok")) { ... } else { ... }` — что
 * подвержено typo, забытому error-handling, etc.
 *
 * Теперь новый код может вернуть [ApiResult] вместо raw JSONObject:
 *
 * ```kotlin
 * // В FeedCallsImpl или другом zone-делегате:
 * fun getNewsTyped(limit: Int): ApiResult<JSONObject> {
 *     val resp = gateway.request(ApiActions.GET_NEWS) { put(LIMIT, limit) }
 *     return ApiResult.fromJson(resp)
 * }
 *
 * // В caller'е:
 * when (val r = ApiClient.getNewsTyped(20)) {
 *     is ApiResult.Ok -> render(r.data)
 *     is ApiResult.Err -> showError(r.code, r.message)
 * }
 * ```
 *
 * **Pure Kotlin/JVM — никаких Android/Compose/JSON зависимостей.**
 * Конверсия из/в JSONObject делается в Android/Desktop конкретных
 * клиентах (где `org.json` доступен).
 */
sealed class ApiResult<out T> {
    /** Успех. [data] — payload (тип специфичен для каждого endpoint'а). */
    data class Ok<T>(val data: T) : ApiResult<T>()

    /**
     * Ошибка.
     * @param code сервеный error-code (`wrong_password`, `user_not_found`, etc).
     * @param message опциональный человекочитаемый текст (если сервер прислал).
     */
    data class Err(val code: String, val message: String? = null) : ApiResult<Nothing>()

    val isOk: Boolean get() = this is Ok
    val errorCode: String? get() = (this as? Err)?.code

    /** Map data при успехе; ошибки пробрасываются как есть. */
    inline fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Ok -> Ok(transform(data))
        is Err -> this
    }

    /** Получить data или null при ошибке. */
    fun getOrNull(): T? = (this as? Ok)?.data

    /** Получить data или fallback при ошибке. */
    fun getOrElse(fallback: () -> @UnsafeVariance T): T = when (this) {
        is Ok -> data
        is Err -> fallback()
    }
}
