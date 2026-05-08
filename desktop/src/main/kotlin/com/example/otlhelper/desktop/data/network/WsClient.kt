package com.example.otlhelper.desktop.data.network

import com.example.otlhelper.desktop.data.security.DesktopWsCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * §TZ-DESKTOP-0.1.0 этап 4a — минимальный WS-клиент для desktop.
 *
 * Функции этой итерации:
 *   • Подключение к wss://api.otlhelper.com/ws через тот же OkHttp с
 *     VPS DNS override + cert trust.
 *   • crypto_init + encrypted hello, как Android WS.
 *   • Приём broadcast'ов. Сейчас реагируем ТОЛЬКО на `desktop_kicked` —
 *     callback в UI переводит app в LoginScreen (кикнуло с другого ПК).
 *
 * Что НЕ сделано (этапы 4b+):
 *   • Остальные kind'ы (new_message, unread_update, presence_change,
 *     typing_*) — будем обрабатывать когда подключим чаты/новости к UI.
 *   • Авто-reconnect — простой exponential backoff уже есть.
 */
class WsClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private var socket: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private var loginName: String = ""
    private var authToken: String = ""
    private var cryptoSession: DesktopWsCrypto.Session? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    var onDesktopKicked: () -> Unit = {}

    /** Пришло новое сообщение (1:1 или в чате с админом). [fromLogin] — чей
     *  отправитель, для фильтрации/таргета refresh в конкретном чате. */
    var onNewMessage: (fromLogin: String) -> Unit = {}

    /** Unread-counters поменялись (сервер бьёт после read/new). */
    var onUnreadUpdate: () -> Unit = {}

    /** Изменилось presence-статус одного из пользователей (online/paused/offline). */
    var onPresenceChange: () -> Unit = {}

    /** Новая новость/опрос в ленте или обновление (vote/reaction/pin/edit). */
    var onNewsUpdate: () -> Unit = {}

    /**
     * §TZ-DESKTOP 0.4.x round 12 — Apps Script lock acquired/released
     * broadcast events для multi-client блокировки sheet tabs во время
     * запуска макроса. Payload содержит actionId, userName, actionLabel,
     * tabName, lockedTabRawNames (массив строк).
     */
    var onSheetLockAcquired: (
        actionId: String,
        actionLabel: String,
        userName: String,
        tabName: String,
        lockedTabs: List<String>,
    ) -> Unit = { _, _, _, _, _ -> }

    var onSheetLockReleased: (actionId: String) -> Unit = {}

    /**
     * §TZ-DESKTOP-DIST — сервер бродкастит `app_version_changed` сразу после
     * `set_app_version` admin-action / release-script broadcast. Клиент
     * триггерит внеочередной `appStatus` (вне 30-минутного polling-окна),
     * чтобы юзер увидел SoftUpdateDialog в течение секунд после релиза.
     *
     * scope передаётся для фильтрации: десктоп бьёт по обоим (mac+win),
     * клиент берёт только свой [BuildInfo.SCOPE]. Если scope чужой —
     * callback не вызывается.
     */
    var onAppVersionChanged: (scope: String, currentVersion: String) -> Unit = { _, _ -> }

    fun connect(login: String, token: String) {
        loginName = login
        authToken = token
        openSocket()
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "bye")
        socket = null
        cryptoSession = null
        connected.set(false)
    }

    private fun openSocket() {
        // §TZ-DESKTOP-0.10.2 — WS_URL через MediaUrlResolver: на Win+corp-proxy
        // переписывается на wss://45-12-239-5.sslip.io/ws (consistent с /api).
        // Direct mode → wss://api.otlhelper.com/ws (как было).
        val req = Request.Builder()
            .url(HttpClientFactory.WS_URL)
            .build()
        socket = HttpClientFactory.rest.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected.set(true)
            reconnectAttempt = 0
            val cs = DesktopWsCrypto.newSession()
            cryptoSession = cs
            val init = JSONObject().apply {
                put("type", "crypto_init")
                put("v", 1)
                put("epk", cs.ephemeralPubBase64)
            }
            webSocket.send(init.toString())
            val hello = JSONObject().apply {
                put("type", "hello")
                put("login", loginName)
                put("token", authToken)
            }
            webSocket.send(DesktopWsCrypto.encryptClientFrame(cs, hello.toString()))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val type = runCatching { JSONObject(text).optString("type") }.getOrDefault("")
            if (type == "crypto_ok") return
            if (cryptoSession != null) return
            handleMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val cs = cryptoSession ?: return
            val text = try { DesktopWsCrypto.decryptServerFrame(cs, bytes) } catch (_: Exception) {
                webSocket.close(4002, "ws_decrypt_failed")
                return
            }
            handleMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected.set(false)
            cryptoSession = null
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected.set(false)
            cryptoSession = null
            // Мы сами сказали bye — не реконнектим.
            if (code != 1000) scheduleReconnect()
        }
    }

    private fun handleMessage(text: String) {
        val json = try { JSONObject(text) } catch (_: Exception) { return }
        when (val kind = json.optString("type")) {
            "desktop_kicked" -> {
                val targetLogin = json.optString("login")
                if (targetLogin == loginName) {
                    scope.launch(Dispatchers.Main) { onDesktopKicked() }
                }
            }
            "new_message" -> {
                val from = json.optString("sender_login").ifBlank { json.optString("from") }
                scope.launch(Dispatchers.Main) { onNewMessage(from) }
            }
            "unread_update" -> scope.launch(Dispatchers.Main) { onUnreadUpdate() }
            "presence_change", "presence_update" ->
                scope.launch(Dispatchers.Main) { onPresenceChange() }
            "new_news", "news_update", "news_edit", "news_delete",
            "news_pin", "news_unpin", "news_react", "news_vote",
            "scheduled_sent" ->
                scope.launch(Dispatchers.Main) { onNewsUpdate() }
            // typing_start / typing_stop — пока не реализуем UI indicator,
            // но не падаем на них.
            "typing_start", "typing_stop" -> Unit
            "sheet_lock_acquired" -> {
                val actionId = json.optString("action_id")
                val actionLabel = json.optString("action_label")
                val userName = json.optString("user_name")
                val tabName = json.optString("tab_name")
                val lockedTabs = json.optJSONArray("locked_tabs")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                } ?: emptyList()
                if (actionId.isNotBlank()) {
                    scope.launch(Dispatchers.Main) {
                        onSheetLockAcquired(actionId, actionLabel, userName, tabName, lockedTabs)
                    }
                }
            }
            "sheet_lock_released" -> {
                val actionId = json.optString("action_id")
                if (actionId.isNotBlank()) {
                    scope.launch(Dispatchers.Main) { onSheetLockReleased(actionId) }
                }
            }
            "app_version_changed" -> {
                val s = json.optString("scope")
                val cur = json.optString("current_version")
                if (s.isNotBlank()) {
                    scope.launch(Dispatchers.Main) { onAppVersionChanged(s, cur) }
                }
            }
            else -> {
                // Неизвестный тип — пока просто игнорим, не логируем в прод.
                if (kind.isNotBlank() && kind.startsWith("news") ||
                    kind == "new_message" || kind == "unread_update") {
                    // дефолтный fallback никогда не достигается, но оставим явный.
                }
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val delayMs = minOf(1000L * (1L shl reconnectAttempt.coerceAtMost(6)), 60_000L)
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            openSocket()
        }
    }

    fun close() {
        disconnect()
        scope.cancel()
    }
}
