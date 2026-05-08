package com.example.otlhelper.desktop.sheets.nativeweb

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
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
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.sheets.SheetsBrowserController
import com.example.otlhelper.desktop.sheets.SheetsCss
import com.example.otlhelper.desktop.sheets.SheetsRuntime
import com.example.otlhelper.desktop.sheets.SheetsSplashOverlay
import com.sun.jna.Pointer
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §TZ-DESKTOP-NATIVE-2026-05 0.8.5 — dedicated single-thread executor для
 * ВСЕХ JNA calls в WebView2.
 *
 * **Why:** ICoreWebView2Controller имеет thread affinity к thread'у где создан.
 * Все subsequent calls (put_ParentWindow, put_Bounds, Navigate, ExecuteScript)
 * должны быть **с того же thread'а**. Если разные — `put_ParentWindow` падает
 * с HRESULT 0x802a000c (WebView2 thread affinity error).
 *
 * Раньше использовали `Dispatchers.IO` который мульти-потоковый pool — каждый
 * `withContext(webView2Dispatcher)` мог попасть на любой worker → разные threads
 * → controller в bad state.
 */
// §TZ-DESKTOP-NATIVE-2026-05 0.8.10 — single-thread ScheduledExecutorService.
// Single thread (т.к. WebView2 controllers имеют thread affinity), но
// scheduling позволяет message pump запускать как короткие periodic tasks
// (16ms каждый PeekMessage) без блокировки executor для других coroutines
// (createWebView, attachToWindow, loadUrl etc.).
private val webView2Executor = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "WebView2Worker").apply { isDaemon = true }
}
private val webView2Dispatcher = webView2Executor.asCoroutineDispatcher()

/**
 * §TZ-DESKTOP-NATIVE-2026-05 0.8.8 — full cleanup при app Quit.
 * Вызывать из tray menu "Выход" перед exitApplication() — destroy все webview
 * controllers (заkrыvaet Edge subprocesses) + shutdown executor.
 */
fun shutdownWebView2() {
    pumpMessagesActive.set(false)
    runCatching { pumpScheduledFuture?.cancel(true) }
    runCatching {
        if (WinWebViewNative.isWindows) {
            WinWebViewNative.instance.destroyAllWebViews()
        }
    }
    runCatching { webView2Executor.shutdownNow() }
}

private val pumpMessagesActive = java.util.concurrent.atomic.AtomicBoolean(false)
@Volatile private var pumpScheduledFuture: ScheduledFuture<*>? = null

/**
 * §TZ-DESKTOP-NATIVE-2026-05 0.8.10 — periodic message pump через scheduledExecutor.
 *
 * WebView2 events доставляются через Win32 DispatchMessage на STA thread
 * (WebView2Worker). Без pump events сидят в очереди → NavigationStarting/Completed
 * handlers не fire.
 *
 * 0.8.9 пытался использовать infinite loop в execute() — это монополизировало
 * single-thread executor, и `withContext(webView2Dispatcher) { ... }` для
 * createOrFail/attach/loadUrl блокировался навсегда. 0.8.10 использует
 * scheduleAtFixedRate — короткие задачи (PeekMessage non-blocking) каждые 16ms,
 * между ними executor свободен для других coroutines.
 */
internal fun startWebView2MessagePump() {
    if (!pumpMessagesActive.compareAndSet(false, true)) return
    if (!WinWebViewNative.isWindows) return
    WinSheetsLog.log("=== WebView2 message pump scheduling (16ms period) ===")
    pumpScheduledFuture = webView2Executor.scheduleAtFixedRate(
        {
            if (pumpMessagesActive.get()) {
                runCatching { WinWebViewNative.instance.pumpMessages() }
            }
        },
        0L, 16L, TimeUnit.MILLISECONDS,
    )
}

/**
 * §TZ-DESKTOP-NATIVE-2026-05 0.8.3 — Java-side debug log.
 * Пишется в `%LOCALAPPDATA%\.otldhelper\webview2\debug-java.log` (Win)
 * или `~/.otldhelper/debug-java-mac.log` (Mac, см. MacSheetsLog).
 *
 * 0.8.18 — добавлены structured event tags для сравнения timeline между
 * Win и Mac. Юзер прогоняет одинаковый scenario на обоих → сравнение
 * показывает где Win ведёт себя иначе.
 */
private object WinSheetsLog {
    private val ts: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val path: String by lazy {
        val base = System.getenv("LOCALAPPDATA")
            ?: System.getProperty("user.home", ".")
        val dir = File(base, ".otldhelper/webview2")
        runCatching { dir.mkdirs() }
        File(dir, "debug-java.log").absolutePath
    }

    fun log(msg: String) {
        runCatching {
            File(path).appendText("[${LocalTime.now().format(ts)}] $msg\n")
        }
    }

    /** Structured event log: `[HH:MM:SS.mmm] [event=mount] details` */
    fun event(tag: String, details: String = "") {
        runCatching {
            val line = "[${LocalTime.now().format(ts)}] [event=$tag] $details\n"
            File(path).appendText(line)
        }
    }
}

