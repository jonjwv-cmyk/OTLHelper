package com.example.otlhelper.data.network.api

import org.json.JSONObject

/**
 * §TZ-CLEANUP-2026-04-25 — internal transport interface between [ApiClient]
 * facade и его zone-делегатами (AuthCalls, FeedCalls, ... в данном пакете).
 *
 * **Контракт:** делегат не знает, как именно request попадает в сеть —
 * он просто строит payload через `JSONObject.() -> Unit` lambda и получает
 * результат. Это позволяет facade-у централизованно управлять:
 *   • header'ами (Authorization, Cache-Control)
 *   • token/device_id инжекцией
 *   • timing/telemetry hook'ом
 *   • error logging'ом
 *
 * Делегат остаётся узко-специализированным: формирует action body, читает
 * результат. Без сетевой/auth/log логики.
 */
internal interface ApiGateway {
    fun request(action: String, build: JSONObject.() -> Unit = {}): JSONObject
}
