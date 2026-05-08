package com.example.otlhelper.data.network

import android.content.Context
import com.example.otlhelper.SessionManager
import com.example.otlhelper.core.push.PushEvent
import com.example.otlhelper.core.push.PushEventBus
import com.example.otlhelper.core.security.E2ECrypto
import com.example.otlhelper.core.security.WsCrypto
import com.example.otlhelper.domain.limits.Limits
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * SF-2026 Phase 8 (§3.7) — WebSocket-канал реального времени.
 *
 * Протокол описан в [server-modular/ws-room.js](https://.../server-modular/ws-room.js).
 *
 * Поведение:
 *  - [connect] открывает WebSocket к `wss://<BASE>/ws` и шлёт hello с login.
 *  - Авто-reconnect с экспоненциальным backoff до [Limits.WS_RECONNECT_MAX_DELAY_MS].
 *  - Heartbeat ping каждые [Limits.WS_PING_INTERVAL_MS] — держит соединение живым.
 *  - Входящие события маппятся в [PushEventBus] (UI уже подписан).
 *  - Если соединение молчит > [Limits.WS_SILENT_FALLBACK_MS] — heartbeat+FCM
 *    делают работу, WS переподключается в фоне.
 */
@Singleton
class WsClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SessionManager,
    private val pushEventBus: PushEventBus,
) {
    // Используем общий HttpClientFactory — сертификат пиннер применится если
    // PinningConfig активен (см. core/security/PinningConfig.kt).
    private val okHttp: OkHttpClient by lazy { HttpClientFactory.wsClient() }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var ws: WebSocket? = null
    @Volatile private var cryptoSession: WsCrypto.Session? = null
    private val connected = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private var reconnectJob: Job? = null
    private var attempt = 0

    private val _state = MutableStateFlow(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    enum class State { Disconnected, Connecting, Connected }

    fun connect() {
        if (connected.get() || reconnectJob?.isActive == true) return
        stopRequested.set(false)
        reconnectJob = scope.launch { connectLoop() }
    }

    fun disconnect() {
        stopRequested.set(true)
        ws?.close(1000, "client_disconnect")
        ws = null
        cryptoSession = null
        connected.set(false)
        _state.value = State.Disconnected
    }

    fun sendPresence(status: String) {
        send(JSONObject().apply { put("type", "presence"); put("status", status) })
    }

    fun sendTypingStart(peerLogin: String) {
        send(JSONObject().apply { put("type", "typing_start"); put("peer_login", peerLogin) })
    }

    fun sendTypingStop(peerLogin: String) {
        send(JSONObject().apply { put("type", "typing_stop"); put("peer_login", peerLogin) })
    }

    private fun send(msg: JSONObject) {
        val sock = ws
        if (sock == null || !connected.get()) return
        val cs = cryptoSession
        try {
            if (cs != null) {
                // §TZ-2.3.31 Phase 3b — encrypted binary frame.
                val bin = WsCrypto.encryptClientFrame(cs, msg.toString())
                sock.send(bin)
            } else {
                sock.send(msg.toString())
            }
        } catch (_: Exception) { /* closed */ }
    }

    private suspend fun connectLoop() {
        while (!stopRequested.get()) {
            _state.value = State.Connecting
            val wsUrl = ApiClientWsBase + "/ws"
            val request = Request.Builder().url(wsUrl).build()
            val sock = okHttp.newWebSocket(request, listener)
            ws = sock
            val sessionStartMs = System.currentTimeMillis()
            // Ждём либо Connected (listener flips connected), либо сбой.
            var waited = 0L
            while (!connected.get() && !stopRequested.get() && waited < 10_000L) {
                delay(200); waited += 200
            }
            if (connected.get()) {
                // §TZ-2.3.36 Phase 9 — WS rekey через периодический reconnect.
                // Каждые WS_MAX_SESSION_MS принудительно закрываем соединение →
                // при reconnect'е WsCrypto.newSession генерит новую ephemeral
                // пару + HKDF → свежие session keys + counter с 0. Уменьшает
                // объём данных под одним ключом без сложных mid-session rekeying.
                while (connected.get() && !stopRequested.get()) {
                    delay(1_000)
                    val elapsed = System.currentTimeMillis() - sessionStartMs
                    if (elapsed >= WS_MAX_SESSION_MS) {
                        try { sock.close(1000, "rekey_rotation") } catch (_: Exception) {}
                        connected.set(false)
                        cryptoSession = null
                        break
                    }
                }
                if (stopRequested.get()) return
            }
            // Фолбэк: экспоненциальный backoff при отказе.
            attempt++
            val delayMs = min(
                Limits.WS_RECONNECT_MAX_DELAY_MS,
                (500L * 2.0.pow(attempt.toDouble())).toLong(),
            )
            delay(delayMs)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected.set(true)
            attempt = 0
            _state.value = State.Connected

            // §TZ-2.3.31 Phase 3b — crypto_init handshake ПЕРВЫМ фреймом.
            // Генерим ephemeral X25519, выводим session keys, отправляем
            // только public ephemeral в plain TEXT (не секретно). Далее
            // отправляем encrypted hello (содержит token — не видно VPS).
            val serverPub = try { E2ECrypto.serverPublicKey(context) } catch (_: Throwable) { null }
            if (serverPub != null) {
                val cs = WsCrypto.newSession(serverPub)
                cryptoSession = cs
                val init = JSONObject().apply {
                    put("type", "crypto_init")
                    put("v", 1)
                    put("epk", cs.ephemeralPubBase64)
                }
                try { webSocket.send(init.toString()) } catch (_: Exception) {}
            } else {
                cryptoSession = null
            }

            val hello = JSONObject().apply {
                put("type", "hello")
                put("login", session.getLogin())
                put("token", session.getToken())
            }
            // send() сам решит: crypto → binary encrypted, иначе plain text.
            send(hello)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // В crypto-режиме сервер шлёт только crypto_ok plaintext'ом —
            // игнорируем (диагностика). Реальные события приходят binary.
            val msg = try { JSONObject(text) } catch (_: Exception) { return }
            val type = msg.optString("type", "")
            if (type.isBlank() || type == "crypto_ok") return
            // Legacy plain путь (старый сервер или плейн-mode) — обрабатываем.
            if (cryptoSession != null) return
            emitEvent(msg)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val cs = cryptoSession ?: return
            val text = try { WsCrypto.decryptServerFrame(cs, bytes) } catch (_: Throwable) {
                webSocket.close(4002, "ws_decrypt_failed")
                return
            }
            val msg = try { JSONObject(text) } catch (_: Exception) { return }
            emitEvent(msg)
        }

        private fun emitEvent(msg: JSONObject) {
            val type = msg.optString("type", "")
            if (type.isBlank()) return
            val dataMap = mutableMapOf<String, String>()
            val keys = msg.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == "type") continue
                dataMap[k] = msg.opt(k)?.toString().orEmpty()
            }
            pushEventBus.tryEmit(PushEvent.Received(type = type, data = dataMap))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            connected.set(false)
            cryptoSession = null
            _state.value = State.Disconnected
            webSocket.close(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected.set(false)
            cryptoSession = null
            _state.value = State.Disconnected
        }
    }

    companion object {
        // wss вместо https. BASE_URL в ApiClient — https-strip + wss.
        // Если ApiClient.BASE_URL менялся, этот хардкод можно параметризовать.
        const val ApiClientWsBase = "wss://api.otlhelper.com"
        // §TZ-2.3.36 Phase 9 — принудительный rekey WS-сессии каждые 60 мин.
        // При reconnect'е новый ephemeral X25519 → новые c2s/s2c session keys.
        const val WS_MAX_SESSION_MS: Long = 60L * 60 * 1000
    }
}

