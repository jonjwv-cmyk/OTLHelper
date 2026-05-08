package com.example.otlhelper.presentation.home.internal

import android.content.Context
import android.util.Log
import com.example.otlhelper.ApiClient
import com.example.otlhelper.BuildConfig
import com.example.otlhelper.SessionManager
import com.example.otlhelper.core.lifecycle.AppPresence
import com.example.otlhelper.data.repository.MolRepository
import com.example.otlhelper.data.sync.BaseSyncManager
import com.example.otlhelper.presentation.home.HomeUiState
import com.example.otlhelper.presentation.widget.OtlHomeWidgetBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-level slice of HomeViewModel — everything that is neither a feed nor
 * a search.
 *
 * Owns:
 *  - app-status gate (server-driven block overlay + mandatory / soft update banner)
 *  - own-profile refresh (avatar URL, feature flags)
 *  - avatar upload
 *  - heartbeat (25s presence tick)
 *  - app-lifecycle refresh (ON_START unread + feed reload)
 *  - unread counts fetch
 *  - logout
 *
 * Mutates the shared [uiState] under fields: softUpdate*, blockOverlay*,
 * updateUrl, blockUpdateVersion, avatar*, accountScreenOpen, statusMessage,
 * splash*, newsUnreadCount, monitoringUnreadCount.
 *
 * §TZ-CLEANUP-2026-04-26 — admin-операции (users management, system state)
 * вынесены в [AdminController].
 */
