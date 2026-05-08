package com.example.otlhelper.desktop.core.metrics

import com.example.otlhelper.desktop.BuildInfo
import com.example.otlhelper.desktop.data.network.ApiClient
import com.example.otlhelper.shared.api.ApiActions
import com.example.otlhelper.shared.api.ApiFields
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * §TZ-2.4.0 — desktop mirror of `app/.../core/metrics/NetworkMetricsBuffer.kt`.
 *
 * Закрывает observability gap (диагностика 2026-04-30 показала: desktop НЕ
 * льёт network_metrics; не видим Mac/Win latency/error rates).
 *
 * Каждый запрос → 1 строка в очереди. Flush раз в 60с или по 30 строкам.
 * Только метаданные (action, duration, status, error code) — никаких body
 * или query parameters.
 */
object NetworkMetricsBuffer {

    private data class Metric(
        val action: String,
        val durationMs: Long,
        val httpStatus: Int,
        val ok: Boolean,
        val errorCode: String,
    )

    private val queue = ConcurrentLinkedQueue<Metric>()
    private val size = AtomicInteger(0)
    private val flushMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var periodicJob: Job? = null

    fun start() {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushIfAny()
            }
        }
    }

    fun record(
        action: String,
        durationMs: Long,
        httpStatus: Int,
        ok: Boolean,
        errorCode: String = "",
    ) {
        if (!ApiClient.hasToken()) return  // до логина не льём
        if (action.isBlank()) return
        if (action == ApiActions.LOG_METRICS) return  // anti-loop
        queue.offer(Metric(
            action = action.take(60),
            durationMs = durationMs,
            httpStatus = httpStatus,
            ok = ok,
            errorCode = errorCode.take(40),
        ))
        if (size.incrementAndGet() >= FLUSH_AT_SIZE) {
            scope.launch { flushIfAny() }
        }
    }

    suspend fun flushIfAny() {
        if (size.get() == 0) return
        flushMutex.withLock {
            val batch = mutableListOf<Metric>()
            while (batch.size < MAX_BATCH_SIZE) {
                val m = queue.poll() ?: break
                batch.add(m)
                size.decrementAndGet()
            }
            if (batch.isEmpty()) return

            try {
                val arr = JSONArray().apply {
                    batch.forEach { m ->
                        put(JSONObject().apply {
                            put("action", m.action)
                            put("duration_ms", m.durationMs)
                            put("http_status", m.httpStatus)
                            put("ok", m.ok)
                            put("network_type", "")  // desktop не определяет
                            if (m.errorCode.isNotBlank()) put("error_code", m.errorCode)
                        })
                    }
                }
                ApiClient.request(ApiActions.LOG_METRICS) {
                    put(ApiFields.METRICS, arr)
                    put(ApiFields.APP_VERSION, BuildInfo.VERSION)
                    put("os_version", System.getProperty("os.version", ""))
                    put(ApiFields.PLATFORM, ApiFields.PLATFORM_DESKTOP)
                }
            } catch (_: Exception) {
                // Сеть мигнула — батч потерян. Не критично, observability.
            }
        }
    }

    fun stop() {
        periodicJob?.cancel()
    }

    private const val FLUSH_AT_SIZE = 30
    private const val FLUSH_INTERVAL_MS = 60_000L
    private const val MAX_BATCH_SIZE = 100
}
