package com.example.otlhelper.desktop.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.otlhelper.desktop.theme.BgApp
import kotlinx.coroutines.launch

/**
 * §TZ-DESKTOP 0.4.x round 4 — Sheets-зона приложения.
 *
 * Слои сверху вниз (когда [SheetsRuntime.State.Ready]):
 *   1. [SheetsTopBar] (~40dp) — file-switcher слева, контекстные Apps Script
 *      кнопки + reload справа
 *   2. [SheetsTabStrip] (~32dp) — пилюли вкладок активного файла
 *   3. [SheetsWebView] (weight=1) — Chromium с **single browser**, ниже
 *      идёт нативное Google menu (Правка/Вставка/Данные после CSS-маски)
 *
 * **Архитектура (durable, 2026-04-26 round 4):**
 *   • Source of truth = [SheetsRegistry] со **статическими gid'ами**.
 *   • Single KCEFBrowser. Multi-instance + CardLayout пробовали — на macOS
 *     heavyweight NSView некорректно работает в hidden card (OTIF5 терял
 *     selection, каждый switch повторно грузит). Откачено.
 *   • Все navigation (file switch + sheet switch) через `loadURL` —
 *     гарантированно работает. Same-file sheet switch ускорен через
 *     programmatic click по настоящему Google sheet-tab в DOM.
 *   • Pre-mask body перед loadURL → onLoadEnd снимает после CSS injection.
 *     Юзер не видит flash chrome Google.
 *   • [SheetsViewBridge.browser] держит ref для blur-эффектов на dialog'ах.
 */
