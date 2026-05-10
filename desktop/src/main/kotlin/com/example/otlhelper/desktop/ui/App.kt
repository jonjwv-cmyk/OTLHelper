package com.example.otlhelper.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.first
import com.example.otlhelper.desktop.BuildInfo
import com.example.otlhelper.desktop.core.session.SessionLifecycleManager
import com.example.otlhelper.desktop.core.update.AppUpdate
import com.example.otlhelper.desktop.core.update.VersionInfo
import com.example.otlhelper.desktop.data.chat.ConversationRepository
import com.example.otlhelper.desktop.data.chat.InboxRepository
import com.example.otlhelper.desktop.data.feed.NewsRepository
import com.example.otlhelper.desktop.data.network.ApiClient
import com.example.otlhelper.desktop.data.scheduled.ScheduledRepository
import com.example.otlhelper.desktop.data.security.IntegrityCheck
import com.example.otlhelper.desktop.data.security.blobAwareUrl
import com.example.otlhelper.desktop.data.users.UsersRepository
import com.example.otlhelper.desktop.data.network.WsClient
import com.example.otlhelper.desktop.data.session.SessionStore
import com.example.otlhelper.desktop.model.Role
import com.example.otlhelper.desktop.ui.dialogs.SoftUpdateDialog
import com.example.otlhelper.desktop.ui.lock.ExtensionPromptDialog
import com.example.otlhelper.desktop.ui.login.LoginScreen
import com.example.otlhelper.desktop.ui.main.MainScreen
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private enum class AppState { RESTORING, LOGIN, MAIN }

/**
 * §TZ-DESKTOP-0.1.0 этап 3 — state-root с реальным login-flow.
 *
 * Поток:
 *  1. На старте пытаемся восстановить сессию из [SessionStore]. Если есть
 *     токен — сразу MainScreen с role из сохранённой сессии.
 *  2. Иначе — LoginScreen. При успешном login сохраняем сессию и переходим
 *     в MainScreen.
 *  3. Logout → ApiClient.logout() → SessionStore.clear() → LoginScreen.
 *
 * §TZ-DESKTOP-UX-2026-05 0.8.54 — RESTORING state. Юзер: «вошёл в аккаунт,
 * закрыл, на запуске видим окно входа и потом исчезает». Initial state
 * был LOGIN → LoginScreen рендерился мгновенно, потом async LaunchedEffect
 * ходил в /me (1-3 сек HTTP roundtrip) и переключал на MAIN. Юзер видел
 * login form flash. Теперь sync-проверяем SessionStore на composition:
 * если сохранённая сессия есть → стартуем в RESTORING (показываем spinner)
 * → LaunchedEffect делает /me probe → MAIN (валидный токен) или LOGIN
 * (мёртвый токен / network blip → fallback в cached MAIN). Если сохранённой
 * сессии нет → стартуем сразу в LOGIN (без перехода через RESTORING).
 */
