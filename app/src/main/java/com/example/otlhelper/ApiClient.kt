package com.example.otlhelper

import android.util.Log
import com.example.otlhelper.core.auth.AuthEvents
import com.example.otlhelper.data.network.HttpClientFactory
import com.example.otlhelper.data.network.api.AdminCalls
import com.example.otlhelper.data.network.api.AdminCallsImpl
import com.example.otlhelper.data.network.api.ApiGateway
import com.example.otlhelper.data.network.api.AuthCalls
import com.example.otlhelper.data.network.api.AuthCallsImpl
import com.example.otlhelper.data.network.api.FeedCalls
import com.example.otlhelper.data.network.api.FeedCallsImpl
import com.example.otlhelper.data.network.api.MetricsCalls
import com.example.otlhelper.data.network.api.MetricsCallsImpl
import com.example.otlhelper.data.network.api.PcSessionCalls
import com.example.otlhelper.data.network.api.PcSessionCallsImpl
import com.example.otlhelper.data.network.api.PushCalls
import com.example.otlhelper.data.network.api.PushCallsImpl
import com.example.otlhelper.data.network.api.SystemCalls
import com.example.otlhelper.data.network.api.SystemCallsImpl
import com.example.otlhelper.shared.api.ApiActions
import com.example.otlhelper.shared.api.ApiFields
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray

/**
 * §TZ-CLEANUP-2026-04-25 — facade-делегирование к zone-implementations.
 * Public API (ApiClient.login(...) / .logout() / etc.) сохранён через
 * Kotlin delegation — call sites не меняются.
 *
 * Zone-делегаты (по мере выноса):
 *   • [AuthCalls]    — login/logout/me/change_password/heartbeat/app_status (✅ вынесено)
 *   • [PushCalls]    — register/unregister push token (✅ вынесено)
 *   • [MetricsCalls] — errors/activity/metrics/network stats (✅ вынесено)
 *   • [SystemCalls]  — base download/avatar (✅ вынесено)
 *   • [FeedCalls]    — messaging/news/polls/reactions/edit/drafts/schedule (✅ вынесено)
 *   • [AdminCalls]   — users CRUD/audit/mute/dnd/system pause (✅ вынесено)
 *
 * **Все 6 zone'ов вынесены.** Facade теперь содержит только инфраструктуру:
 * auth state, request/postJson, telemetry hook, avatarUrlFor.
 */
private val FacadeGateway: ApiGateway = object : ApiGateway {
    override fun request(action: String, build: JSONObject.() -> Unit): JSONObject =
        ApiClient.request(action, build)
}