/**
 * §TZ-DESKTOP-NATIVE-2026-05 — Compose обёртка над WebView2 (Win).
 *
 * Структурно зеркалит [MacSheetsWebView], но без multi-instance preload —
 * single-controller модель. File switch = `loadUrl(newUrl)`. Trade-off:
 * переключение между файлами WORKFLOW↔OTIF5 будет ~2-3с reload (как было
 * с KCEF до 0.4.x round 4), но это и так был paint поведение KCEF.
 * Multi-instance preload можно добавить во второй итерации.
 *
 * # Init flow
 *
 * 1. Compose mount → [LaunchedEffect] создаёт [WinSheetsWebViewState], вызывает
 *    `setUserDataFolder(~/.otldhelper/webview2)` → `createWebViewWithSettings`.
 *    Init синхронный (~50-300ms блокирует EDT, не критично — это раз на mount).
 * 2. `attachToWindow` — `SetParent` под HWND ComposeWindow.
 * 3. `setVisible(false)` до тех пор пока CSS-маска не применилась.
 * 4. `loadUrl` → polling currentUrl → когда docs.google.com/spreadsheets и
 *    `!isLoading` → eval CSS-маска → reveal через 3.2-4.8s delay (Google
 *    рендерит Sheets ленивно; меньше задержка = юзер видит chrome).
 *
 * # Cookies persistence
 *
 * UserDataFolder = `%LOCALAPPDATA%\.otldhelper\webview2\`. Setting через
 * `WinWebViewNative.setUserDataFolder(path)` ДО `createWebView`. Edge WebView2
 * сохраняет Google login cookies в `EBWebView/Default/Cookies` SQLite. Survives
 * рестарты.
 *
 * # Keyboard
 *
 * `setBrowserAcceleratorKeysEnabled(true)` (default) — Edge перехватывает
 * Ctrl+V/C/X/A/Z в input полях нативно, юзер видит обычное paste behavior.
 * AWT KeyEventDispatcher для Cmd+V на Mac не нужен на Win — Edge всё делает
 * через ICoreWebView2_2::add_AcceleratorKeyPressed.
 */
