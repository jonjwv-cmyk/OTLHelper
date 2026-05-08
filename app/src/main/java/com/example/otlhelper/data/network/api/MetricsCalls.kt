package com.example.otlhelper.data.network.api

import com.example.otlhelper.shared.api.ApiActions
import com.example.otlhelper.shared.api.ApiFields
import org.json.JSONArray
import org.json.JSONObject

/**
 * §TZ-CLEANUP-2026-04-25 — observability/metrics zone:
 *   • [logErrors] / [logActivity] — батч-сабмит crash logs / user_activity;
 *   • [getAppStats] / [getAppErrors] — developer-дайджест (роль gated на сервере);
 *   • [logMetrics] — батч сетевых метрик (latency / status / errors);
 *   • [getNetworkStats] — агрегированные сетевые метрики для AppStatsDialog.
 *
 * Реализация — в [MetricsCallsImpl].
 */
interface MetricsCalls {
    /**
     * SF-2026 §3.14, Phase 11: отправка батча крэшей/ошибок в `app_errors`.
     * Принимает JSONArray записей вида
     * `[ { occurred_at, error_class, error_message, stack_trace, platform, app_version, os_version, device_model, device_abi }, ... ]`.
     */
    fun logErrors(errors: JSONArray): JSONObject

    /**
     * SF-2026 §3.14, Phase 11: батч событий активности в `user_activity`.
     * Принимает JSONArray записей `[ { event_type, device_id, payload, occurred_at }, ... ]`.
     */
    fun logActivity(events: JSONArray): JSONObject

    /**
     * SF-2026 §3.13, Phase 10: developer-дайджест активности приложения.
     * Требует роль superadmin/developer на сервере.
     */
    fun getAppStats(sinceDays: Int = 7): JSONObject

    /**
     * Список последних ошибок из `app_errors` — для детализации при клике
     * на бейдж «Ошибки» в AppStatsDialog. Возвращает user_login,
     * app_version, error_class, error_message, device_model, occurred_at.
     */
    fun getAppErrors(sinceDays: Int = 7, limit: Int = 50): JSONObject

    /**
     * Batch-логирование сетевых метрик (2026-04 observability).
     * Не пишет себе же в метрики — `NetworkMetricsBuffer.record` фильтрует
     * `action=log_metrics`.
     */
    fun logMetrics(
        metrics: JSONArray,
        appVersion: String,
        osVersion: String,
        platform: String,
    ): JSONObject

    /** Developer-only: агрегация network-метрик для AppStatsDialog секции «Сеть». */
    fun getNetworkStats(sinceDays: Int = 1): JSONObject
}

internal class MetricsCallsImpl(private val gateway: ApiGateway) : MetricsCalls {

    override fun logErrors(errors: JSONArray): JSONObject =
        gateway.request(ApiActions.LOG_ERRORS) { put(ApiFields.ERRORS, errors) }

    override fun logActivity(events: JSONArray): JSONObject =
        gateway.request(ApiActions.LOG_ACTIVITY) { put(ApiFields.EVENTS, events) }

    override fun getAppStats(sinceDays: Int): JSONObject =
        gateway.request(ApiActions.GET_APP_STATS) { put(ApiFields.SINCE_DAYS, sinceDays) }

    override fun getAppErrors(sinceDays: Int, limit: Int): JSONObject =
        gateway.request(ApiActions.GET_APP_ERRORS) {
            put(ApiFields.SINCE_DAYS, sinceDays)
            put(ApiFields.LIMIT, limit)
        }

    override fun logMetrics(
        metrics: JSONArray,
        appVersion: String,
        osVersion: String,
        platform: String,
    ): JSONObject = gateway.request(ApiActions.LOG_METRICS) {
        put(ApiFields.METRICS, metrics)
        put(ApiFields.APP_VERSION, appVersion)
        put(ApiFields.OS_VERSION, osVersion)
        put(ApiFields.PLATFORM, platform)
    }

    override fun getNetworkStats(sinceDays: Int): JSONObject =
        gateway.request(ApiActions.GET_NETWORK_STATS) {
            put(ApiFields.SINCE_DAYS, sinceDays)
        }
}