object ApiClient :
    AuthCalls by AuthCallsImpl(FacadeGateway),
    FeedCalls by FeedCallsImpl(FacadeGateway),
    AdminCalls by AdminCallsImpl(FacadeGateway),
    PushCalls by PushCallsImpl(FacadeGateway),
    MetricsCalls by MetricsCallsImpl(FacadeGateway),
    SystemCalls by SystemCallsImpl(FacadeGateway),
    PcSessionCalls by PcSessionCallsImpl(FacadeGateway) {

    // §TZ-2.3.17 — back to :443 (стандартный HTTPS порт). Архитектура изменена:
    // теперь VPS nginx делает TLS TERMINATION (не passthrough) с self-signed
    // cert. Diagnostic показал что ISP DPI режет не порт и не hostname, а
    // TLS-fingerprint Cloudflare. VPS отдаёт NGINX TLS handshake → DPI не
    // распознаёт CF → пропускает полную скорость. VPS затем re-encrypts и
    // проксирует в CF.
    // Клиент доверяет self-signed cert через res/raw/otl_vps_cert.pem +
    // HttpClientFactory.buildVpsSslFactory. SAN покрывает все *.otlhelper.com.
    private const val BASE_URL = "https://api.otlhelper.com/"
    private val JSON_MEDIA = "application/json; charset=UTF-8".toMediaType()

    private var _token = ""
    private var _deviceId = ""

    // Shared OkHttpClient — connection pool + HTTP/2 + pipelining come for free.
    // The AuthSigningInterceptor reads the current token via the closure so we
    // don't need to rebuild the client on every auth change.
    //
    // Cert pinning применяется через HttpClientFactory → PinningConfig, когда
    // пины в PinningConfig заполнены (не placeholder).
    private val client: OkHttpClient by lazy {
        HttpClientFactory.restClient { _token }
    }

    /**
     * Public avatar URL for a login, per the server's fixed convention:
     * `{BASE_URL}avatar/{login}`. The server returns 404 when the user has no
     * uploaded avatar — Coil catches that and the UI falls back to initials.
     */
    fun avatarUrlFor(login: String): String =
        "${BASE_URL}avatar/${login.trim()}"

    fun setAuth(token: String, deviceId: String = "") {
        _token = token
        if (deviceId.isNotBlank()) _deviceId = deviceId
    }

    fun getToken() = _token

    fun clearAuth() {
        _token = ""
        _deviceId = ""
    }

    /**
     * Хук для телеметрии латенси. Подключается из [OtlApp.onCreate] — туда
     * передаётся [com.example.otlhelper.core.telemetry.Telemetry.timing]. В
     * этом файле не импортируется сам Telemetry чтобы не плодить циклы.
     * Вызывается после каждого HTTP-запроса.
     */
    @Volatile
    var onActionLatency: ((action: String, durationMs: Long, ok: Boolean, httpStatus: Int, errorCode: String) -> Unit)? = null

    /** §TZ-CLEANUP-2026-04-25 — internal для делегатов из data/network/api/.
     *  Public методы facade'а вызывают его напрямую; zone-делегаты — через
     *  [ApiGateway] (см. FacadeGateway вверху файла). */
    internal fun request(action: String, build: JSONObject.() -> Unit = {}): JSONObject {
        return postJson(JSONObject().apply {
            put(ApiFields.ACTION, action)
            if (_token.isNotBlank()) put(ApiFields.TOKEN, _token)
            if (_deviceId.isNotBlank()) put(ApiFields.DEVICE_ID, _deviceId)
            build()
        })
    }

    // §TZ-CLEANUP-2026-04-25 — auth-зона (login/logout/me/changePassword/
    // heartbeat/appStatus) вынесена в data/network/api/AuthCalls.kt.
    // Делегирование через `AuthCalls by AuthCallsImpl(...)` в декларации
    // объекта — public API сохранён, call sites не изменились.

    // logErrors / logActivity / getAppStats / getAppErrors — вынесено в MetricsCalls.

    // editMessage / softDeleteMessage / undeleteMessage — вынесено в FeedCalls.
    // saveDraft / loadDraft / listDrafts — вынесено в FeedCalls.
    // scheduleMessage / listScheduled / cancelScheduled — вынесено в FeedCalls.

    // muteContact / unmuteContact / getMutedContacts / setDndSchedule
    //   — вынесено в AdminCalls.
    // addReaction / removeReaction / getReactions — вынесено в FeedCalls.
    // getAuditLog — вынесено в AdminCalls.
    // logout/changePassword — вынесено в AuthCalls.
    // toggleUser / resetPassword / renameUser / changeUserLogin /
    //   changeUserRole / deleteUser / createUser / getUsers
    //   — вынесено в AdminCalls.
    // me — вынесено в AuthCalls.
    // getBaseVersion / getBaseDownloadUrl / downloadBase ×2 — вынесено в SystemCalls.
    // sendMessage / sendNews / getNews / getNewsReaders / getPollStats /
    //   pinMessage / unpinMessage — вынесено в FeedCalls.
    // getSystemState / setAppPause / clearAppPause — вынесено в AdminCalls.
    // createNewsPoll / voteNewsPoll / getUserChat / getAdminChat /
    //   getAdminMessages / getUnreadCounts / markMessageRead — вынесено в FeedCalls.
    // registerPushToken / unregisterPushToken — вынесено в PushCalls.

    // uploadAvatar — вынесено в SystemCalls.

    // logMetrics / getNetworkStats — вынесено в MetricsCalls.

    private fun postJson(body: JSONObject): JSONObject {
        val action = body.optString("action", "")
        val request = Request.Builder()
            .url(BASE_URL)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            // Upstream is a Cloudflare Worker — most actions are mutations, so
            // we keep no-cache semantics end-to-end. (Endpoints that can cache
            // in future should drop these headers selectively — TODO M7b.)
            .header("Cache-Control", "no-cache, no-store, must-revalidate")
            .header("Pragma", "no-cache")
            .build()

        val startedAt = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val parsed = JSONObject(text.ifBlank { "{}" })
                val ok = parsed.optBoolean("ok", false)
                val err = parsed.optString("error", "")
                val durationMs = System.currentTimeMillis() - startedAt
                // Telemetry + NetworkMetricsBuffer hooks. Telemetry.timing сам
                // фильтрует быстрые вызовы (> 2с → user_activity slow_action).
                // NetworkMetricsBuffer логирует ВСЁ → агрегации по сети.
                runCatching {
                    onActionLatency?.invoke(
                        action,
                        durationMs,
                        ok && response.isSuccessful,
                        response.code,
                        err,
                    )
                }

                if (!response.isSuccessful || !ok) {
                    Log.w(
                        TAG,
                        "action=$action http=${response.code} ok=$ok " +
                            "dur=${durationMs}ms err=$err body=${text.take(200)}"
                    )
                    if (action != ApiActions.LOGIN && err in AuthEvents.DEAD_TOKEN_ERRORS) {
                        AuthEvents.emit(err)
                    }
                } else {
                    Log.d(TAG, "action=$action http=${response.code} ok=true dur=${durationMs}ms")
                }
                return parsed
            }
        } catch (e: Exception) {
            // Сетевой fail ДО получения response (SocketTimeout, UnknownHost,
            // SSLPeerUnverified, ConnectException и т.п.) — раньше эти кейсы
            // НЕ попадали в network_metrics, из-за чего мы не видели что у
            // некоторых юзеров Worker не может даже TCP/TLS установить.
            // Теперь логируем с http=0, ok=false, errorCode=Exception-class
            // для быстрой диагностики: "base_version 0ms ssl_peer_unverified"
            // → видно что пиннинг режет соединение.
            val durationMs = System.currentTimeMillis() - startedAt
            val errorCode = e.javaClass.simpleName.take(40)
            Log.w(
                TAG,
                "action=$action network_fail dur=${durationMs}ms " +
                    "ex=${errorCode} msg=${e.message?.take(200) ?: ""}"
            )
            runCatching {
                onActionLatency?.invoke(
                    action,
                    durationMs,
                    false,     // ok=false — request never got a response
                    0,         // http=0 — no HTTP status (socket-level fail)
                    errorCode, // e.g. "SSLPeerUnverifiedException", "SocketTimeoutException"
                )
            }
            throw e
        }
    }

    private const val TAG = "ApiClient"
}