internal class AppController(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<HomeUiState>,
    private val session: SessionManager,
    private val context: Context,
    private val molRepository: MolRepository,
    private val baseSyncManager: BaseSyncManager,
    private val onFlushPendingQueue: () -> Unit,
    private val onReloadActiveTab: () -> Unit,
) {

    private var heartbeatJob: Job? = null

    // §TZ-2.3.9 — Nankin-case recovery: после успешного heartbeat'а, если
    // были подряд фейлы, триггерим refresh всего что initial-load мог
    // упустить (app_status / unread / active tab / base sync). Иначе юзер
    // залипает в broken-UI после временной сетевой проблемы.
    private var consecutiveHeartbeatFailures = 0

    // ── App status (server-driven block / soft update) ───────────────────────
    suspend fun checkAppStatus() {
        try {
            val response = withContext(Dispatchers.IO) { ApiClient.appStatus(BuildConfig.VERSION_NAME) }
            val appState = response.optString("app_state", "normal")
            val versionOk = response.optBoolean("version_ok", true)
            val updateUrl = response.optString("update_url", "")
            val currentVersion = response.optString("current_version", "")
            val apkSha256 = response.optString("apk_sha256", "")
            val localVersion = BuildConfig.VERSION_NAME
            // §TZ-2.3.35 Phase 4a — сохраняем expected SHA-256 каждый раз при
            // получении app_status. AppUpdate.installApk() сверит с этим
            // значением перед установкой; mismatch → refuse.
            com.example.otlhelper.core.update.AppUpdate.setExpectedSha256(context, apkSha256)

            // Soft update — server has a newer build than what's installed, but
            // we're still above min_version. Non-blocking banner.
            val softUpdateAvailable = appState == "normal" &&
                versionOk &&
                currentVersion.isNotBlank() &&
                isVersionLess(localVersion, currentVersion)

            when {
                appState != "normal" -> {
                    val title = response.optString("app_title", response.optString("title", "Приложение недоступно"))
                    val message = response.optString("app_message", response.optString("message", ""))
                    uiState.update {
                        it.copy(
                            blockOverlayVisible = true,
                            blockTitle = title,
                            blockMessage = message,
                            updateUrl = updateUrl,
                            blockUpdateVersion = currentVersion,
                            softUpdateAvailable = false,
                        )
                    }
                }
                !versionOk -> {
                    uiState.update {
                        it.copy(
                            blockOverlayVisible = true,
                            blockTitle = "Требуется обновление",
                            blockMessage = "Пожалуйста, обновите приложение до последней версии.",
                            updateUrl = updateUrl,
                            blockUpdateVersion = currentVersion,
                            softUpdateAvailable = false,
                        )
                    }
                }
                else -> {
                    uiState.update {
                        it.copy(
                            blockOverlayVisible = false,
                            softUpdateAvailable = softUpdateAvailable && !it.softUpdateDismissed,
                            softUpdateVersion = if (softUpdateAvailable) currentVersion else "",
                            softUpdateUrl = if (softUpdateAvailable) updateUrl else "",
                        )
                    }
                }
            }
            OtlHomeWidgetBridge.publishUpdate(
                context,
                available = (softUpdateAvailable || !versionOk) && updateUrl.isNotBlank(),
                version = currentVersion,
            )
        } catch (_: Exception) {
            // no network — skip
        }
    }

    fun dismissSoftUpdate() {
        uiState.update { it.copy(softUpdateAvailable = false, softUpdateDismissed = true) }
    }

    /** Compare semver-ish strings "a.b.c" — returns true if a < b. */
    private fun isVersionLess(a: String, b: String): Boolean {
        fun parts(v: String) = v.trim().split(".").mapNotNull { it.toIntOrNull() }
        val ap = parts(a)
        val bp = parts(b)
        val n = maxOf(ap.size, bp.size)
        for (i in 0 until n) {
            val ai = ap.getOrElse(i) { 0 }
            val bi = bp.getOrElse(i) { 0 }
            if (ai != bi) return ai < bi
        }
        return false
    }

    // ── Own profile ──────────────────────────────────────────────────────────
    /**
     * Pull the current user's own profile (`me` action) to refresh avatar_url
     * and any other server-side fields we cache locally. Called on init() so
     * the avatar appears immediately after login without waiting for
     * chat/news payloads to arrive.
     */
    suspend fun refreshOwnProfile() {
        try {
            val response = withContext(Dispatchers.IO) { ApiClient.me() }
            if (!response.optBoolean("ok", false)) return
            val data = response.optJSONObject("data") ?: response
            // §TZ-2.3.28 — data может быть `response.user` (me endpoint) или response
            // сам. Проверяем оба. blob-key/nonce поля внутри user.
            val userObj = data.optJSONObject("user") ?: data
            val serverAvatar = com.example.otlhelper.core.security.blobAwareUrl(userObj, "avatar_url").trim()
            if (serverAvatar.isNotBlank() && serverAvatar != session.getAvatarUrl()) {
                session.saveAvatarUrl(serverAvatar)
                uiState.update { it.copy(avatarUrl = serverAvatar) }
            }
            // Refresh feature flags on every me() — the server can roll a feature
            // out between sessions (§3.15.a.Е "refreshed on every online heartbeat").
            data.optJSONObject("features")?.let { session.saveFeatures(it) }
        } catch (_: Exception) {
            // offline — keep cached/convention avatar
        }
    }

    // ── Avatar upload ────────────────────────────────────────────────────────
    /**
     * Uploads avatar bytes to the server (R2 via worker). Caller provides
     * already-loaded image bytes (from gallery/camera picker). Bytes are
     * encoded as base64 data URL — the worker stores them in R2 and returns
     * the avatar_url, which we persist to the session.
     */
    fun uploadAvatar(bytes: ByteArray, mimeType: String, fileName: String) {
        scope.launch {
            uiState.update { it.copy(avatarUploading = true, avatarUploadError = "") }
            try {
                val b64 = withContext(Dispatchers.IO) {
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }
                val dataUrl = "data:$mimeType;base64,$b64"
                val response = withContext(Dispatchers.IO) {
                    ApiClient.uploadAvatar(dataUrl, mimeType, fileName)
                }
                if (response.optBoolean("ok", false)) {
                    // §TZ-2.3.28 — blobAwareUrl добавит blob fragment если
                    // avatar зашифрован (для 2.3.28+ всё uploaded avatars
                    // сервер шифрует).
                    val url = com.example.otlhelper.core.security.blobAwareUrl(response, "avatar_url")
                    session.saveAvatarUrl(url)
                    // Cache-bust in case the server returned the same URL after re-upload.
                    // Добавляем query param ДО fragment'а (иначе cache-bust попадёт в fragment).
                    val (baseUrl, frag) = run {
                        val h = url.indexOf('#')
                        if (h < 0) url to ""
                        else url.substring(0, h) to url.substring(h)
                    }
                    val cacheBusted = if (baseUrl.contains("?"))
                        "$baseUrl&t=${System.currentTimeMillis()}$frag"
                    else
                        "$baseUrl?t=${System.currentTimeMillis()}$frag"
                    val displayUrl = cacheBusted
                    uiState.update {
                        it.copy(
                            avatarUploading = false,
                            avatarUploadError = "",
                            avatarUrl = displayUrl,
                            statusMessage = "Аватар обновлён",
                        )
                    }
                } else {
                    val err = response.optString("error", "upload_failed")
                    uiState.update {
                        it.copy(
                            avatarUploading = false,
                            avatarUploadError = err,
                            statusMessage = "Не удалось загрузить аватар",
                        )
                    }
                }
            } catch (e: Exception) {
                uiState.update {
                    it.copy(
                        avatarUploading = false,
                        avatarUploadError = e.message ?: "network_error",
                        statusMessage = "Ошибка сети — попробуйте ещё раз",
                    )
                }
            }
        }
    }

    // ── Unread counts ────────────────────────────────────────────────────────
    fun loadUnreadCounts() {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.getUnreadCounts() }
                val ok = response.optBoolean("ok", false)
                if (ok) {
                    // Server returns { ok, data: { news: N, admin_messages: N } }
                    val data = response.optJSONObject("data")
                    val news = data?.optInt("news", 0) ?: response.optInt("news_unread", 0)
                    val monitoring = data?.optInt("admin_messages", 0) ?: response.optInt("messages_unread", 0)
                    uiState.update { it.copy(newsUnreadCount = news, monitoringUnreadCount = monitoring) }
                    OtlHomeWidgetBridge.publish(context, news, monitoring)
                }
            } catch (_: Exception) {
                // Silent — next auto-refresh tick retries.
            }
        }
    }

    // ── Heartbeat ────────────────────────────────────────────────────────────
    //
    // Fires every 25s so the server's "online" presence window (which expires
    // ~60s after last heartbeat) stays comfortably refreshed. Combined with
    // the 30s feed auto-refresh, other users see your status appear within
    // ~30s of becoming active — close enough to "live" without WebSockets.
    fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            // Send one immediately so we appear online right after launch.
            // A successful heartbeat is a strong signal that the network is back,
            // so we opportunistically drain the offline queue here too.
            runHeartbeatTick()
            while (isActive) {
                delay(25_000L)
                runHeartbeatTick()
            }
        }
    }

    private suspend fun runHeartbeatTick() {
        try {
            val response = withContext(Dispatchers.IO) { ApiClient.heartbeat(AppPresence.state) }
            val hadPriorFailures = consecutiveHeartbeatFailures > 0
            consecutiveHeartbeatFailures = 0
            scope.launch { onFlushPendingQueue() }
            checkBaseVersionAndForceSync(response)
            // §TZ-2.3.9 — Nankin recovery. Если до этого тика один-или-больше
            // heartbeat'ов упал — сеть только что восстановилась. Initial-load
            // endpoints (app_status / news / chat / base_version) скорее всего
            // тоже ушли в таймаут и UI в broken-state. Триггерим refresh чтобы
            // юзер не сидел на пустом экране до следующего вручную toggl'а.
            if (hadPriorFailures) {
                Log.i("AppController", "heartbeat recovered — refreshing initial loads")
                scope.launch { checkAppStatus() }
                scope.launch { loadUnreadCounts() }
                scope.launch { onReloadActiveTab() }
                // syncBaseIfNeeded — idempotent; WorkManager не запустит второй
                // если уже running или только что закончился success'ом.
                try { baseSyncManager.enqueue() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            consecutiveHeartbeatFailures++
        }
    }

    /**
     * §RELIABLE-BASE канал 2 (fast-path когда app open): сервер в heartbeat
     * response включает `base_version`. Сравниваем с локальной Room-копией;
     * при несовпадении — `baseSyncManager.force()` (REPLACE — убивает backoff
     * предыдущей попытки). Даёт ~25с granularity — ловит случай "FCM не дошёл"
     * пока юзер активно использует приложение, не ждём 15-мин periodic.
     *
     * Guard: ничего не делаем если сервер вернул пустой `base_version`
     * (старый сервер до деплоя ответов с base_version) — старый и новый
     * клиенты совместимы, просто без fast-path.
     */
    private suspend fun checkBaseVersionAndForceSync(heartbeatResponse: org.json.JSONObject) {
        val serverVersion = heartbeatResponse.optString("base_version", "").trim()
        if (serverVersion.isEmpty()) return
        val localVersion = withContext(Dispatchers.IO) {
            runCatching { molRepository.getLocalVersion() }.getOrDefault("")
        }
        if (localVersion == serverVersion) return
        Log.i(
            "AppController",
            "base_version mismatch (local=$localVersion, server=$serverVersion) — force sync"
        )
        try { baseSyncManager.force() } catch (e: Exception) {
            Log.w("AppController", "baseSyncManager.force() failed: ${e.message}", e)
        }
    }

    // ── App lifecycle refresh ────────────────────────────────────────────────
    /**
     * Subscribe to ProcessLifecycleOwner — when the app returns from
     * background, refresh the active tab immediately. Without this, presence
     * dots and unread badges lagged 5-8s (waiting for the next auto-refresh).
     */
    fun startAppLifecycleRefresh() {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                scope.launch {
                    loadUnreadCounts()
                    onReloadActiveTab()
                }
            }
        }
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    }

    // ── Account screen / status / logout ─────────────────────────────────────
    fun openAccountScreen() {
        uiState.update { it.copy(accountScreenOpen = true) }
    }

    fun closeAccountScreen() {
        uiState.update { it.copy(accountScreenOpen = false) }
    }

    fun setStatus(msg: String) {
        uiState.update { it.copy(statusMessage = msg) }
    }

    fun setSplashStatus(status: String) {
        uiState.update { it.copy(splashStatus = status) }
    }

    fun setSplashVisible(visible: Boolean) {
        uiState.update { it.copy(splashVisible = visible) }
    }

    fun logout(onDone: () -> Unit) {
        scope.launch {
            try { withContext(Dispatchers.IO) { ApiClient.logout() } } catch (_: Exception) {}
            session.clearSession()
            ApiClient.clearAuth()
            // Сбросить learned clock offset — новая сессия может попасть на
            // другой regional CF edge с микроразницей во времени.
            com.example.otlhelper.core.network.AuthSigningInterceptor.resetClockOffset()
            // Затираем persistent UI-state (scroll positions, drafts, активный tab)
            // при logout'е — чтобы следующий юзер на том же устройстве не увидел
            // scroll-position / draft / warehouse-pin прошлого пользователя.
            // SaveableStateHolder в Compose сам уничтожится когда HomeScreen
            // покинет composition (navigate("login") с popUpTo(0)).
            runCatching {
                context.getSharedPreferences("home_state", Context.MODE_PRIVATE)
                    .edit().clear().apply()
            }
            onDone()
        }
    }

    // ── Push event routing ───────────────────────────────────────────────────
    /** Returns true if the push belongs to app domain and was handled. */
    fun handlePush(type: String): Boolean {
        return when (type) {
            "app_version" -> {
                // Release script broadcast — re-check app_status to populate
                // the soft-update banner. Reset the dismissed flag so the new
                // release shows even if the user hid the previous one.
                uiState.update { it.copy(softUpdateDismissed = false) }
                scope.launch { checkAppStatus() }
                true
            }
            else -> false
        }
    }

    fun cancel() {
        heartbeatJob?.cancel()
    }
}
