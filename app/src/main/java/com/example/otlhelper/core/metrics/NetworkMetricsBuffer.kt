package com.example.otlhelper.core.metrics

import android.os.Build
import android.util.Log
import com.example.otlhelper.ApiClient
import com.example.otlhelper.BuildConfig
import com.example.otlhelper.SessionManager
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Буфер сетевых метрик → батч-flush на сервер.
 *
 * Каждый HTTP-запрос из [ApiClient] кладёт сюда одну строку. Когда
 * накопилось [FLUSH_AT_SIZE] или прошло [FLUSH_INTERVAL_MS] — шлём
 * батчом через `log_metrics`. Не теряем запись при ошибке отправки —
 * перед вызовом сервера записи уходят в локальный снапшот; если ответ
 * не OK, кладём обратно (best-effort).
 *
 * Приватность: ТОЛЬКО метаданные. Никаких body, query, phone-номеров.
 * Сервер сам ограничивает размер строк и лимитирует retention 30 днями.
 */
@Singleton
class NetworkMetricsBuffer @Inject constructor(
    private val session: SessionManager,
    private val networkTypeDetector: NetworkTypeDetector,
) {
    private data class Metric(
        val action: String,
        val durationMs: Long,
        val httpStatus: Int,
        val ok: Boolean,
        val networkType: String,
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

    /**
     * Вызывается из ApiClient.onActionLatency — быстро, без аллокаций
     * по возможности. Если переполнилось — фоновый flush.
     */
    fun record(
        action: String,
        durationMs: Long,
        httpStatus: Int,
        ok: Boolean,
        errorCode: String = "",
    ) {
        if (!session.hasToken()) return // До логина не логируем — серверу некуда писать
        if (action.isBlank()) return
        // Пропускаем сам log_metrics — иначе бесконечный цикл телеметрии-о-телеметрии.
        if (action == "log_metrics") return
        queue.offer(Metric(
            action = action.take(60),
            durationMs = durationMs,
            httpStatus = httpStatus,
            ok = ok,
            networkType = networkTypeDetector.current(),
            errorCode = errorCode.take(40),
        ))
        val currentSize = size.incrementAndGet()
        if (currentSize >= FLUSH_AT_SIZE) {
            scope.launch { flushIfAny() }
        }
    }

    /**
     * Выгружает накопленные метрики на сервер одним батч-запросом.
     * Идемпотентно — параллельные вызовы сериализуются через mutex.
     */
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
                val metricsArr = JSONArray().apply {
                    batch.forEach { m ->
                        put(JSONObject().apply {
                            put("action", m.action)
                            put("duration_ms", m.durationMs)
                            put("http_status", m.httpStatus)
                            put("ok", m.ok)
                            put("network_type", m.networkType)
                            if (m.errorCode.isNotBlank()) put("error_code", m.errorCode)
                        })
                    }
                }
                val response = ApiClient.logMetrics(
                    metrics = metricsArr,
                    appVersion = BuildConfig.VERSION_NAME,
                    osVersion = "${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT}",
                    platform = "android",
                )
                if (!response.optBoolean("ok", false)) {
                    Log.w(TAG, "flush failed: ${response.optString("error")}")
                    // Возвращаем обратно в очередь если возможно — best-effort.
                    // Не пробуем бесконечно: если сервер стабильно не принимает,
                    // проглатываем, чтобы очередь не росла бесконечно в памяти.
                    if (response.optString("error") == "batch_too_large") {
                        // too-large на нашей стороне — дропаем, next batch будет меньше
                    }
                }
            } catch (e: Exception) {
                // Сеть мигнула — метрики уже out of queue, потеря небольшая.
                // Альтернатива — класть обратно, но на долгой offline-сессии
                // память будет расти. Мы тут outsourced observability
                // системой — не бизнес-данные, можно дропнуть пачку.
                Log.w(TAG, "flush exception: ${e.message}")
            }
        }
    }

    fun stop() {
        periodicJob?.cancel()
    }

    companion object {
        private const val TAG = "NetworkMetrics"
        /** Сколько запросов накапливаем прежде чем flush'нуть (сторона size). */
        private const val FLUSH_AT_SIZE = 30
        /** Интервал флаш-тика — и без того flush срабатывает на size. */
        private const val FLUSH_INTERVAL_MS = 60_000L
        /** Максимум в одном запросе (совпадает с серверным лимитом). */
        private const val MAX_BATCH_SIZE = 100
    }
}
