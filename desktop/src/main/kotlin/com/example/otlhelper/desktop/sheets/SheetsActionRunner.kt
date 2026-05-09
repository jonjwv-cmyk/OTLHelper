package com.example.otlhelper.desktop.sheets

import com.example.otlhelper.desktop.data.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * §TZ-DESKTOP 0.4.x round 11 — HTTP client для запуска Apps Script
 * Web Apps. Простой GET → follow redirects → вернуть success/failure.
 *
 * **Response**: Apps Script Web App с `?action=run` обычно возвращает
 * 200 OK с текстовым/JSON content (зависит от скрипта). Ошибки скрипта
 * могут быть 200 со специфическим body — сейчас не парсим.
 *
 * **Timeouts**: connect 30s, read 5min — Apps Script долго пишет
 * (может execute 1-3 минуты на больших спредшитах).
 *
 * **Single shared client** — переиспользует connection pool. OkHttp
 * thread-safe.
 */
object SheetsActionRunner {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * §TZ-DESKTOP-0.10.13 — выполнить через VPS-сервер. Клиент шлёт ТОЛЬКО
     * `action_id` + опциональный `password` (если action.requiresPassword).
     * Сервер сам lookup'ит scriptUrl в `sheets-registry.js`, валидирует
     * password (если требуется), бродкастит WS lock acquire/release,
     * потом fetch'ит Apps Script URL. URL никогда не покидает сервер.
     *
     * До 0.10.12 клиент шлёт `script_url` напрямую — сервер сохраняет
     * backward-compat (старые клиенты продолжают работать), но новые
     * (этот код) шлют только action_id.
     *
     * Returns true если script успешно отработал. Возможные ошибки:
     *   wrong_password → юзер ввёл не тот пароль (server валидирует)
     *   unknown_action_id → action нет в server registry
     *   http_NNN → Apps Script вернул не 2xx
     */
    suspend fun runViaServer(
        action: SheetAction,
        userName: String,
        tabName: String,
        lockedTabs: List<String>,
        password: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            ApiClient.request("run_script") {
                put("action_id", action.id)
                put("action_label", action.label)
                put("user_name", userName)
                put("tab_name", tabName)
                put("locked_tabs", JSONArray(lockedTabs))
                if (!password.isNullOrEmpty()) {
                    put("password", password)
                }
            }.optBoolean("ok", false)
        }.getOrElse { e ->
            System.err.println("[OTLD][script] runViaServer failed: ${e.message}")
            false
        }
    }

    /**
     * §TZ-DESKTOP-0.10.13 — Server-side polling action status. Заменяет
     * старый `pollUntilDone(statusUrl, ...)` — клиент больше не знает
     * statusUrl, шлёт action_id, сервер сам fetch'ит из своей registry.
     *
     * Returns true когда action завершён (alive=false), false если timeout.
     */
    suspend fun pollUntilDoneViaServer(
        actionId: String,
        intervalMs: Long = 2_000,
        maxAttempts: Int = 90,
    ): Boolean = withContext(Dispatchers.IO) {
        repeat(maxAttempts) { attempt ->
            kotlinx.coroutines.delay(intervalMs)
            val alive = runCatching {
                val resp = ApiClient.request("check_sheet_action_status") {
                    put("action_id", actionId)
                }
                if (!resp.optBoolean("ok", false)) return@runCatching true
                resp.optBoolean("alive", true)
            }.getOrDefault(true)
            if (!alive) {
                System.err.println("[OTLD][poll] $actionId finished after ${attempt + 1} attempts")
                return@withContext true
            }
        }
        System.err.println("[OTLD][poll] $actionId timeout after $maxAttempts attempts")
        false
    }

    /**
     * Старый прямой GET — оставлен на случай fallback (дев-режим без
     * сервера или offline). Не используется в основном flow.
     */
    suspend fun runScript(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val ok = response.isSuccessful
                System.err.println("[OTLD][script] $url → HTTP ${response.code} (${if (ok) "ok" else "fail"})")
                ok
            }
        }.getOrElse { e ->
            System.err.println("[OTLD][script] $url → exception: ${e.message}")
            false
        }
    }

    /**
     * Polling-helper для actions с `statusUrl` (alive endpoint). Дёргает
     * URL каждые [intervalMs] и возвращает когда response body ≠ "alive"
     * (или когда максимум [maxAttempts] исчерпан — fallback timeout).
     */
    suspend fun pollUntilDone(
        statusUrl: String,
        intervalMs: Long = 2_000,
        maxAttempts: Int = 90,
    ): Boolean = withContext(Dispatchers.IO) {
        repeat(maxAttempts) { attempt ->
            kotlinx.coroutines.delay(intervalMs)
            val alive = runCatching {
                val request = Request.Builder().url(statusUrl).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching false
                    val body = response.body?.string()?.trim()?.lowercase() ?: ""
                    // Apps Script returns "true"/"alive"/"running" пока работает,
                    // "false"/"done"/"completed" по завершении. Считаем что
                    // любое содержащее "true" / "alive" / "1" — alive.
                    body.contains("true") || body.contains("alive") || body == "1"
                }
            }.getOrDefault(true)  // network error → assume still running
            if (!alive) {
                System.err.println("[OTLD][poll] $statusUrl finished after ${attempt + 1} attempts")
                return@withContext true
            }
        }
        System.err.println("[OTLD][poll] $statusUrl timeout after $maxAttempts attempts")
        false
    }
}
