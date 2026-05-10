package com.example.otlhelper.desktop.sheets

import com.example.otlhelper.desktop.data.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

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
     * §0.11.8 — RACE FIX: paired alive-wait pattern. Раньше первый
     * polling ответ alive=false → exit как "finished". Но Apps Script
     * после trigger в течение 1-3 сек ещё не успел поставить флаг
     * status='running' → возвращал status='idle' → парсилось как
     * alive=false → клиент думал "уже всё" → котик уходил пока Apps
     * Script только стартовал. Юзер: «макрос план перенс данные но
     * странно некорректно кот раньше времени ушел».
     *
     * Фикс: ждём сначала alive=true (max graceAttempts), потом alive=false.
     * Если alive=true так и не пришёл за grace period — exit как done
     * (Apps Script возможно завершился очень быстро или не запустился).
     *
     * Returns true когда action завершён, false если timeout.
     */
    suspend fun pollUntilDoneViaServer(
        actionId: String,
        intervalMs: Long = 2_000,
        maxAttempts: Int = 90,
        graceAttempts: Int = 5,  // первые 10 сек ждём alive=true до считания alive=false как done
    ): Boolean = withContext(Dispatchers.IO) {
        var seenAlive = false
        repeat(maxAttempts) { attempt ->
            kotlinx.coroutines.delay(intervalMs)
            val alive = runCatching {
                val resp = ApiClient.request("check_sheet_action_status") {
                    put("action_id", actionId)
                }
                if (!resp.optBoolean("ok", false)) return@runCatching true
                resp.optBoolean("alive", true)
            }.getOrDefault(true)

            if (alive) {
                seenAlive = true
                return@repeat
            }
            // alive=false. Exit только если уже видели alive=true (нормальное
            // завершение работы) ИЛИ grace period истёк (Apps Script не
            // отвечает / завершился раньше первого polling).
            if (seenAlive || attempt >= graceAttempts) {
                System.err.println("[OTLD][poll] $actionId finished after ${attempt + 1} attempts (seenAlive=$seenAlive)")
                return@withContext true
            }
            // Иначе ждём — возможно Apps Script ещё не успел поставить running
        }
        System.err.println("[OTLD][poll] $actionId timeout after $maxAttempts attempts (seenAlive=$seenAlive)")
        false
    }

    // §TZ-DESKTOP-0.10.13 — удалены legacy функции `runScript(url)` и
    // `pollUntilDone(statusUrl)`. Они принимали Apps Script URL напрямую
    // от клиента, что после миграции на server-side registry стало
    // dead-кодом (никто не вызывал). Сейчас весь flow только через
    // runViaServer + pollUntilDoneViaServer (action_id, не URL).
}