@Composable
fun App() {
    // §TZ-DESKTOP-0.10.18 — debug log boot banner. Юзер увидит файл
    // Desktop\otl-debug.log с этой строкой → знает что app запустился.
    LaunchedEffect(Unit) {
        com.example.otlhelper.desktop.core.debug.DebugLogger.log(
            "BOOT",
            "OTL Helper ${BuildInfo.VERSION} on ${BuildInfo.OS} | " +
                "java=${System.getProperty("java.version")} | " +
                "user.home=${System.getProperty("user.home")}"
        )
        // §0.11.12 — Kaspersky/AV monitor. Логирует AV процессы +
        // injected DLL + temp I/O latency в Desktop\otl-debug.log.
        // Тег [AV_MONITOR]. Periodic re-scan каждую минуту.
        // §0.11.14.1 — расширен: process spawn timing, file I/O timing,
        // network probe, executable location class, shutdown hook.
        com.example.otlhelper.desktop.core.debug.KasperskyMonitor.start()
        // §0.11.14.1 — periodic heap/threads/GC/uptime snapshot каждые 30s.
        // Тег [SYS_METRICS]. Spike warnings: GC pressure, heap leak, thread leak.
        com.example.otlhelper.desktop.core.debug.SystemMetricsLogger.start()
    }

    // §0.11.9 — SAP triple Ctrl+C launcher. Глобальный hotkey listener
    // через clipboard sequence polling. При triple-copy (3 копирования
    // за 1.5 сек) читает буфер, валидирует формат (10 цифр 42/43/44 →
    // ME23N заказ, 7-8 цифр 5/6 → VL02N поставка), запускает bundled
    // VBS через cscript с env vars. Win-only. Стартует один раз и
    // работает всё время пока приложение запущено. Error UI — heavyweight
    // DialogWindow (поверх Sheets webview зоны).
    com.example.otlhelper.desktop.sap.SapLauncherIntegration()
    var state by remember {
        // Sync SessionStore.load() — file IO ~5-50ms, приемлемо на cold
        // start чтобы избежать LoginScreen flash. LaunchedEffect ниже
        // потом делает HTTP probe для определения MAIN vs LOGIN.
        mutableStateOf(
            if (SessionStore.load() != null) AppState.RESTORING
            else AppState.LOGIN
        )
    }
    var login by remember { mutableStateOf("") }
    // Логируем любой переход state (после declaration login)
    // §0.11.14.1 — добавили dwell time предыдущего state, uptime, heap snapshot.
    var stateTransitionTs by remember { mutableStateOf(System.currentTimeMillis()) }
    var prevAppState by remember { mutableStateOf<AppState?>(null) }
    LaunchedEffect(state) {
        val now = System.currentTimeMillis()
        val dwellMs = now - stateTransitionTs
        val uptimeMs = java.lang.management.ManagementFactory.getRuntimeMXBean().uptime
        val rt = Runtime.getRuntime()
        val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        com.example.otlhelper.desktop.core.debug.DebugLogger.event(
            "STATE",
            "transition" to "appstate",
            "from" to (prevAppState?.name ?: "null"),
            "to" to state.name,
            "login" to login.ifBlank { "-" },
            "prev_dwell_ms" to dwellMs,
            "uptime_ms" to uptimeMs,
            "heap_mb" to heapUsedMb,
            "threads" to Thread.activeCount(),
        )
        stateTransitionTs = now
        prevAppState = state
    }
    var fullName by remember { mutableStateOf("") }
    var avatarUrl by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.User) }
    var kickedNotice by remember { mutableStateOf(false) }

    // §TZ-DESKTOP-DIST — состояние update-flow (server app_status).
    var pendingUpdateVersion by remember { mutableStateOf("") }
    var pendingUpdateUrl by remember { mutableStateOf("") }
    var pendingUpdateForce by remember { mutableStateOf(false) }
    var pendingUpdateDismissed by remember { mutableStateOf(false) }

    // §TZ-DESKTOP-UX-2026-04 — публичный snapshot версий для footer'а
    // правой панели и Settings: Android current, БД current, Desktop current.
    var versionInfo by remember { mutableStateOf(VersionInfo()) }

    // §TZ-DESKTOP-DIST — канал для форс-запуска appStatus check'а вне
    // 30-минутного poll-окна. WS event `app_version_changed` шлёт сигнал,
    // polling-loop просыпается и сразу делает запрос. Conflated capacity =
    // если signal придёт пока loop делает request, второй сигнал
    // не теряется, но и не копится в очередь.
    val forceUpdateCheck = remember { Channel<Unit>(capacity = Channel.CONFLATED) }

    val scope = rememberCoroutineScope()
    val ws = remember { WsClient() }

    // §TZ-0.10.5 — PC session lifecycle.
    val sessionLifecycle = remember { SessionLifecycleManager(scope) }
    val lifecycleState by sessionLifecycle.state.collectAsState()

    // §TZ-2.4.4 — восстановление сессии при холодном старте + проверка
    // валидности токена. Если сервер сбросил sessions (как было после reset
    // паролей 2026-04-30) или токен expired — авто-очистка cached session
    // (включая зашифрованную ленту/чаты/media), показ login screen.
    // Без этого юзер видел старую ленту из cache + 401 на любом действии.
    //
    // §TZ-DESKTOP-UX-2026-05 0.8.54 — initial state может быть RESTORING
    // (если SessionStore.load() ≠ null). Effect делает /me probe и
    // переключает RESTORING → MAIN/LOGIN. Если initial state = LOGIN
    // (нет сохранённой сессии) — effect ничего не делает, юзер сразу
    // видит form.
    LaunchedEffect(Unit) {
        if (state != AppState.RESTORING) return@LaunchedEffect
        val saved = SessionStore.load()
        if (saved == null) {
            // Между sync-check в remember и сейчас сессию могли удалить
            // (теоретически). Защита.
            state = AppState.LOGIN
            return@LaunchedEffect
        }
        ApiClient.setToken(saved.token)
        // Quick valid-token probe через `me` (легковесный action).
        val resp = withContext(Dispatchers.IO) {
            runCatching { ApiClient.me() }.getOrNull()
        }
        val ok = resp?.optBoolean("ok", false) == true
        val deadTokenError = resp?.optString("error", "")?.lowercase() ?: ""
        val isDeadToken = deadTokenError in setOf(
            "invalid_token", "token_revoked", "token_expired",
            "user_inactive", "user_suspended", "password_reset",
            "auth_required",
        )
        if (ok) {
            login = saved.login
            role = saved.role
            fullName = saved.fullName
            avatarUrl = saved.avatarUrl
            state = AppState.MAIN
        } else if (isDeadToken) {
            // Токен мёртв — авто-wipe + показ login screen.
            ApiClient.clearToken()
            runCatching { SessionStore.clear() }
            state = AppState.LOGIN
            kickedNotice = true
        } else {
            // Сеть мигнула / 5xx — оставляем cached state.
            // Heartbeat поймает реальный 401 если токен мёртв.
            login = saved.login
            role = saved.role
            fullName = saved.fullName
            avatarUrl = saved.avatarUrl
            state = AppState.MAIN
        }
    }

    // §TZ-DESKTOP-DIST — version-check + clean-up старых installer'ов.
    //
    // Раз на старте чистим закэшированный installer если установленная
    // версия совпала с тем что мы ранее качали (юзер успешно обновился —
    // файл больше не нужен).
    //
    // Дальше каждые 30 минут опрашиваем appStatus(scope, version). Если
    // сервер вернул `current_version > BuildInfo.VERSION` — показываем
    // SoftUpdateDialog (force=true при !version_ok, иначе non-blocking).
    LaunchedEffect(Unit) {
        AppUpdate.clearStaleAfterUpdate()
        while (true) {
            try {
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.appStatus(
                        scope = BuildInfo.SCOPE,
                        appVersion = BuildInfo.VERSION,
                        binarySha = IntegrityCheck.selfSha256,
                    )
                }
                if (resp.optBoolean("ok", false)) {
                    val versionOk = resp.optBoolean("version_ok", true)
                    val current = resp.optString("current_version", "").trim()
                    val updUrl = resp.optString("update_url", "").trim()
                    val sha = resp.optString("apk_sha256", "").trim()
                    val baseVer = resp.optString("base_version", "").trim()
                    val baseUpdAt = resp.optString("base_updated_at", "").trim()
                    val force = !versionOk || resp.optBoolean("force_update", false)
                    if (sha.isNotBlank()) AppUpdate.setExpectedSha256(sha)
                    // §TZ-DESKTOP-UX-2026-04 — обновляем VersionInfo: desktop
                    // current+url+sha (этот scope) + base (общая для платформ).
                    versionInfo = versionInfo.copy(
                        baseVersion = baseVer,
                        baseUpdatedAt = baseUpdAt,
                        desktopCurrent = current,
                        desktopUpdateUrl = updUrl,
                        desktopUpdateSha = sha,
                        desktopForceUpdate = force,
                    )
                    // §0.11.13 — показываем диалог обновления ТОЛЬКО если
                    // серверная версия СТРОГО ВЫШЕ клиента. Раньше было `!=` —
                    // это значит при локальной dev-сборке (BuildInfo.VERSION
                    // выше чем D1.current_version который обновляется CI'ем)
                    // юзер видел "обновитесь до 0.10.x" поверх LoginScreen,
                    // диалог блокировал QR-форму. Сейчас сравниваем семантически.
                    val needs = current.isNotBlank() &&
                        updUrl.isNotBlank() &&
                        isServerVersionNewer(current, BuildInfo.VERSION)
                    if (needs) {
                        pendingUpdateVersion = current
                        pendingUpdateUrl = updUrl
                        pendingUpdateForce = force
                        if (force) pendingUpdateDismissed = false
                    } else {
                        pendingUpdateVersion = ""
                        pendingUpdateUrl = ""
                        pendingUpdateForce = false
                    }
                }
            } catch (_: Throwable) { /* offline — пробуем в следующий тик */ }
            // Ждём либо 30 минут, либо WS-сигнал app_version_changed.
            // withTimeoutOrNull возвращает null при таймауте → loop продолжается.
            withTimeoutOrNull(30L * 60L * 1000L) {
                forceUpdateCheck.receive()
            }
        }
    }

    // §TZ-DESKTOP-UX-2026-04 — отдельный info-only polling для Android scope.
    // Нужен только чтобы показывать актуальную версию Android в правой панели
    // и в Settings (никакого update-action для desktop'а это не запускает).
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.appStatus(scope = "main", appVersion = "")
                }
                if (resp.optBoolean("ok", false)) {
                    val androidCur = resp.optString("current_version", "").trim()
                    val baseVer = resp.optString("base_version", "").trim()
                    val baseUpd = resp.optString("base_updated_at", "").trim()
                    versionInfo = versionInfo.copy(
                        androidCurrent = androidCur,
                        // base здесь дублируется (один и тот же server-side row)
                        // — берём из любого ответа, который пришёл свежее.
                        baseVersion = baseVer.ifBlank { versionInfo.baseVersion },
                        baseUpdatedAt = baseUpd.ifBlank { versionInfo.baseUpdatedAt },
                    )
                }
            } catch (_: Throwable) { /* offline — следующий тик */ }
            delay(60L * 60L * 1000L)  // раз в час достаточно для info-display
        }
    }

    /** Обновляет full_name + avatar_url (с blob keys) через `me`.
     *  Вызывается после входа и при восстановлении сессии — чтобы в UI
     *  были свежие значения, а не закэшированные в login-response. */
    suspend fun refreshOwnProfile() {
        try {
            val resp = withContext(Dispatchers.IO) { ApiClient.me() }
            if (!resp.optBoolean("ok", false)) return
            val data = resp.optJSONObject("data") ?: resp
            val userObj = data.optJSONObject("user") ?: data
            val serverFull = userObj.optString("full_name").orEmpty()
            val serverAvatar = blobAwareUrl(userObj, "avatar_url").trim()
            if (serverFull.isNotBlank()) fullName = serverFull
            if (serverAvatar.isNotBlank()) avatarUrl = serverAvatar
            // Перепишем сохранённую сессию новыми полями.
            SessionStore.load()?.let { saved ->
                SessionStore.save(
                    saved.copy(
                        fullName = if (serverFull.isNotBlank()) serverFull else saved.fullName,
                        avatarUrl = if (serverAvatar.isNotBlank()) serverAvatar else saved.avatarUrl,
                    )
                )
            }
        } catch (_: Exception) { /* offline — keep cached */ }
    }

    // На MAIN сразу тянем профиль — чтобы аватар и имя обновились, если
    // сервер вернул blob-keys только в `me`, а не в login-response.
    LaunchedEffect(state, login) {
        if (state == AppState.MAIN && login.isNotBlank()) {
            refreshOwnProfile()
        }
    }

    // §TZ-0.10.12 — splash-only pipeline после login / QR re-redeem.
    //
    //  Котик висит поверх browser ~2.5с пока SheetsWorkspace монтирует
    //  фрешный CefBrowser через `client.createBrowser(initialUrl, …)` и
    //  Sheets проходит свой natural load:
    //    белый → Google chrome → CSS-маска (onLoadEnd inject) → итог.
    //  Когда снимаем кота — юзер видит уже замаскированную таблицу.
    //
    //  ВАЖНО — НЕ дёргать `SheetsViewBridge.browser?.loadUrl(initialUrl)`
    //  отсюда. Раньше (0.10.9–0.10.11) был такой код в предположении
    //  «browser переиспользуется и стоит на about:blank после logout».
    //  В реальности SheetsWebView.kt делает `browser.dispose()` через
    //  DisposableEffect onDispose при unmount → на re-mount создаётся
    //  фрешный browser с правильным URL в конструкторе. Дублирующий
    //  loadUrl отсюда вызывал double-navigation: первый load + CSS-инжект
    //  отрабатывали, второй loadUrl сбивал страницу в промежуточное
    //  состояние, котик снимался, юзер видел белый экран. На cold-start
    //  баг не проявлялся: JCEF инициализируется медленно → второй loadUrl
    //  попадал в первый → де-факто redirect на тот же URL без эффекта.
    //  На re-login JCEF уже warm → первая навигация успевает завершиться
    //  до 500ms-задержки → второй loadUrl ломал готовую страницу.
    //
    //  Юзер: «после кота всегда показывается таблица корректно. не важно
    //  чистый вход или сброс сессии путем входа на другом пк по коду».
    LaunchedEffect(state, login) {
        if (state == AppState.MAIN && login.isNotBlank()) {
            com.example.otlhelper.desktop.core.debug.DebugLogger.log(
                "SHEETS", "MAIN entered, starting splash + loadFromServer"
            )
            com.example.otlhelper.desktop.sheets.SheetsViewBridge.externalSplashOverlay.value = true
            val loadJob = scope.launch(Dispatchers.IO) {
                val ok = com.example.otlhelper.desktop.sheets.SheetsRegistry.loadFromServer()
                com.example.otlhelper.desktop.core.debug.DebugLogger.log(
                    "SHEETS", "loadFromServer → ok=$ok, files=${com.example.otlhelper.desktop.sheets.SheetsRegistry.filesList.size}"
                )
                if (!ok) {
                    com.example.otlhelper.desktop.core.debug.DebugLogger.warn(
                        "SHEETS", "get_client_config failed — workspace останется пустым"
                    )
                }
            }
            // §0.10.26 — SPLASH HOLD UNTIL reveal-done (не на жёстком timeout).
            // Раньше splash off через 2.5+2.5с — на slow network WebView2 reveal
            // занимал 50+ секунд → юзер видел чёрный квадрат / пустоту между
            // splash off и actual Sheets render. Решение: ждать revealingFlow=false
            // через SheetsViewBridge.browser.isRevealing.
            //
            // Шаги:
            //  1. Минимум 1с splash (SheetsWorkspace mount + browser create lag)
            //  2. Ждать пока config загрузится (loadFromServer)
            //  3. Ждать пока browser ref bind в SheetsViewBridge (mount complete)
            //  4. Ждать пока isRevealing → false (CSS-маска инжектнута, reveal-done)
            //  5. Maximum 90 сек safety timeout (не висим вечно если SAP/Google down)
            kotlinx.coroutines.delay(1_000)
            kotlinx.coroutines.withTimeoutOrNull(5_000) { loadJob.join() }

            // §0.11.2 — outer timeout уменьшен 90s→20s. На re-login после
            // logout WebView2 может не emit isRevealing event (same-URL
            // optimization), splash висел. Теперь max 20s даже если reveal
            // не fired — гарантия что юзер не сидит вечно с котом.
            // Force visible browser в конце как safety.
            kotlinx.coroutines.withTimeoutOrNull(20_000) {
                var br: com.example.otlhelper.desktop.sheets.SheetsBrowserController? = null
                while (br == null) {
                    br = com.example.otlhelper.desktop.sheets.SheetsViewBridge.browser
                    if (br != null) break
                    kotlinx.coroutines.delay(100)
                }
                val ctrl = br ?: return@withTimeoutOrNull
                kotlinx.coroutines.withTimeoutOrNull(3_000) {
                    ctrl.isRevealing.first { it } // wait reveal-pipeline started
                }
                ctrl.isRevealing.first { !it } // wait reveal-done
            }

            // §0.11.2 — force visible browser. На re-login было: splash off
            // → но webview HWND скрыт через needsWebviewHide=true (externalSplash).
            // Если splash off через timeout без reveal → браузер остаётся
            // hidden. Force show тут гарантирует что юзер видит Sheets даже
            // если reveal pipeline не отработал нормально.
            runCatching {
                com.example.otlhelper.desktop.sheets.SheetsViewBridge.browser?.setVisible(true)
            }

            com.example.otlhelper.desktop.sheets.SheetsViewBridge.externalSplashOverlay.value = false
            com.example.otlhelper.desktop.core.debug.DebugLogger.log(
                "SHEETS", "splash off, sheetsLoaded=${com.example.otlhelper.desktop.sheets.SheetsRegistry.loaded.value}"
            )
        }
    }

    // §TZ-0.10.9 — auto-logout вместо LockOverlay при dead-token. Раньше
    // показывался полупрозрачный overlay «Сессия заблокирована», но он
    // глючил (race с tickLocal: то появлялся, то нет). Юзер: «может и окно
    // не нада если сессия тут же заканчивается». Теперь lock=true сразу
    // тригерит logout flow → LoginScreen с QR. Это убирает race полностью —
    // нечему мерцать. Inline logic т.к. local fun performLogout определена
    // ниже в этом composable (Kotlin forward-declaration не работает для
    // local functions внутри @Composable).
    LaunchedEffect(lifecycleState.locked) {
        if (lifecycleState.locked && state == AppState.MAIN) {
            // Скрыть Sheets webview перед logout (см. performLogout).
            runCatching {
                com.example.otlhelper.desktop.sheets.SheetsViewBridge.browser?.setVisible(false)
                com.example.otlhelper.desktop.sheets.SheetsViewBridge.browser?.loadUrl("about:blank")
            }
            runCatching { ApiClient.logout() }
            ApiClient.clearToken()
            SessionStore.clear()
            // §TZ-DESKTOP-0.10.13 — на logout сбрасываем server-loaded sheets
            // config; на re-login заново подтянется через get_client_config.
            com.example.otlhelper.desktop.sheets.SheetsRegistry.reset()
            sessionLifecycle.stop()
            login = ""
            fullName = ""
            avatarUrl = ""
            role = Role.User
            state = AppState.LOGIN
        }
    }

    // §TZ-DESKTOP-0.1.0 этап 4a — WS подключается на MAIN-состоянии. При
    // `desktop_kicked` (пользователь зашёл с другого ПК) — очищаем сессию
    // и падаем на LoginScreen с пометкой.
    //
    // Real-time (этап 4b+): WS-события new_message/unread_update/news_*
    // триггерят forceRefresh соответствующего репозитория — чтобы UI не
    // ждал следующий poll-тик. Сам polling остаётся как fallback.
    // §TZ-0.10.5 — start/stop SessionLifecycleManager в зависимости от состояния.
    DisposableEffect(state) {
        if (state == AppState.MAIN) {
            sessionLifecycle.start()
        } else {
            sessionLifecycle.stop()
        }
        onDispose { sessionLifecycle.stop() }
    }

    DisposableEffect(state) {
        if (state == AppState.MAIN) {
            ws.onDesktopKicked = {
                kickedNotice = true
                scope.launch {
                    // §TZ-0.10.10 — Sheets cleanup ПЕРЕД state change. Иначе
                    // heavyweight Chromium NSView остаётся в Z-order поверх
                    // LoginScreen → юзер видит зависший cat splash вместо QR.
                    runCatching {
                        com.example.otlhelper.desktop.sheets.SheetsViewBridge.browser?.setVisible(false)
                        com.example.otlhelper.desktop.sheets.SheetsViewBridge.browser?.loadUrl("about:blank")
                        com.example.otlhelper.desktop.sheets.SheetsViewBridge.externalSplashOverlay.value = false
                    }
                    ApiClient.clearToken()
                    SessionStore.clear()
                    com.example.otlhelper.desktop.sheets.SheetsRegistry.reset()
                    login = ""
                    fullName = ""
                    avatarUrl = ""
                    role = Role.User
                    state = AppState.LOGIN
                }
            }
            // §TZ-DESKTOP 0.4.x — Apps Script lock multi-client.
            ws.onSheetLockAcquired = { actionId, actionLabel, userName, tabName, lockedTabs ->
                com.example.otlhelper.desktop.sheets.SheetsLockHub.acquire(
                    actionId = actionId,
                    actionLabel = actionLabel,
                    userName = userName,
                    tabName = tabName,
                    lockedTabRawNames = lockedTabs,
                )
            }
            ws.onSheetLockReleased = { actionId ->
                com.example.otlhelper.desktop.sheets.SheetsLockHub.release(actionId)
            }
            // §TZ-DESKTOP-DIST — WS push'ит `app_version_changed` сразу после
            // release-script broadcast'а. Дёргаем форс-чек чтобы пользователь
            // увидел SoftUpdateDialog в течение секунд после релиза, а не
            // ждал следующий 30-минутный polling-тик.
            ws.onAppVersionChanged = { wsScope, _ ->
                if (wsScope == BuildInfo.SCOPE) {
                    forceUpdateCheck.trySend(Unit)
                }
            }
            ws.connect(login = login, token = ApiClient.currentToken())
        } else {
            ws.disconnect()
        }
        onDispose { ws.disconnect() }
    }

    fun performLogout() {
        scope.launch {
            // §TZ-0.10.9 — перед logout СНАЧАЛА скрыть heavyweight Sheets
            // webview через setVisible(false). Иначе native NSView/HWND
            // остаётся в Z-order поверх Compose LoginScreen → юзер видит
            // фантомный белый/серый кусок Google Sheets вместо QR-окна.
            // setVisible(false) убирает native view целиком — далее loadUrl
            // blank на всякий случай чтобы при re-login не было stale page.
            runCatching {
                com.example.otlhelper.desktop.sheets.SheetsViewBridge.browser?.setVisible(false)
                com.example.otlhelper.desktop.sheets.SheetsViewBridge.browser?.loadUrl("about:blank")
            }
            runCatching { ApiClient.logout() }
            ApiClient.clearToken()
            SessionStore.clear()
            com.example.otlhelper.desktop.sheets.SheetsRegistry.reset()
            login = ""
            fullName = ""
            avatarUrl = ""
            role = Role.User
            state = AppState.LOGIN
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (state) {
            AppState.RESTORING -> {
                // §TZ-DESKTOP-UX-2026-05 0.8.54 — splash во время /me probe.
                // Spinner на бренд-фоне; юзер видит загрузку, не form flash.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            AppState.LOGIN -> LoginScreen(
                onLoggedIn = { newLogin, newRole, newFullName, newAvatarUrl ->
                    login = newLogin
                    role = newRole
                    fullName = newFullName
                    avatarUrl = newAvatarUrl
                    state = AppState.MAIN
                },
            )
            AppState.MAIN -> {
                // §TZ-DESKTOP-0.1.0 этап 5 — репозитории создаются один раз per-login
                // и живут пока сессия активна. Сворачивание/разворачивание правой
                // панели НЕ приводит к пересозданию и повторной загрузке данных.
                //
                // §TZ-DESKTOP-0.10.13 — гейтим рендер MainScreen на загрузку
                // SheetsRegistry с сервера. SheetsWorkspace требует non-null
                // SheetsRegistry.WORKFLOW; до server-load этот доступ был бы
                // null → крэш. Пока loaded=false показываем пустоту: cat splash
                // из LaunchedEffect выше визуально это перекрывает.
                val sheetsLoaded by com.example.otlhelper.desktop.sheets.SheetsRegistry.loaded.collectAsState()
                if (sheetsLoaded) {
                val repos = remember(login) {
                    if (login.isBlank()) null
                    else SessionRepos(
                        scope = scope,
                        inboxRepo = InboxRepository(scope),
                        newsRepo = NewsRepository(scope, myLogin = login),
                        convRepo = ConversationRepository(scope, myLogin = login),
                        scheduledRepo = ScheduledRepository(scope),
                        usersRepo = UsersRepository(scope),
                        sessionLifecycle = sessionLifecycle,
                    )
                }
                // Старт polling'ов на MAIN, остановка на logout/kick.
                DisposableEffect(repos) {
                    repos?.inboxRepo?.start()
                    repos?.newsRepo?.start()
                    onDispose {
                        repos?.inboxRepo?.stop()
                        repos?.newsRepo?.stop()
                        repos?.convRepo?.close()
                    }
                }
                // §TZ-DESKTOP-UX-2026-04 — фоновый prefetch чатов всех peers
                // из inbox чтобы CentralSearchDialog искал по полному архиву,
                // а не только по чатам которые юзер открывал в этой сессии.
                //
                // Ждём первого успешного inbox-refresh (rows непустые,
                // isLoading=false), берём уникальные peer logins, лениво
                // грузим по 200 сообщений на peer (~150ms throttle).
                if (repos != null) {
                    LaunchedEffect(repos) {
                        var done = false
                        repos.inboxRepo.state.collect { st ->
                            if (done) return@collect
                            if (st.isLoading) return@collect
                            val peers = st.rows.map { it.senderLogin }
                                .filter { it.isNotBlank() }
                                .distinct()
                            if (peers.isEmpty()) return@collect
                            done = true
                            scope.launch(Dispatchers.IO) {
                                runCatching { repos.convRepo.prefetchPeers(peers) }
                            }
                        }
                    }
                }
                // §TZ-DESKTOP-0.1.0 этап 4b+ — WS → форс-рефреш соответствующих
                // репо на real-time события. Polling остаётся fallback'ом если
                // WS отвалился.
                DisposableEffect(repos) {
                    if (repos != null) {
                        ws.onNewMessage = { fromLogin ->
                            scope.launch {
                                repos.inboxRepo.refresh()
                                if (fromLogin.isNotBlank()) {
                                    repos.convRepo.refreshIfOpenWith(fromLogin)
                                }
                            }
                        }
                        ws.onUnreadUpdate = {
                            scope.launch { repos.inboxRepo.refresh() }
                        }
                        // §0.11.0 — targeted presence update. WS event
                        // `presence_change{login,status,last_seen_at}` →
                        // точечно обновляет presence в InboxRepository
                        // (chat list sender dot) и UsersRepository (UserList
                        // dot + label) без full refresh с сервера.
                        // Мгновенный UI update <100ms.
                        ws.onPresenceChange = { plogin, pstatus, plastSeen ->
                            repos.inboxRepo.updateSenderPresence(plogin, pstatus)
                            repos.usersRepo.updatePresence(plogin, pstatus, plastSeen)
                        }
                        ws.onNewsUpdate = {
                            scope.launch { repos.newsRepo.forceRefresh() }
                        }
                    }
                    onDispose {
                        ws.onNewMessage = {}
                        ws.onUnreadUpdate = {}
                        ws.onPresenceChange = { _, _, _ -> }
                        ws.onNewsUpdate = {}
                    }
                }
                if (repos != null) {
                    MainScreen(
                        login = login,
                        fullName = fullName,
                        avatarUrl = avatarUrl,
                        role = role,
                        onLogout = { performLogout() },
                        repos = repos,
                        versionInfo = versionInfo,
                        // §TZ-DESKTOP-UX-2026-04 — Settings → "Обновить" →
                        // confirm "Да" → этот callback. Если есть pending
                        // update — снимаем dismissed-флаг чтобы SoftUpdateDialog
                        // показался поверх. Если pending пуст — просто
                        // дёргаем force-check (WS event аналог).
                        onUpdateRequest = {
                            pendingUpdateDismissed = false
                            forceUpdateCheck.trySend(Unit)
                        },
                    )
                }
                }  // ← закрывает if (sheetsLoaded) выше (§0.10.13 macro gate)
            }
        }

        // §TZ-DESKTOP-DIST — soft / force update dialog поверх любого экрана.
        // Force-update обязателен (пока не обновится — UI не отвечает); soft —
        // юзер может закрыть, до следующего polling-тика не показываем повторно.
        val showUpdate = pendingUpdateUrl.isNotBlank() &&
            pendingUpdateVersion.isNotBlank() &&
            (pendingUpdateForce || !pendingUpdateDismissed)
        if (showUpdate) {
            SoftUpdateDialog(
                version = pendingUpdateVersion,
                url = pendingUpdateUrl,
                force = pendingUpdateForce,
                onDismiss = {
                    pendingUpdateDismissed = true
                    pendingUpdateVersion = ""
                    pendingUpdateUrl = ""
                },
            )
        }

        // §TZ-DESKTOP-0.9.3 — Manual proxy creds dialog. Показывается
        // автоматически когда [JcifsNtlmAuthenticator] не нашёл cached creds.
        // Юзер вводит доменный логин/пароль, после этого NTLM работает.
        com.example.otlhelper.desktop.ui.dialogs.ManualProxyCredsDialogHost()

        // §TZ-0.10.9 — LockOverlay убран. Раньше при locked=true показывал
        // полупрозрачный экран «Сессия заблокирована, нажмите Показать QR» —
        // но из-за race между tickLocal/refresh/onAuthFailure он мерцал.
        // Теперь lifecycleState.locked напрямую тригерит performLogout()
        // в LaunchedEffect выше → юзер сразу попадает на LoginScreen с QR,
        // без промежуточного экрана.

        // §TZ-0.10.5 — Extension prompt за 5 мин до конца окна, если ext доступны.
        // §TZ-DESKTOP-0.10.13 — onDismiss теперь делает immediate logout вместо
        // dismissExtensionPrompt(). Юзер: «кнопка отмены не сбрасывает как
        // положено сессию чтобы был экран QR кода». Раньше Cancel просто
        // скрывал диалог, сессия дотикивала до истечения, юзер сидел в
        // потенциально мёртвой сессии — clicks падали с 401 на следующих
        // /api запросах. Теперь Cancel = explicit logout → LoginScreen с QR.
        // Та же логика для X-кнопки в title bar (через onCloseRequest).
        if (state == AppState.MAIN && lifecycleState.shouldShowExtensionPrompt) {
            ExtensionPromptDialog(
                yekHm = lifecycleState.yekHm,
                extensionsRemaining = lifecycleState.extensionsRemaining,
                onExtend = {
                    scope.launch { sessionLifecycle.extend() }
                },
                onDismiss = { performLogout() },
            )
        }
    }
}

/**
 * §0.11.13 — Семантическое сравнение версий для update-флоу.
 * Возвращает true если [serverVer] СТРОГО ВЫШЕ [clientVer]. Защита от
 * "downgrade prompts" когда клиент локально собран на новой версии,
 * а D1 ещё не обновлён CI'ем.
 *
 * Формат: "X.Y.Z" (semantic versioning, без pre-release/build metadata).
 * Невалидный формат → false (безопасный default — не показываем диалог).
 */
private fun isServerVersionNewer(serverVer: String, clientVer: String): Boolean {
    if (serverVer.isBlank() || clientVer.isBlank()) return false
    if (serverVer == clientVer) return false
    val s = serverVer.split('.').mapNotNull { it.toIntOrNull() }
    val c = clientVer.split('.').mapNotNull { it.toIntOrNull() }
    if (s.isEmpty() || c.isEmpty()) return false
    val maxLen = maxOf(s.size, c.size)
    for (i in 0 until maxLen) {
        val sv = s.getOrElse(i) { 0 }
        val cv = c.getOrElse(i) { 0 }
        if (sv > cv) return true
        if (sv < cv) return false
    }
    return false
}