internal class WinSheetsWebViewState(
    private val javaScriptEnabled: Boolean = true,
    private val allowsFileAccess: Boolean = true,
) {
    internal var webViewId: Long by mutableStateOf(0L)
        private set

    internal var isAttached: Boolean by mutableStateOf(false)
        private set

    var currentUrl: String? by mutableStateOf(null)
        private set

    var isLoading: Boolean by mutableStateOf(false)
        private set

    /** Оригинальный URL без cache-bust query — нужен для корректного
     *  detection hash-only change. Internal чтобы LaunchedEffect мог
     *  detect hash-only change и не reset reveal pipeline. */
    internal var lastLoadedOriginalUrl: String? = null
        private set

    /** Last applied frame для skip duplicate setWebViewFrame в polling. */
    private var lastFrame: androidx.compose.ui.geometry.Rect? = null

    private var navigationCallback: WinNavigationCallback? = null

    /** Возвращает: 0 = success, 1 = Runtime missing, 2 = init error (не Runtime).
     *  Synchronized — LaunchedEffect может вызвать дважды конкурентно
     *  (recompose composeWindow + url) → без synchronized два параллельных
     *  createWebView → first fail на CO_E_NOTINITIALIZED (PoC race), second
     *  success → leak первого controller. С 0.8.4 обе проблемы решены. */
    @Synchronized
    fun createOrFail(): Int {
        WinSheetsLog.log("createOrFail() called on thread ${Thread.currentThread().name}, current webViewId=$webViewId")
        if (webViewId != 0L) {
            WinSheetsLog.log("createOrFail: already created, returning 0")
            return 0
        }
        if (!WinWebViewNative.isWindows) {
            WinSheetsLog.log("createOrFail: not Windows, abort")
            return 2
        }

        return runCatching {
            val userDataFolder = userDataFolder()
            File(userDataFolder).mkdirs()
            WinSheetsLog.log("userDataFolder=$userDataFolder")

            // Первый JNA touch — здесь может вылететь UnsatisfiedLinkError если
            // DLL не загрузилась (отсутствует в bundle, или native deps missing).
            WinSheetsLog.log("Calling JNA setUserDataFolder...")
            WinWebViewNative.instance.setUserDataFolder(userDataFolder)
            WinSheetsLog.log("JNA setUserDataFolder OK")

            WinSheetsLog.log("Calling JNA isWebView2RuntimeAvailable...")
            val runtimeOk = WinWebViewNative.instance.isWebView2RuntimeAvailable()
            WinSheetsLog.log("isWebView2RuntimeAvailable=$runtimeOk")
            if (!runtimeOk) return@runCatching 1

            WinSheetsLog.log("Calling JNA createWebViewWithSettings(js=$javaScriptEnabled, fileAccess=$allowsFileAccess)...")
            webViewId = WinWebViewNative.instance.createWebViewWithSettings(
                javaScriptEnabled,
                allowsFileAccess,
            )
            WinSheetsLog.log("createWebViewWithSettings returned webViewId=$webViewId")

            if (webViewId != 0L) {
                WinWebViewNative.instance.setBrowserAcceleratorKeysEnabled(webViewId, true)
                WinSheetsLog.log("createOrFail: SUCCESS")
                0
            } else {
                WinSheetsLog.log("createOrFail: createWebView returned 0 (see C++ debug.log)")
                2
            }
        }.getOrElse { e ->
            WinSheetsLog.log("createOrFail EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            2
        }
    }

    /** Compat: bool create — true if success, false otherwise. */
    fun create(): Boolean = createOrFail() == 0

    fun attachToWindow(window: ComposeWindow): Boolean {
        if (webViewId == 0L) return false
        if (isAttached) return true

        return runCatching {
            // ComposeWindow.windowHandle на Win = HWND parent (long → Pointer).
            val windowPointer = Pointer(window.windowHandle)
            WinWebViewNative.instance.attachWebViewToWindow(webViewId, windowPointer)
            WinWebViewNative.instance.setWebViewVisible(webViewId, false)
            WinWebViewNative.instance.forceWebViewDisplay(webViewId)
            isAttached = true
            true
        }.getOrElse {
            println("[OTLD][Sheets][WebView2] attach failed: ${it.message}")
            false
        }
    }

    fun updateFrame(bounds: Rect, density: Float) {
        if (webViewId == 0L || !isAttached) return
        if (bounds.width <= 0f || bounds.height <= 0f) return

        // §TZ-DESKTOP-NATIVE-2026-05 0.8.16 — skip duplicate frame update
        // с tolerance. Compose recomposes могут давать sub-pixel changes
        // (1228.0 → 1227.95) — без tolerance каждый recompose триггерит
        // setWebViewFrame → юзер видит "фризы/мерцание" на границах.
        val cached = lastFrame
        if (cached != null
            && kotlin.math.abs(cached.left - bounds.left) < 0.5f
            && kotlin.math.abs(cached.top - bounds.top) < 0.5f
            && kotlin.math.abs(cached.width - bounds.width) < 0.5f
            && kotlin.math.abs(cached.height - bounds.height) < 0.5f) {
            return
        }
        lastFrame = bounds

        runCatching {
            // §TZ-DESKTOP-NATIVE-2026-05 0.8.17 — убрали forceWebViewDisplay
            // и JS resize event из updateFrame. На каждый recompose
            // (даже spurious через polling) форсили SetWindowPos HWND_TOP +
            // window.dispatchEvent('resize') → Edge перерисовывал webview →
            // юзер видел "фризы по границам / постоянное обновление".
            // Edge сам справляется с put_Bounds через NotifyParentWindowPositionChanged
            // (вызывается внутри setWebViewFrame в C++).
            WinWebViewNative.instance.setWebViewFrame(
                webViewId = webViewId,
                x = bounds.left.toInt(),
                y = bounds.top.toInt(),
                width = bounds.width.toInt(),
                height = bounds.height.toInt(),
            )
            // §TZ-DESKTOP-NATIVE-2026-05 0.8.33 — Sheets layout recalc через
            // resize event. Mac делает это на каждый updateFrame и поэтому
            // не имеет bottom strip — Sheets reacts на window resize.
            // Win не имел этого с 0.8.17 (был spurious флэш, но cache-skip
            // на дубликате bounds выше в этой функции теперь предотвращает
            // spurious calls). Resize дёргается только при реальном
            // изменении geometry → Sheets recalc'нет canvas, strip уйдёт
            // без необходимости кликать toggle "Скрыть меню".
            WinWebViewNative.instance.evaluateJavaScript(
                webViewId,
                "try{window.dispatchEvent(new Event('resize'));}catch(e){}",
            )
        }
    }

    fun refresh() {
        if (webViewId == 0L) return
        runCatching {
            isLoading = WinWebViewNative.instance.webViewIsLoading(webViewId)
            currentUrl = WinWebViewStrings.currentUrl(webViewId)
        }
    }

    fun loadUrl(url: String) {
        if (webViewId == 0L) {
            WinSheetsLog.log("loadUrl: webViewId=0, skip url=$url")
            return
        }
        // §TZ-DESKTOP-NATIVE-2026-05 0.8.21 — REVERT 0.8.20 JS hash approach.
        // window.location.hash = X не triggers Sheets internal sheet switch
        // в Edge WebView2 (events fire но Sheets handler не реагирует).
        // Возвращаемся к cache-bust loadUrl — full reload, медленнее но
        // ГАРАНТИРОВАННО работает (юзер видит правильный лист после reveal).
        val previousOriginal = lastLoadedOriginalUrl ?: ""
        val previousBase = previousOriginal.substringBefore('#').substringBefore('?')
        val incomingBase = url.substringBefore('#').substringBefore('?')
        val isHashOnlyChange = previousBase.isNotEmpty() && previousBase == incomingBase

        val finalUrl = if (isHashOnlyChange) {
            val base = url.substringBefore('#')
            val hash = if (url.contains('#')) "#" + url.substringAfter('#') else ""
            val sep = if (base.contains('?')) '&' else '?'
            "${base}${sep}_=${System.currentTimeMillis()}${hash}"
        } else url

        WinSheetsLog.event("load-url", "hashOnly=$isHashOnlyChange cacheBust=${finalUrl != url}")
        currentUrl = finalUrl
        lastLoadedOriginalUrl = url
        isLoading = true
        WinWebViewNative.instance.loadURL(webViewId, finalUrl)
    }

    fun reload() {
        if (webViewId != 0L) WinWebViewNative.instance.webViewReload(webViewId)
    }

    fun evaluateJavaScript(code: String) {
        if (webViewId != 0L) WinWebViewNative.instance.evaluateJavaScript(webViewId, code)
    }

    fun popWebMessages(limit: Int = 32): List<String> {
        if (webViewId == 0L) return emptyList()
        val messages = ArrayList<String>()
        repeat(limit) {
            val msg = WinWebViewStrings.popWebMessage(webViewId) ?: return messages
            messages += msg
        }
        return messages
    }

    /**
     * §TZ-DESKTOP-NATIVE-2026-05 0.8.37 — Java-side accumulator buffer для
     * SHEET_SWITCH сообщений. C++ buffer (`webMessages` в NativeUtils.cpp)
     * единственный источник, мы pop'аем оттуда раз в polling cycle и
     * накапливаем в Java queue. Поллер фильтрует по prefix и удаляет
     * только matching message — другие остаются для других поллеров.
     *
     * Раньше (0.8.36) popBridgeMessage(prefix) drain'ил все native messages
     * → firstOrNull(matching) → НЕ-matching messages терялись навсегда.
     * Race: юзер кликает gid=A потом быстро gid=B. polling для A drain'ит
     * [A:direct, B:missing], возвращает A:direct, B:missing теряется →
     * Java для B не получает :missing → loadUrl fallback не fire'ится →
     * 🚚 / ГРАФИК ТМЦ не грузятся при tab click.
     */
    private val messageQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** Drain native messages в Java queue. Вызывать перед каждым pollMatching. */
    fun drainNativeMessages() {
        popWebMessages().forEach { messageQueue.offer(it) }
    }

    /** Найти первое сообщение с prefix и удалить его. Другие остаются в очереди. */
    fun pollMatching(prefix: String): String? {
        drainNativeMessages()
        val iter = messageQueue.iterator()
        while (iter.hasNext()) {
            val msg = iter.next()
            if (msg.startsWith(prefix)) {
                iter.remove()
                return msg
            }
        }
        return null
    }

    /** Очистить все накопленные сообщения (полезно при reset reveal). */
    fun clearMessageQueue() {
        messageQueue.clear()
        drainNativeMessages()
        messageQueue.clear()
    }

    fun setVisible(visible: Boolean) {
        if (webViewId == 0L) return
        WinWebViewNative.instance.setWebViewVisible(webViewId, visible)
        if (visible) {
            WinWebViewNative.instance.bringWebViewToFront(webViewId)
            WinWebViewNative.instance.forceWebViewDisplay(webViewId)
        }
    }

    fun setNavigationInterceptor(interceptor: ((String) -> Boolean)?) {
        if (webViewId == 0L) return
        navigationCallback = interceptor?.let { fn ->
            object : WinNavigationCallback {
                override fun invoke(webViewId: Long, url: String): Boolean = fn(url)
            }
        }
        WinWebViewNative.instance.setNavigationCallback(webViewId, navigationCallback)
    }

    fun destroy() {
        if (webViewId == 0L) return
        runCatching { WinWebViewNative.instance.destroyWebView(webViewId) }
        webViewId = 0L
        isAttached = false
        currentUrl = null
        navigationCallback = null
    }

    companion object {
        fun userDataFolder(): String {
            val localAppData = System.getenv("LOCALAPPDATA")
                ?: System.getProperty("user.home", ".")
            return File(File(localAppData, ".otldhelper"), "webview2").absolutePath
        }
    }
}

/**
 * §TZ-DESKTOP-NATIVE-2026-05 0.8.34 — externalVisible+revealed two-flag pattern,
 * портированный из MacSheetsWebView. Webview ФИЗИЧЕСКИ показывается только
 * когда ОБА флага true:
 *   • externalVisible — внешний контролёр (SheetsWorkspace, Search dialog) хочет
 *     видимости. По умолчанию true.
 *   • revealed — reveal-loop завершил mask injection и signal-wait.
 *
 * Без этой защиты SheetsWorkspace.kt:110 (`DisposableEffect(needsWebviewHide,
 * browser)`) при mount'е срабатывал на browser=non-null → setVisible(true) →
 * webview становился видимым ВО ВРЕМЯ загрузки страницы, юзер видел
 * Sheets bootstrap + mask application live.
 */
private class WinSheetsBrowserController(
    private val state: WinSheetsWebViewState,
    private val onRevealReset: () -> Unit,
    private val isRevealed: () -> Boolean,
    private val onExternalVisibilityChange: (Boolean) -> Unit,
    private val revealingFlow: kotlinx.coroutines.flow.StateFlow<Boolean>,
) : SheetsBrowserController {
    override val currentUrl: String?
        get() = state.currentUrl

    override val isRevealing: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = revealingFlow

    // §TZ-DESKTOP-NATIVE-2026-05 0.8.5 — все state ops через webView2Executor
    // (single-thread). WebView2 controller имеет thread affinity — calls
    // должны быть с того же thread'а где он создан, иначе HRESULT 0x802a000c.
    override fun loadUrl(url: String) {
        onRevealReset()
        webView2Executor.execute { state.loadUrl(url) }
    }
    override fun reload() {
        onRevealReset()
        webView2Executor.execute { state.reload() }
    }
    override fun evaluateJavaScript(code: String) {
        webView2Executor.execute { state.evaluateJavaScript(code) }
    }
    override fun popBridgeMessage(prefix: String?): String? {
        // §TZ-DESKTOP-NATIVE-2026-05 0.8.37 — pollMatching (вместо drain-all
        // firstOrNull) чтобы не терять сообщения для других tab clicks.
        // Через webView2Executor т.к. popWebMessages читает native pointer
        // (thread affinity).
        return runCatching {
            webView2Executor.submit<String?> {
                if (prefix != null) state.pollMatching(prefix)
                else {
                    state.drainNativeMessages()
                    null  // legacy path: just drain, no read
                }
            }.get(500, TimeUnit.MILLISECONDS)
        }.recoverCatching { e ->
            if (e is TimeoutException) null else throw e
        }.getOrNull()
    }
    override fun setVisible(visible: Boolean) {
        // §TZ-DESKTOP-NATIVE-2026-05 0.8.34 — gate setVisible(true) до
        // reveal-loop completion. Если revealed=false, физически НЕ показываем
        // webview — иначе юзер увидит сырой Sheets bootstrap. Сохраняем
        // externalVisible state — reveal-loop проверит при выставлении
        // setVisible(true) после signal.
        onExternalVisibilityChange(visible)
        webView2Executor.execute {
            // Re-read isRevealed() внутри executor чтобы избежать stale value
            // если revealed изменился пока task ждал в очереди.
            state.setVisible(visible && isRevealed())
        }
    }
}

@Composable
internal fun WinSheetsWebView(
    url: String,
    onBrowserReady: (SheetsBrowserController) -> Unit,
    modifier: Modifier = Modifier,
    revealNonSpreadsheetPages: Boolean = false,
) {
    val state = remember { WinSheetsWebViewState() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var composeWindow by remember { mutableStateOf<ComposeWindow?>(null) }
    var pendingFrameJob by remember { mutableStateOf<Job?>(null) }
    var pendingBounds by remember { mutableStateOf<Rect?>(null) }
    var revealed by remember { mutableStateOf(false) }
    var loadedUrl by remember { mutableStateOf<String?>(null) }
    var maskedUrl by remember { mutableStateOf<String?>(null) }
    var forcePlainReveal by remember { mutableStateOf(false) }
    // §TZ-DESKTOP-NATIVE-2026-05 0.8.34 — externalVisible from BrowserController.
    // Default true (юзер хочет webview видимым). Совмещается с revealed: webview
    // показывается только когда оба true. Так SheetsWorkspace.setVisible(true)
    // на mount не показывает сырой Sheets во время загрузки.
    var externalVisible by remember { mutableStateOf(true) }
    /** 0 = OK, 1 = Runtime missing, 2 = init error. */
    var initStatus by remember { mutableStateOf(0) }

    // §TZ-DESKTOP-UX-2026-05 0.8.59 — signal-based reveal status для
    // блокировки tab/file клик в SheetsWorkspace. true пока reveal pipeline
    // активна (cat splash виден, юзер не должен кликать другие tabs/files).
    val revealingFlow = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }

    LaunchedEffect(Unit) {
        WinSheetsLog.event("mount", "url='${url.take(120)}'")
        startWebView2MessagePump()
        // Retry up to 20 × 100ms = 2s — на Win Compose окно может ещё не быть
        // в focused/active state на момент первого frame'а LaunchedEffect.
        var found: ComposeWindow? = null
        repeat(20) { attempt ->
            found = findComposeWindow()
            if (found != null) {
                WinSheetsLog.log("findComposeWindow OK on attempt $attempt, hwnd=${found?.windowHandle}")
                return@repeat
            }
            delay(100)
        }
        if (found == null) {
            WinSheetsLog.log("findComposeWindow FAILED after 20 attempts (2s)")
        }
        composeWindow = found
    }

    LaunchedEffect(composeWindow, url) {
        WinSheetsLog.log("LaunchedEffect(composeWindow=${composeWindow?.let { "ComposeWindow@${it.windowHandle}" } ?: "null"}, url=$url) fired")
        val window = composeWindow ?: run {
            WinSheetsLog.log("LaunchedEffect: composeWindow null, abort")
            return@LaunchedEffect
        }
        val isSpreadsheet = url.contains("docs.google.com/spreadsheets", ignoreCase = true)

        if (state.webViewId == 0L) {
            val status = withContext(webView2Dispatcher) { state.createOrFail() }
            if (status != 0) {
                WinSheetsLog.log("createOrFail returned $status — showing placeholder")
                initStatus = status
                return@LaunchedEffect
            }
            WinSheetsLog.log("Calling attachToWindow...")
            val attached = withContext(webView2Dispatcher) { state.attachToWindow(window) }
            WinSheetsLog.log("attachToWindow returned $attached")
            // §TZ-DESKTOP-NATIVE-2026-05 0.8.6 — immediate initial frame.
            // Без этого webview visible но bounds=(0,0,0,0). Если pendingBounds
            // ещё null (Compose layout не завершён) — fallback на window size
            // в physical pixels (window.width — это Component.width в px).
            val initialBounds = pendingBounds ?: androidx.compose.ui.geometry.Rect(
                0f, 0f,
                window.width.toFloat(),
                window.height.toFloat(),
            )
            WinSheetsLog.log("Initial frame: bounds=$initialBounds, density=${density.density}")
            withContext(webView2Dispatcher) {
                state.updateFrame(initialBounds, density.density)
            }
            pendingBounds?.let { bounds ->
                withContext(webView2Dispatcher) {
                    state.updateFrame(bounds, density.density)
                }
            }
        }

        val controller = WinSheetsBrowserController(
            state = state,
            onRevealReset = {
                revealed = false
                maskedUrl = null
                forcePlainReveal = false
                state.setVisible(false)
                revealingFlow.value = true  // 0.8.59 — start blocking clicks
            },
            isRevealed = { revealed },
            onExternalVisibilityChange = { visible -> externalVisible = visible },
            revealingFlow = revealingFlow.asStateFlow(),
        )
        onBrowserReady(controller)

        if (loadedUrl != url) {
            // §TZ-DESKTOP-NATIVE-2026-05 0.8.33 — АТОМАРНЫЙ URL transition.
            // Раньше (0.8.32 баг): порядок был revealed=false → withContext{
            // setVisible(false); loadUrl(url) } → loadedUrl=url → maskedUrl=null.
            // Между установкой revealed=false и loadedUrl=url был window
            // ~50-100ms когда старый reveal-loop успевал проитерироваться,
            // увидеть `revealed=false` И `state.currentUrl=OLD_URL` (state.loadUrl
            // ещё не выполнился) И `maskedUrl=OLD_URL` (ещё не сброшен) →
            // `maskedUrl == current` → пропуск инжекта → reveal-done без
            // маски на новом URL. WORKFLOW грузился без скрытия headers.
            //
            // Фикс: ставим loadedUrl И maskedUrl=null ДО withContext.
            // Re-key LaunchedEffect фиксируется в этом snapshot до того как
            // state.loadUrl изменит state.currentUrl. Старый loop при
            // следующей итерации увидит loadedUrl=NEW_URL → guard в
            // spreadsheet branch отфильтрует.
            WinSheetsLog.log("Loading new URL: $url (isSpreadsheet=$isSpreadsheet, forcePlainReveal=${!isSpreadsheet && revealNonSpreadsheetPages})")
            revealed = false
            maskedUrl = null
            forcePlainReveal = !isSpreadsheet && revealNonSpreadsheetPages
            loadedUrl = url
            withContext(webView2Dispatcher) {
                state.setVisible(false)
                // §TZ-DESKTOP-NATIVE-2026-05 0.8.37 — сбросить накопленные
                // SHEET_SWITCH/SHEETS_READY messages из предыдущей навигации.
                // Иначе старые сообщения могут запутать новый pollMatching.
                state.clearMessageQueue()
                state.loadUrl(url)
            }
        }
    }

    // §TZ-DESKTOP-NATIVE-2026-05 0.8.19 — re-key on loadedUrl чтобы reveal
    // pipeline cancel'ился при URL change. Раньше LaunchedEffect(state) был
    // single coroutine forever — если юзер быстро менял лист пока reveal
    // wait 7sec для старого URL, по окончании waited setVisible(true)
    // показывал webview с НОВЫМ URL без CSS-маски (race).
    LaunchedEffect(state, loadedUrl) {
        WinSheetsLog.event("reveal-loop-start", "loadedUrl=${loadedUrl?.take(80)}")
        revealLoop@ while (true) {
            delay(180)
            // §TZ-DESKTOP-NATIVE-2026-05 0.8.20 — DISABLED periodic CSS reapply.
            // Каждый reapply вызывал DOM mutation → Sheets toolbar (формула bar)
            // и status bar (счётчик ячеек) re-render → юзер видел "фризы по
            // верхнему/нижнему краю" таблицы. CSS уже применяется 2 раза до
            // reveal — этого достаточно для статичной маски.
            // §TZ-DESKTOP-NATIVE-2026-05 0.8.6 — force frame update каждые 180ms.
            // Без этого webview attached + visible но bounds=(0,0,0,0) → не виден.
            // onGloballyPositioned может срабатывать раньше attach → setWebViewFrame
            // не вызывается. Polling гарантирует bounds установлен.
            val bounds = pendingBounds
            if (state.webViewId != 0L && bounds != null && bounds.width > 0 && bounds.height > 0) {
                withContext(webView2Dispatcher) {
                    state.updateFrame(bounds, density.density)
                }
            }
            withContext(webView2Dispatcher) { state.refresh() }
            if (state.webViewId == 0L || revealed) continue

            val current = state.currentUrl.orEmpty()
            val isSpreadsheet = current.contains("docs.google.com/spreadsheets", ignoreCase = true)
            if (forcePlainReveal && current.isNotBlank() && !isSpreadsheet) {
                delay(350)
                revealed = true
                revealingFlow.value = false  // 0.8.59 — unblock clicks
                // §TZ-DESKTOP-NATIVE-2026-05 0.8.34 — учитываем externalVisible.
                withContext(webView2Dispatcher) { state.setVisible(externalVisible) }
                continue
            }
            if (!state.isLoading && current.isNotBlank()) {
                if (!isSpreadsheet && !revealNonSpreadsheetPages && !forcePlainReveal) {
                    revealed = false
                    withContext(webView2Dispatcher) { state.setVisible(false) }
                    continue
                }
                if (isSpreadsheet) {
                    // §TZ-DESKTOP-NATIVE-2026-05 0.8.35 — guard: state.currentUrl
                    // ещё не сравнялся с loadedUrl (Navigate async). Если
                    // intended URL и current URL принадлежат разным spreadsheet
                    // ID — это race с LaunchedEffect cancellation. Skip.
                    //
                    // §TZ-DESKTOP-UX-2026-05 0.8.61 — НЕ применяем guard если
                    // intended URL не spreadsheet (например ServiceLogin или
                    // AddSession после login). Это НЕ race — это Google
                    // server-side redirect через continue param. Pre-fix юзер
                    // после login видел forever cat splash потому что guard
                    // блокировал reveal pipeline бесконечно.
                    val intended = loadedUrl
                    val intendedIsSpreadsheet = intended != null &&
                        intended.contains("docs.google.com/spreadsheets", ignoreCase = true)
                    if (intended != null && intendedIsSpreadsheet &&
                        !sameSpreadsheetId(intended, current)) {
                        delay(120)
                        continue@revealLoop
                    }
                    if (maskedUrl != current) {
                        // §TZ-DESKTOP-NATIVE-2026-05 0.8.35 — Mac approach (порт).
                        // Раньше (0.8.32-0.8.34) пытались signal-based reveal с
                        // SHEETS_READY token + reinject loop + 25s timeout. По
                        // логам signal приходил через ~25-26s (после timeout)
                        // потому что JS reinject сбрасывал `attempts` counter
                        // и safety net `attempts > 40` не срабатывал.
                        // Mac работает на 4.8s firstReveal + 0.65s second wait
                        // через простые delays БЕЗ signal-based logic. Win
                        // медленнее (Edge bootstrap'ит Sheets ~5-8 сек), но
                        // принцип тот же. Никакого signal, reinject в loop,
                        // taimeout race — пара INJECT_JS вызовов с фиксированным
                        // ожиданием как делает Mac.
                        WinSheetsLog.event("css-first-inject", "url='${current.take(80)}'")
                        withContext(webView2Dispatcher) {
                            state.evaluateJavaScript(SheetsCss.INJECT_JS)
                        }
                        maskedUrl = current

                        // §TZ-DESKTOP-NATIVE-2026-05 0.8.41 — короткий waitMs
                        // для hash-only navigations (same spreadsheet, разные gid).
                        // Sheets держит state в памяти, hash change = fast SPA
                        // navigation, bootstrap не нужен. Раньше было 8s firstReveal
                        // → юзер видел кота 14с при loadUrl fallback для проблемных
                        // listов. Теперь 2.5s + 1.5s second wait = 4с total.
                        val baseLoaded = (loadedUrl ?: "")
                            .substringBefore('?').substringBefore('#')
                        val baseCurrent = current
                            .substringBefore('?').substringBefore('#')
                        val isHashOnly = baseLoaded.isNotEmpty() && baseLoaded == baseCurrent
                        val firstReveal = current != loadedUrl
                        val waitMs = when {
                            isHashOnly -> 2_500L     // same-spreadsheet hash nav — fast
                            firstReveal -> 8_000L    // cross-spreadsheet или cold start
                            else -> 4_000L
                        }
                        WinSheetsLog.event(
                            "reveal-wait",
                            "firstReveal=$firstReveal isHashOnly=$isHashOnly waitMs=$waitMs",
                        )

                        // Periodic resize events во время ожидания. Sheets реагирует
                        // на window.resize и пересчитывает canvas height — это
                        // решает bottom strip без необходимости менять menu state.
                        // Mac делает это на каждый updateFrame; Win cache-skip
                        // пропускает spurious frame updates, поэтому здесь дёргаем
                        // явно во время reveal-wait.
                        val resizeIntervalMs = 2_000L
                        val deadline = System.currentTimeMillis() + waitMs
                        while (System.currentTimeMillis() < deadline) {
                            val remaining = deadline - System.currentTimeMillis()
                            delay(minOf(resizeIntervalMs, remaining.coerceAtLeast(0L)))
                            // Cross-spreadsheet abort check — если Sheets
                            // навигировал на другой документ, бросаем pipeline.
                            val nowUrl = state.currentUrl.orEmpty()
                            if (nowUrl.isNotEmpty() && !sameSpreadsheetId(current, nowUrl)) {
                                WinSheetsLog.event(
                                    "reveal-abort-navigation",
                                    "current='${nowUrl.take(80)}'",
                                )
                                continue@revealLoop
                            }
                            withContext(webView2Dispatcher) {
                                state.evaluateJavaScript(
                                    "try{window.dispatchEvent(new Event('resize'));}catch(e){}"
                                )
                            }
                        }
                        WinSheetsLog.event("css-second-inject")
                        withContext(webView2Dispatcher) {
                            state.evaluateJavaScript(SheetsCss.INJECT_JS)
                        }
                        delay(400)
                        // §TZ-DESKTOP-NATIVE-2026-05 0.8.53 — PRE-REVEAL TRIPLE-KICK
                        // (порт из MacSheetsWebView 0.8.49). Раньше Win делал
                        // post-reveal 1px nudge через scope.launch (см. 0.8.45):
                        // юзер видел strip на 250ms → snap. Mac 0.8.49 решил
                        // эту проблему перенесением nudge ВНУТРЬ pipeline ДО
                        // setVisible(true) — Sheets recompute проходит пока
                        // webview hidden, юзер сразу видит таблицу без strip.
                        //
                        // Triple-kick =
                        //  1. 10px frame shrink → restore (ResizeObserver fires;
                        //     1px было ниже порога, отсюда были полосы на 0.8.45)
                        //  2. body.style.zoom toggle через rAF (browser repaint)
                        //  3. click .docs-sheet-active-tab (Sheets handler
                        //     внутренний layout recompute — тот же путь что
                        //     юзерский tab click)
                        //  + delay(250) чтобы recompute закончился пока скрыт.
                        val bounds = pendingBounds
                        if (bounds != null && bounds.height >= 30f) {
                            val nudged = androidx.compose.ui.geometry.Rect(
                                left = bounds.left,
                                top = bounds.top,
                                right = bounds.right,
                                bottom = bounds.bottom - 10f,
                            )
                            WinSheetsLog.event("pre-reveal-nudge", "shrink 10px")
                            withContext(webView2Dispatcher) {
                                state.updateFrame(nudged, density.density)
                            }
                            delay(80)
                            withContext(webView2Dispatcher) {
                                state.updateFrame(bounds, density.density)
                            }
                        }
                        withContext(webView2Dispatcher) {
                            state.evaluateJavaScript(
                                """
                                (function() {
                                    try {
                                        if (document.body) {
                                            var saved = document.body.style.zoom || '';
                                            document.body.style.zoom = 0.9999;
                                            requestAnimationFrame(function() {
                                                try {
                                                    document.body.style.zoom = saved;
                                                    window.dispatchEvent(new Event('resize'));
                                                } catch (_) {}
                                            });
                                        }
                                        var active = document.querySelector('.docs-sheet-active-tab');
                                        if (active) {
                                            ['mousedown','mouseup','click'].forEach(function(t) {
                                                try { active.dispatchEvent(new MouseEvent(t, {bubbles:true,cancelable:true,view:window})); } catch(_) {}
                                            });
                                        }
                                    } catch (e) {}
                                })();
                                """.trimIndent()
                            )
                        }
                        delay(250)  // Sheets recompute завершается ПОКА webview hidden
                    }
                } else {
                    delay(250)
                }
                WinSheetsLog.event("reveal-done", "isSpreadsheet=$isSpreadsheet")
                revealed = true
                revealingFlow.value = false  // 0.8.59 — unblock clicks
                // §TZ-DESKTOP-NATIVE-2026-05 0.8.34 — учитываем externalVisible.
                // Если контролёр (например Search dialog) выставил setVisible(false),
                // не показываем webview даже после reveal-done.
                val show = externalVisible
                WinSheetsLog.event("set-visible-true", "external=$show")
                withContext(webView2Dispatcher) {
                    state.setVisible(show)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E10))
            .onGloballyPositioned { coordinates ->
                pendingBounds = coordinates.boundsInWindow()
                pendingFrameJob?.cancel()
                pendingFrameJob = scope.launch {
                    delay(16)
                    val bounds = pendingBounds ?: return@launch
                    val scale = density.density
                    withContext(webView2Dispatcher) {
                        state.updateFrame(bounds, scale)
                    }
                }
            },
    ) {
        when (initStatus) {
            1 -> WebView2RuntimeMissingPlaceholder(modifier = Modifier.fillMaxSize())
            2 -> WebView2InitErrorPlaceholder(modifier = Modifier.fillMaxSize())
            else -> if (!revealed) {
                // §TZ-DESKTOP-NATIVE-2026-05 0.8.32 — showTitle=false на Win
                // чтобы matched Mac UX: только кот + halo + 3 пульсирующие
                // точки, без подписи "Запускаем". Юзер: «как на мак осе версии».
                SheetsSplashOverlay(
                    state = SheetsRuntime.State.Initializing(""),
                    modifier = Modifier.fillMaxSize(),
                    showTitle = false,
                )
            }
        }
    }

    // §TZ-DESKTOP-UX-2026-05 0.8.58 — Ctrl+V/C/X/A через Java clipboard +
    // execCommand (port из MacSheetsWebView). Раньше полагались на
    // setBrowserAcceleratorKeysEnabled(true) — Edge должен handle нативно,
    // но в нашем embedded SwingPanel setup user gesture не достигает Edge
    // → clipboard policy block. Юзер: «не работает копировать в ячейках
    // выделять текст вставлять данные». Делаем сами через AWT KeyEventDispatcher.
    DisposableEffect(state) {
        val dispatcher = java.awt.KeyEventDispatcher { e ->
            if (e.id != java.awt.event.KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            val ctrl = e.isControlDown
            if (!ctrl) return@KeyEventDispatcher false
            when (e.keyCode) {
                java.awt.event.KeyEvent.VK_V -> {
                    val text = runCatching {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                    }.getOrNull().orEmpty()
                    if (text.isNotEmpty()) {
                        val escaped = buildString(text.length + 16) {
                            for (ch in text) when (ch) {
                                '\\' -> append("\\\\")
                                '"'  -> append("\\\"")
                                '\b' -> append("\\b")
                                '' -> append("\\f")
                                '\n' -> append("\\n")
                                '\r' -> append("\\r")
                                '\t' -> append("\\t")
                                ' ' -> append("\\u2028")
                                ' ' -> append("\\u2029")
                                else -> if (ch.code < 0x20) {
                                    append("\\u%04x".format(ch.code))
                                } else {
                                    append(ch)
                                }
                            }
                        }
                        webView2Executor.execute {
                            state.evaluateJavaScript(
                                """
                                (function() {
                                  var t = "$escaped";
                                  var el = document.activeElement;
                                  if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) {
                                    if (typeof el.value === 'string') {
                                      var s = el.selectionStart || 0, e2 = el.selectionEnd || 0;
                                      el.value = el.value.slice(0, s) + t + el.value.slice(e2);
                                      el.setSelectionRange(s + t.length, s + t.length);
                                      el.dispatchEvent(new Event('input', {bubbles: true}));
                                    } else {
                                      document.execCommand('insertText', false, t);
                                    }
                                  } else {
                                    document.execCommand('insertText', false, t);
                                  }
                                })();
                                """.trimIndent()
                            )
                        }
                    }
                    true
                }
                java.awt.event.KeyEvent.VK_C -> {
                    webView2Executor.execute {
                        state.evaluateJavaScript("document.execCommand('copy');")
                    }
                    true
                }
                java.awt.event.KeyEvent.VK_X -> {
                    webView2Executor.execute {
                        state.evaluateJavaScript("document.execCommand('cut');")
                    }
                    true
                }
                java.awt.event.KeyEvent.VK_A -> {
                    webView2Executor.execute {
                        state.evaluateJavaScript("document.execCommand('selectAll');")
                    }
                    true
                }
                else -> false
            }
        }
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(dispatcher)
        onDispose {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(dispatcher)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingFrameJob?.cancel()
            state.destroy()
        }
    }
}

/**
 * §TZ-DESKTOP-NATIVE-2026-05 0.8.33 — извлечение spreadsheet ID из URL для
 * сравнения "тот же документ или нет". Sheets во время bootstrap'а делает
 * location.replace со sub-параметрами (`?usp=...&pli=1#gid=X` etc.) →
 * полное сравнение строк бросает abort даже когда юзер не навигировал.
 * Сравнение по ID робаст к таким мутациям.
 */
private fun sameSpreadsheetId(a: String, b: String): Boolean {
    val regex = Regex("/spreadsheets/d/([^/?#]+)")
    val idA = regex.find(a)?.groupValues?.getOrNull(1) ?: return a == b
    val idB = regex.find(b)?.groupValues?.getOrNull(1) ?: return a == b
    return idA == idB
}

private fun findComposeWindow(): ComposeWindow? {
    val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    (focusManager.focusedWindow as? ComposeWindow)?.let { return it }
    (focusManager.activeWindow as? ComposeWindow)?.let { return it }
    return Window.getWindows().filterIsInstance<ComposeWindow>().firstOrNull { it.isVisible }
}

@Composable
private fun WebView2RuntimeMissingPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color(0xFF0E0E10)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "WebView2 Runtime не установлен",
                color = Color(0xFFE6E6E6),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Установите Microsoft Edge WebView2 Runtime\nили выполните: winget install Microsoft.EdgeWebView2",
                color = Color(0xFFA0A0A0),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WebView2InitErrorPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color(0xFF0E0E10)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Ошибка инициализации WebView2",
                color = Color(0xFFE6E6E6),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Лог: %LOCALAPPDATA%\\.otldhelper\\webview2\\debug.log\nОтправьте файл разработчику для диагностики.",
                color = Color(0xFFA0A0A0),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
