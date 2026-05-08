package com.example.otlhelper.core.telemetry

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.example.otlhelper.BuildConfig
import com.example.otlhelper.data.pending.PendingAction
import com.example.otlhelper.data.repository.FeedRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SF-2026 §3.14 + §3.15.a.И — клиентская телеметрия.
 *
 * Лёгкий event-emitter: [event] пишет запись в оффлайн-очередь; `PendingActionFlusher`
 * доставляет её на сервер в таблицу `user_activity` при следующем heartbeat'е.
 * Для крэшей — [reportCrash] отправляет одиночную ошибку в `app_errors`.
 *
 * **Приватность (§3.14):** никогда не логируем тексты сообщений, пароли,
 * персональные данные. Только метаданные (screen name, query length, action kind).
 */
@Singleton
class Telemetry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val feedRepository: FeedRepository,
) {
    // Фоновой скоуп для неблокирующего enqueue — не хотим делать вызов Telemetry
    // suspend'ом, чтобы его можно было звать из любого места UI.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun nowUtc(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private val deviceId: String by lazy {
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (_: Exception) { "" }
    }

    /**
     * Запись события активности (`screen_open`, `search_query`, `message_sent`, `poll_voted`…).
     *
     * [payload] должен содержать только МЕТАДАННЫЕ, не сами данные. Например:
     *  - `screen_open → { "screen": "news" }`  — НЕ текст новости
     *  - `search_query → { "len": 7, "matched": 3 }` — НЕ строку запроса
     *  - `message_sent → { "receiver_role": "admin" }` — НЕ текст сообщения
     */
    /**
     * Лог латенси одного API-вызова. Вызывается автоматически из
     * [ApiClient] через глобальный hook (см. `OtlApp.onCreate`).
     * Пишется в `user_activity` с eventType=`slow_action` только для
     * запросов дольше [MIN_LATENCY_REPORT_MS] — это чтобы не флудить
     * успешными быстрыми вызовами, а видеть именно проблемы юзеров.
     */
    fun timing(action: String, durationMs: Long, ok: Boolean = true) {
        if (durationMs < MIN_LATENCY_REPORT_MS) return
        event("slow_action", mapOf(
            "action" to action,
            "duration_ms" to durationMs,
            "ok" to ok,
            "app_version" to BuildConfig.VERSION_NAME,
            "os" to "${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT}",
        ))
    }

    companion object {
        /** Запросы короче этого — не логируем (слишком много шума). */
        const val MIN_LATENCY_REPORT_MS = 2000L
    }

    fun event(eventType: String, payload: Map<String, Any?> = emptyMap()) {
        val payloadJson = JSONObject().apply {
            payload.forEach { (k, v) -> if (v != null) put(k, v) }
        }
        val action = PendingAction.LogActivity(
            occurredAt = nowUtc(),
            eventType = eventType,
            deviceId = deviceId,
            payload = payloadJson,
        )
        scope.launch {
            feedRepository.enqueuePendingAction(action.actionType, action.toJson(), action.entityKey)
        }
    }

    /**
     * Отправка неперехваченного исключения. Вызывается из [CrashHandler] —
     * прикладной код сам не должен звать этот метод напрямую, если только не
     * хочет залогировать ошибку, которую сам обрабатывает.
     */
    fun reportCrash(throwable: Throwable) {
        val action = PendingAction.LogError(
            occurredAt = nowUtc(),
            errorClass = throwable::class.java.name,
            errorMessage = (throwable.message ?: "").take(500),
            stackTrace = throwable.stackTraceToString().take(8000),
            platform = "android",
            appVersion = BuildConfig.VERSION_NAME,
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".take(100),
            deviceAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        )
        scope.launch {
            feedRepository.enqueuePendingAction(action.actionType, action.toJson(), action.entityKey)
        }
    }
}