@Composable
fun SheetsWorkspace(
    modifier: Modifier = Modifier,
    // §TZ-DESKTOP 0.4.x round 11 — для action lock overlay statusbar
    // («Иван Петров — запущен макрос»). Пока default = "Вы" — Phase 2
    // подключит передачу юзера через MainScreen → SheetsWorkspace.
    currentUserName: String = "Вы",
    modalRightInset: Dp = 0.dp,
) {
    val runtimeState by SheetsRuntime.state.collectAsState()

    // §TZ-DESKTOP-0.10.13 — SheetsRegistry загружается с сервера. App.kt гарантирует
    // что SheetsWorkspace не mount'ится пока loaded=false (см. App.kt MAIN branch),
    // поэтому здесь .WORKFLOW не может быть null. Если null — это баг
    // в гейтинге, явно падаем с осмысленным сообщением.
    val workflowFile = SheetsRegistry.WORKFLOW
        ?: error("SheetsWorkspace mounted before SheetsRegistry was loaded — это баг гейтинга в App.kt")

    var activeFile by remember { mutableStateOf<SheetsFile>(workflowFile) }
    val lastTabByFile = remember { mutableStateMapOf<String, String>() }
    val visibleTabs = remember(activeFile) { activeFile.visibleTabs() }
    var activeTabName by remember {
        mutableStateOf(workflowFile.visibleTabs().firstOrNull()?.originalName)
    }

    var browser: SheetsBrowserController? by remember { mutableStateOf(null) }
    var suppressNextUrlNavigation by remember { mutableStateOf(false) }
    val workspaceScope = rememberCoroutineScope()

    // §TZ-DESKTOP-UX-2026-04 — pаз-флаг для cat overlay во время reload.
    // Когда юзер жмёт «Обновить»: WKWebView/KCEF reload'ит page; в моменте
    // «между unload и onLoadEnd+CSS-inject» Google chrome (toolbar/menu)
    // мерцает наружу — наша CSS-маска ещё не применена. Флаг включаем
    // на время этого окна, скрываем browser + показываем splash с котом
    // (как при cold-start). Снимаем после re-inject CSS (timer-based).
    var isReloading by remember { mutableStateOf(false) }

    // §TZ-DESKTOP-UX-2026-04 — Google login state machine с auto-detection.
    // Initial state зависит от persisted pref:
    //  • Auto (default) → Detecting (probe URL, потом Pending или SignedIn)
    //  • Anonymous (юзер ранее выбрал «Нет» либо нажал «Выход») → сразу
    //    Anonymous, gate не показываем.
    var loginChoice by remember {
        mutableStateOf(
            if (SheetsLoginPref.load() == SheetsLoginPref.Mode.Anonymous) GoogleLoginChoice.Anonymous
            else GoogleLoginChoice.Detecting
        )
    }
    // Confirm-dialog state для logout flow.
    var showLogoutConfirm by remember { mutableStateOf(false) }
    // §TZ-DESKTOP 0.4.x round 12 — Apps Script lock state ИЗ singleton hub.
    // Hub устанавливается из WS broadcast (sheet_lock_acquired/released) →
    // все desktop клиенты видят lock одновременно. Optimistic UI: при
    // запуске action клиент сам acquire'ит локально (не дожидаясь WS roundtrip).
    val actionLock by SheetsLockHub.activeLock.collectAsState()
    var pendingPasswordAction by remember { mutableStateOf<SheetAction?>(null) }

    // §TZ-DESKTOP-UX-2026-05 0.8.59 — signal-based block для tab/file клик
    // во время reveal pipeline (cat splash). Юзер: «когда возвращаемся мы
    // на лист и можем другие листы щелкать от этого баг что лист моем
    // другой кликнуть а грузимся на этот». Native pipelines (Mac/Win) emit
    // через MutableStateFlow в slot. true пока reveal pipeline активна →
    // tab/file клик disabled через UI enabled-flag.
    val sheetRevealing by (
        browser?.isRevealing ?: SheetsBrowserController.DEFAULT_NOT_REVEALING
    ).collectAsState()
    // §TZ-DESKTOP 0.4.x — per-tab lock scope:
    //   • currentTabLocked = active tab is в actionLock.lockedTabRawNames
    //   • Webview скрываем + overlay показываем ТОЛЬКО если currentTabLocked
    //   • Если юзер переключился на не-locked tab — webview показывается
    //     назад, overlay уходит, можно работать. Lock держится в фоне до
    //     завершения скрипта; вернётся на locked tab → снова закрыто.
    val currentTabLocked = actionLock?.lockedTabRawNames?.contains(activeTabName) == true
    // Browser скрываем только когда нужна полноценная защитная загрузка/lock.
    // Password/logout/search остаются поверх живой таблицы с CSS blur.
    val needsWebviewHide = currentTabLocked ||
        isReloading ||
        loginChoice == GoogleLoginChoice.Detecting ||
        showLogoutConfirm ||
        pendingPasswordAction != null
    DisposableEffect(needsWebviewHide, browser) {
        runCatching { browser?.setVisible(!needsWebviewHide) }
        onDispose {}
    }

    /**
     * §TZ-DESKTOP-0.10.13 — Execute action. Если action.requiresPassword,
     * password передаётся параметром (после успешного prompt в UI). Если
     * нет — null. Сервер сам валидирует password при run_script.
     *
     * §TZ-DESKTOP-0.10.13 — Если action.macroId != null, запускаем через
     * MacroOrchestrator (Windows-only): get_macro_bundle → cscript VBS
     * → submit_macro_data. Только потом B2 alive polling и unlock.
     */
    fun executeAction(action: SheetAction, password: String? = null) {
        System.err.println("[OTLD][action] start: ${action.id} macroId=${action.macroId}")
        val lockedTabs = action.locksTabs.ifEmpty {
            listOfNotNull(activeTabName)
        }
        SheetsLockHub.acquire(
            actionId = action.id,
            actionLabel = action.label,
            userName = currentUserName,
            tabName = activeTabName ?: activeFile.title,
            lockedTabRawNames = lockedTabs,
        )
        workspaceScope.launch {
            val ran = if (action.macroId != null) {
                // §TZ-DESKTOP-0.10.13 — macro flow.
                // get_macro_bundle на сервере сам валидирует password,
                // выдаёт VBS source + one-time token. Orchestrator
                // запускает cscript, ждёт TSV, шлёт submit_macro_data.
                val result = com.example.otlhelper.desktop.macro.MacroOrchestrator.runMacro(
                    actionId = action.id,
                    password = password,
                )
                when (result) {
                    is com.example.otlhelper.desktop.macro.MacroOrchestrator.Result.Success -> true
                    is com.example.otlhelper.desktop.macro.MacroOrchestrator.Result.Failure -> {
                        System.err.println("[OTLD][macro] ${action.id} failed: ${result.reason} ${result.detail.orEmpty()}")
                        false
                    }
                }
            } else {
                SheetsActionRunner.runViaServer(
                    action = action,
                    userName = currentUserName,
                    tabName = activeTabName ?: activeFile.title,
                    lockedTabs = lockedTabs,
                    password = password,
                )
            }
            System.err.println("[OTLD][action] done: ${action.id} ok=$ran")
            // Polling B2 alive (если есть statusUrl). Для macro-actions
            // это особенно важно: после submit_macro_data Apps Script
            // только что начал работу, B2 ещё крутится — ждём alive=false.
            if (ran && action.hasStatusUrl) {
                SheetsActionRunner.pollUntilDoneViaServer(
                    actionId = action.id,
                    intervalMs = 2_000,
                    maxAttempts = 180,
                )
            }
            // Reload + re-inject CSS-маска (после reload URL не меняется,
            // MacSheetsWebView polling skip'ает re-inject).
            runCatching { browser?.reload() }
            kotlinx.coroutines.delay(1_500)
            runCatching { browser?.evaluateJavaScript(SheetsCss.INJECT_JS) }
            kotlinx.coroutines.delay(1_000)
            runCatching { browser?.evaluateJavaScript(SheetsCss.INJECT_JS) }
            // Force release locally — даже если WS не сработал. Сервер
            // бродкастит released → у других клиентов release через WS.
            SheetsLockHub.release(action.id)
        }
    }
    // Splash при Ready живёт внутри SwingPanel SheetsWebView: браузер и
    // загрузочный экран лежат в одном JLayeredPane, без отдельного окна.

    // Sync ref в singleton bridge — диалоги (CentralSearchDialog/CommandPalette)
    // используют для blur Sheets canvas через CSS-инжект.
    //
    // §TZ-0.10.12 — DisposableEffect вместо LaunchedEffect: на unmount
    // (state MAIN → LOGIN при logout/kick) очищаем bridge, иначе там
    // остаётся stale-reference на disposed CefBrowser. До 0.10.11 этим
    // stale-ref пользовался App.kt LaunchedEffect(state, login) для
    // повторного loadUrl — отсюда double-navigation и баг с белым экраном
    // при re-login. App.kt больше так не делает, но bridge-cleanup
    // защищает от регрессий и других мест которые могут читать bridge.
    DisposableEffect(browser) {
        SheetsViewBridge.browser = browser
        onDispose {
            // Чистим только если bridge всё ещё на нашем browser:
            // если уже прилетел новый mount и переписал — не трогаем.
            if (SheetsViewBridge.browser === browser) {
                SheetsViewBridge.browser = null
            }
        }
    }

    LaunchedEffect(Unit) { SheetsRuntime.start() }

    // Контекстные actions для активной вкладки (TopBar справа).
    val activeActions = remember(visibleTabs, activeTabName) {
        visibleTabs.firstOrNull { it.originalName == activeTabName }?.actions.orEmpty()
    }

    // §TZ-DESKTOP 0.4.x round 4 — единый driver для navigation.
    // Активный URL вычисляется из (activeFile, activeTabName); SheetsWebView
    // получает его через параметр и сам решает loadURL/skip через lastUrl.
    val activeTab = remember(visibleTabs, activeTabName) {
        visibleTabs.firstOrNull { it.originalName == activeTabName }
    }
    val sheetUrl = remember(activeFile, activeTab) {
        val gid = activeTab?.gid ?: activeFile.firstVisibleGid()
        "https://docs.google.com/spreadsheets/d/${activeFile.id}/edit#gid=$gid"
    }

    // Стартовый URL зависит от [loginChoice]:
    //  • Detecting / Pending / Authenticating / SignedIn → ServiceLogin
    //    (Chromium либо auto-redirect'ит при наличии Google cookie, либо
    //    показывает login form). Detection logic меняет state на основе
    //    того куда Chromium перешёл по `currentUrl`.
    //  • Anonymous → сразу на sheet URL без login (read-only public mode).
    val initialUrl = remember(loginChoice) {
        // workflowFile уже non-null из guard вверху
        val firstUrl = workflowFile.firstTabUrl()
        when (loginChoice) {
            GoogleLoginChoice.Anonymous -> firstUrl
            else -> {
                val continueUrl = java.net.URLEncoder.encode(firstUrl, "UTF-8")
                "https://accounts.google.com/ServiceLogin?continue=$continueUrl"
            }
        }
    }

    // Triggered URL — null до первой явной навигации, потом sheet/login URL.
    var triggeredUrl: String? by remember { mutableStateOf(null) }

    // §TZ-DESKTOP-UX-2026-04 — auto-detection state machine.
    // Polling browser.currentUrl: если ушёл на docs.google.com/spreadsheets
    // → Google залогинен → SignedIn. В Detecting probe длится 8s; в
    // Authenticating 60s ждём пока юзер введёт пароль; timeout → Anonymous.
    LaunchedEffect(loginChoice, browser) {
        val br = browser ?: return@LaunchedEffect
        when (loginChoice) {
            GoogleLoginChoice.Detecting -> {
                // Quick silent probe — для юзеров у кого Google cookie
                // ещё валиден (auto-redirect на sheet через `continue`).
                // Если не нашли login — переходим в Anonymous (просмотр) без
                // лишнего gate-диалога. Юзер сам жмёт «Войти в Google» в
                // TabStrip если хочет редактировать.
                //
                // §TZ-DESKTOP-UX-2026-05 0.8.53 — единый 10-сек deadline на
                // Mac+Win (раньше Mac был 3 сек). Юзер: «вошёл в аккаунт,
                // закрыл программу, на запуске вижу окно логин/пароль вместо
                // сразу работы».
                //
                // ServiceLogin → continue redirect cycle на cold start
                // занимает 3-5+ сек (process spawn + DNS + TLS + Google
                // server roundtrip). 3 сек deadline резал redirect посреди →
                // фолбэк в Anonymous → новый WKWebView slot для spreadsheet
                // загружался без auth state на view (cookies persist на диске
                // но новый WKWebView их ещё не подхватил async). 10 сек даёт
                // redirect завершиться → Detection видит docs.google.com →
                // SignedIn → main slot держится, юзер сразу видит таблицу.
                //
                // Win Edge подгружает cookies из UserDataFolder при первом
                // frame медленнее (~5-7 сек) — для Win 10 сек был всегда.
                val deadline = System.currentTimeMillis() + 10_000L
                while (loginChoice == GoogleLoginChoice.Detecting &&
                    System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(500)
                    val url = br.currentUrl.orEmpty()
                    // §TZ-DESKTOP-UX-2026-04 — `contains` находил подстроку
                    // в continue=<sheet_url>` параметре accounts.google.com URL
                    // и polling ошибочно решал что юзер залогинен с самого
                    // первого тика. `startsWith("https://docs.google.com")`
                    // проверяет именно host.
                    if (url.startsWith("https://docs.google.com/spreadsheets") ||
                        url.startsWith("http://docs.google.com/spreadsheets")) {
                        loginChoice = GoogleLoginChoice.SignedIn
                        return@LaunchedEffect
                    }
                }
                if (loginChoice == GoogleLoginChoice.Detecting) {
                    // §TZ-DESKTOP-UX-2026-05 0.8.52 — fallback на sheetUrl
                    // (текущий activeFile), не hardcoded WORKFLOW. Через
                    // triggeredUrl, не br.loadUrl: пусть MacSheetsWebView
                    // переключит slot по spreadsheetKey(sheetUrl) — иначе
                    // main-slot останется на ServiceLogin URL и Compose
                    // activeFile рассинхронится с webview.
                    triggeredUrl = sheetUrl
                    loginChoice = GoogleLoginChoice.Anonymous
                }
            }
            GoogleLoginChoice.Authenticating -> {
                // §TZ-DESKTOP-UX-2026-05 0.8.59 — БЕЗДЕДЛАЙННЫЙ polling.
                // Раньше 30-сек timeout автоматически кидал в Anonymous —
                // юзер сидел на login form, не успел ввести → reset.
                // Юзер: «должна быть кнопка Отмена. нажали ее и возвращаемся
                // в таблицу. если вошли то как только вход подтвердился тоже
                // возврат». Кнопка Отмена показана в TabStrip вместо «Войти».
                // Кнопка Cancel sets loginChoice=Anonymous напрямую → этот
                // блок exits naturally через `while loginChoice == Auth`.
                while (loginChoice == GoogleLoginChoice.Authenticating) {
                    kotlinx.coroutines.delay(1_000)
                    val url = br.currentUrl.orEmpty()
                    if (url.startsWith("https://docs.google.com/spreadsheets") ||
                        url.startsWith("http://docs.google.com/spreadsheets")) {
                        SheetsLoginPref.save(SheetsLoginPref.Mode.Auto)
                        triggeredUrl = null
                        loginChoice = GoogleLoginChoice.SignedIn
                        // §TZ-DESKTOP-UX-2026-05 0.8.62 — logout-style pipeline
                        // после login SUCCESS. Юзер: «белый экран есть потом
                        // кот держится долго». Это logout flow же работает
                        // быстро (юзер: «logout кот короткий»). Тот же подход:
                        //   • isReloading=true → SheetsSplashOverlay (cat) viewed
                        //   • Browser ALREADY на docs.google.com (Google redirect),
                        //     не нужен loadUrl
                        //   • 1.5 сек wait → Sheets bootstrap settled
                        //   • Двойной CSS inject (как logout) — mask Sheets chrome
                        //   • isReloading=false → splash off → юзер видит таблицу
                        // Total ~2.3 сек splash вместо 5-7 от full reload pipeline.
                        if (!isReloading) {
                            isReloading = true
                            workspaceScope.launch {
                                kotlinx.coroutines.delay(1_500)
                                runCatching { browser?.evaluateJavaScript(SheetsCss.INJECT_JS) }
                                kotlinx.coroutines.delay(800)
                                runCatching { browser?.evaluateJavaScript(SheetsCss.INJECT_JS) }
                                isReloading = false
                            }
                        }
                        return@LaunchedEffect
                    }
                }
                // Loop exit означает что loginChoice сменился на что-то другое
                // (обычно Anonymous через Cancel button). Здесь nothing more
                // to do — Cancel button уже выставил triggeredUrl=sheetUrl.
            }
            else -> Unit
        }
    }

    LaunchedEffect(sheetUrl) {
        // Не дёргаем loadURL пока browser ещё на ServiceLogin (cold start) —
        // Chromium сам redirect'нёт на initialUrl's continue parameter.
        if (browser != null) {
            if (suppressNextUrlNavigation) {
                suppressNextUrlNavigation = false
            } else {
                triggeredUrl = sheetUrl
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(BgApp)) {
        when (val s = runtimeState) {
            is SheetsRuntime.State.Ready -> Column(modifier = Modifier.fillMaxSize()) {
                SheetsTopBar(
                    files = SheetsRegistry.filesList,
                    activeFile = activeFile,
                    onFileChange = { newFile ->
                        // §TZ-DESKTOP-UX-2026-05 0.8.59 — block click если
                        // reveal pipeline активна (cat splash виден). Без
                        // этой защиты юзер кликает другой файл пока грузится
                        // первый → race, грузим не туда, нужно повторно кликать.
                        if (!sheetRevealing && newFile.id != activeFile.id) {
                            activeTabName?.let { lastTabByFile[activeFile.id] = it }
                            activeFile = newFile
                            val newTabs = newFile.visibleTabs()
                            activeTabName = lastTabByFile[newFile.id]
                                ?.takeIf { remembered -> newTabs.any { it.originalName == remembered } }
                                ?: newTabs.firstOrNull()?.originalName
                            // §TZ-DESKTOP-UX-2026-04 — file switch = reload
                            // другого spreadsheet'а в browser. Показываем
                            // splash до окончания load + CSS-mask injection
                            // (timer-based, как у обычного reload).
                            if (!isReloading) {
                                isReloading = true
                                workspaceScope.launch {
                                    kotlinx.coroutines.delay(2_200)
                                    runCatching { browser?.evaluateJavaScript(SheetsCss.INJECT_JS) }
                                    kotlinx.coroutines.delay(800)
                                    isReloading = false
                                }
                            }
                        }
                    },
                    actions = activeActions,
                    actionsEnabled = !currentTabLocked && loginChoice == GoogleLoginChoice.SignedIn,
                    reloading = isReloading,
                    onAction = { action ->
                        if (action.requiresPassword) {
                            pendingPasswordAction = action
                        } else {
                            executeAction(action)
                        }
                    },
                    onReload = {
                        // §TZ-DESKTOP-UX-2026-04 — overlay-pipeline вместо
                        // голого reload(). Reload + delay + двойной inject CSS,
                        // как у executeAction. Без overlay юзер на 1-2 сек
                        // видел голый Google chrome.
                        if (!isReloading) {
                            isReloading = true
                            workspaceScope.launch {
                                runCatching { browser?.reload() }
                                kotlinx.coroutines.delay(1_500)
                                runCatching { browser?.evaluateJavaScript(SheetsCss.INJECT_JS) }
                                kotlinx.coroutines.delay(800)
                                runCatching { browser?.evaluateJavaScript(SheetsCss.INJECT_JS) }
                                isReloading = false
                            }
                        }
                    },
                    onGoogleMenu = { menu -> SheetsViewBridge.openNativeGoogleMenu(menu) },
                )
                SheetsTabStrip(
                    tabs = visibleTabs,
                    activeOriginalName = activeTabName,
                    onTabClick = { tab ->
                        // §TZ-DESKTOP-UX-2026-05 0.8.59 — block tab click
                        // если reveal pipeline активна. См. onFileChange.
                        if (!sheetRevealing && tab.originalName != activeTabName) {
                            lastTabByFile[activeFile.id] = tab.originalName
                            // §TZ-DESKTOP-NATIVE-2026-05 0.8.35 — sheet switch
                            // через JS click bridge с Java loadUrl fallback.
                            // JS пробует:
                            //  1. .docs-sheet-tab[data-id] / [role="tab"] direct
                            //  2. text match среди .docs-sheet-tab/[role="tab"]
                            //  3. tryAllSheetsPopup — открывает "Все листы"
                            //     popup с CSS-override expose, кликает item
                            // JS постит SHEET_SWITCH:<gid>:direct/popup/missing.
                            // Java на Win poll'ит через 2.5s и если :missing —
                            // fallback на loadUrl (полный reload, но юзер
                            // попадёт на лист гарантированно).
                            val targetGid = tab.gid.toString()
                            suppressNextUrlNavigation = browser != null
                            SheetsViewBridge.switchNativeSheetTab(tab)
                            activeTabName = tab.originalName
                            val isWin = System.getProperty("os.name")
                                ?.lowercase()?.contains("win") == true
                            if (isWin) {
                                workspaceScope.launch {
                                    // §TZ-DESKTOP-NATIVE-2026-05 0.8.36 — continuous
                                    // polling вместо fixed 2.5s wait. JS attempts
                                    // занимают: direct (3 × 150ms = 450ms) + popup
                                    // (~600ms expose+click+pick). Если :direct
                                    // придёт раньше — выходим сразу и не ждём.
                                    // Если :missing — fallback loadUrl. Timeout 4s.
                                    val prefix = "OTLD:SHEET_SWITCH:$targetGid:"
                                    val deadline = System.currentTimeMillis() + 4_000L
                                    var msg: String? = null
                                    while (msg == null && System.currentTimeMillis() < deadline) {
                                        kotlinx.coroutines.delay(200)
                                        msg = browser?.popBridgeMessage(prefix)
                                    }
                                    if (msg != null && msg.endsWith(":missing")) {
                                        System.err.println("[OTLD][sheet-switch] $msg → loadUrl fallback")
                                        suppressNextUrlNavigation = false
                                        triggeredUrl = "https://docs.google.com/spreadsheets/d/${activeFile.id}/edit#gid=$targetGid"
                                    }
                                }
                            }
                        }
                    },
                    // §TZ-DESKTOP-UX-2026-04 — третья кнопка «Войти в Google»
                    // показывается только в Anonymous. При нажатии:
                    //  1. Browser navigate на ServiceLogin URL (явно loadUrl,
                    //     потому что в Anonymous browser уже на sheet и
                    //     re-state не триггерит загрузку).
                    //  2. Переключаемся в Authenticating → 60-сек polling.
                    //  3. Юзер логинится → SignedIn автоматически.
                    //     Не успел за 60s → fallback в Anonymous.
                    showGoogleLoginButton = loginChoice == GoogleLoginChoice.Anonymous,
                    onGoogleLoginClick = {
                        // §TZ-DESKTOP-UX-2026-04 — `AddSession` всегда показывает
                        // login-форму (в отличие от `ServiceLogin`, который при
                        // partial cookie может вернуть «Уже вошли как X»).
                        //
                        // Nonce в URL: после Authenticating timeout slot["main"]
                        // оставался revealed на том же AddSession URL, и второй
                        // click давал «кот долго идёт» — LaunchedEffect не видел
                        // url-change. Уникальный fragment (#fresh=ts) форсирует
                        // recompose → fresh load → fresh reveal cycle.
                        //
                        // §TZ-DESKTOP-UX-2026-05 0.8.52 — continue=sheetUrl
                        // (current active), не hardcoded WORKFLOW. После
                        // успешного login Chromium follows continue → юзер
                        // приземляется на тот же лист где был.
                        val continueUrl = java.net.URLEncoder.encode(sheetUrl, "UTF-8")
                        val nonce = System.currentTimeMillis()
                        loginChoice = GoogleLoginChoice.Authenticating
                        triggeredUrl =
                            "https://accounts.google.com/AddSession?continue=$continueUrl&hl=ru#fresh=$nonce"
                        runCatching { browser?.setVisible(true) }
                    },
                    showGoogleLogoutButton = loginChoice == GoogleLoginChoice.SignedIn,
                    onGoogleLogoutClick = { showLogoutConfirm = true },
                    // §TZ-DESKTOP-UX-2026-05 0.8.59 — Cancel button во время
                    // login. Юзер: «должна быть кнопка Отмена. нажали ее
                    // и возвращаемся в таблицу». Заменяет «Войти в Google»
                    // pill пока на Authenticating state. Click → break
                    // polling loop, return на текущий sheet.
                    showCancelLoginButton = loginChoice == GoogleLoginChoice.Authenticating,
                    onCancelLoginClick = {
                        triggeredUrl = sheetUrl
                        SheetsLoginPref.save(SheetsLoginPref.Mode.Anonymous)
                        loginChoice = GoogleLoginChoice.Anonymous
                    },
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    SheetsWebView(
                        url = triggeredUrl ?: initialUrl,
                        onBrowserReady = { browser = it },
                        modifier = Modifier.fillMaxSize(),
                        revealNonSpreadsheetPages = loginChoice == GoogleLoginChoice.Authenticating,
                    )
                    val lock = actionLock
                    // §TZ-0.10.9 — external splash trigger от App.kt при QR
                    // re-login (Sheets reload). Котик висит поверх browser
                    // до тех пор пока App.kt не сбросит флаг.
                    val externalSplash by SheetsViewBridge.externalSplashOverlay.collectAsState()
                    if (lock != null && currentTabLocked) {
                        SheetsActionLockOverlay(
                            lock = lock,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (isReloading || externalSplash) {
                        // Cat splash на время reload — браузер скрыт через
                        // setVisible(false), мы рисуем поверх «пустоты».
                        // §TZ-DESKTOP-NATIVE-2026-05 0.8.35 — showTitle=false
                        // (как Mac), чтобы не было "Запускаем" текста.
                        SheetsSplashOverlay(
                            state = SheetsRuntime.State.Initializing(""),
                            modifier = Modifier.fillMaxSize(),
                            showTitle = false,
                        )
                    } else if (loginChoice == GoogleLoginChoice.Detecting) {
                        // §TZ-DESKTOP-UX-2026-04 — quick 3-сек silent probe.
                        SheetsSplashOverlay(
                            state = SheetsRuntime.State.Initializing(""),
                            modifier = Modifier.fillMaxSize(),
                            showTitle = false,
                        )
                    }
                    // §TZ-DESKTOP-UX-2026-04 — confirm logout dialog. Browser
                    // прячем (showLogoutConfirm в needsWebviewHide) — иначе
                    // heavyweight WKWebView/JCEF перекрывает Compose Box.
                    if (showLogoutConfirm) {
                        GoogleLogoutConfirmDialog(
                            onYes = {
                                showLogoutConfirm = false
                                // §TZ-DESKTOP-UX-2026-04 — Google /Logout
                                // показывает свою страницу «Вы вышли. Войти?»
                                // и НЕ авто-redirect'ит на continue. Поэтому
                                // делаем pipeline:
                                //  1. cat splash (isReloading=true) скрывает
                                //     эту страницу от юзера.
                                //  2. navigate на /Logout — Google чистит cookies.
                                //  3. через 2с форсированный navigate на sheet
                                //     URL без login — таблица read-only.
                                //  4. дважды inject CSS-маска (как у refresh).
                                //  5. snimaem splash, юзер видит таблицу.
                                SheetsLoginPref.save(SheetsLoginPref.Mode.Anonymous)
                                loginChoice = GoogleLoginChoice.Anonymous
                                triggeredUrl = null
                                if (!isReloading) {
                                    isReloading = true
                                    workspaceScope.launch {
                                        val firstUrl = workflowFile.firstTabUrl()
                                        val continueUrl = java.net.URLEncoder.encode(firstUrl, "UTF-8")
                                        runCatching {
                                            browser?.loadUrl("https://accounts.google.com/Logout?continue=$continueUrl")
                                        }
                                        kotlinx.coroutines.delay(2_000)
                                        runCatching { browser?.loadUrl(firstUrl) }
                                        kotlinx.coroutines.delay(1_500)
                                        runCatching { browser?.evaluateJavaScript(SheetsCss.INJECT_JS) }
                                        kotlinx.coroutines.delay(800)
                                        runCatching { browser?.evaluateJavaScript(SheetsCss.INJECT_JS) }
                                        isReloading = false
                                    }
                                }
                            },
                            onNo = { showLogoutConfirm = false },
                            rightInset = modalRightInset,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            else -> SheetsSplashOverlay(state = s, modifier = Modifier.fillMaxSize())
        }
    }

    // Password prompt overlay для actions с requiresPassword (например TECH NAME).
    // §TZ-DESKTOP-0.10.13 — валидация серверная: prompt просто собирает
    // password и передаёт в executeAction(action, password). Сервер ответит
    // wrong_password если не совпало (см. handleRunScript на сервере).
    val pwAction = pendingPasswordAction
    if (pwAction != null && pwAction.requiresPassword) {
        SheetsPasswordPrompt(
            actionLabel = pwAction.label,
            rightInset = modalRightInset,
            onSubmit = { password ->
                pendingPasswordAction = null
                executeAction(pwAction, password)
            },
            onDismiss = { pendingPasswordAction = null },
        )
    }
}
