package com.example.otlhelper.core.telemetry

/**
 * §0.11.x — global hook для модулей **вне Hilt scope** (например ExoPlayer
 * DataSource'ы, которые создаются ExoPlayer factory без DI).
 *
 * `OtlApp.onCreate` устанавливает [emitter] на Hilt-инжектированный
 * [Telemetry.event]. Любой код может вызвать [TelemetryHook.event] — оно
 * forward'ит в Telemetry, или silently игнорит если hook ещё не установлен
 * (например на ранней стадии app boot).
 *
 * Безопасно вызывать с любого треда. Telemetry внутри сам делает enqueue
 * на IO-scope, не блокирует caller.
 *
 * Приватность: не логировать содержимое сообщений / пароли / личные данные.
 * Только метаданные (URL без query, размеры в байтах, error class names).
 */
object TelemetryHook {

    @Volatile
    var emitter: ((eventType: String, payload: Map<String, Any?>) -> Unit)? = null

    /** Forward event в Telemetry или silently skip если hook не установлен. */
    fun event(eventType: String, payload: Map<String, Any?> = emptyMap()) {
        runCatching { emitter?.invoke(eventType, payload) }
    }
}
