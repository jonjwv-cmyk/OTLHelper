package com.example.otlhelper.desktop.sheets.nativeweb

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.example.otlhelper.desktop.sheets.SheetsBrowserController
import com.example.otlhelper.desktop.sheets.SheetsCss
import com.example.otlhelper.desktop.sheets.SheetsRegistry
import com.example.otlhelper.desktop.sheets.SheetsRuntime
import com.example.otlhelper.desktop.sheets.SheetsSplashOverlay
import com.sun.jna.Pointer
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.io.File
import java.net.URLDecoder
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §TZ-DESKTOP-NATIVE-2026-05 0.8.18 — Mac-side debug log для параллельной
 * диагностики Mac+Win timeline. Юзер прогоняет одинаковый scenario на
 * обоих → сравнение показывает где Win ведёт себя иначе чем Mac (где
 * всё работает идеально).
 *
 * Path: `~/.otldhelper/webview2/debug-java-mac.log`
 */
internal object MacSheetsLog {
    private val ts: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val path: String by lazy {
        val home = System.getProperty("user.home", ".")
        val dir = File(home, ".otldhelper/webview2")
        runCatching { dir.mkdirs() }
        File(dir, "debug-java-mac.log").absolutePath
    }

    fun event(tag: String, details: String = "") {
        // Legacy log файл (для совместимости со старыми анализаторами).
        runCatching {
            val line = "[${LocalTime.now().format(ts)}] [event=$tag] $details\n"
            File(path).appendText(line)
        }
        // §0.11.13 — дубль в общий ~/Desktop/otl-debug.log который юзер
        // присылает. Тэг WV-MAC-{tag}: отделяем от Win событий.
        runCatching {
            com.example.otlhelper.desktop.core.debug.DebugLogger.log(
                "WV-MAC", "$tag $details"
            )
        }
    }
}

internal class MacSheetsWebViewState(
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

    private var navigationCallback: MacNavigationCallback? = null

    fun create(): Boolean {
        if (webViewId != 0L) return true
        if (!MacWebViewNative.isMacOS) return false

        return runCatching {
            webViewId = MacWebViewNative.instance.createWebViewWithSettings(
                javaScriptEnabled,
                allowsFileAccess,
            )
            webViewId != 0L
        }.getOrElse {
            println("[OTLD][Sheets][WKWebView] create failed: ${it.message}")
            false
        }
    }

    fun attachToWindow(window: ComposeWindow): Boolean {
        if (webViewId == 0L) return false
        if (isAttached) return true

        return runCatching {
            val windowPointer = Pointer(window.windowHandle)
            MacWebViewNative.instance.attachWebViewToWindow(webViewId, windowPointer)
            MacWebViewNative.instance.setWebViewVisible(webViewId, false)
            MacWebViewNative.instance.forceWebViewDisplay(webViewId)
            isAttached = true
            true
        }.getOrElse {
            println("[OTLD][Sheets][WKWebView] attach failed: ${it.message}")
            false
        }
    }

    fun updateFrame(bounds: Rect, density: Float, parentHeight: Float) {
        if (webViewId == 0L || !isAttached) return
        if (bounds.width <= 0f || bounds.height <= 0f) return

        runCatching {
            MacWebViewNative.instance.setWebViewFrameFlipped(
                webViewId = webViewId,
                x = (bounds.left / density).toDouble(),
                y = (bounds.top / density).toDouble(),
                width = (bounds.width / density).toDouble(),
                height = (bounds.height / density).toDouble(),
                parentHeight = parentHeight.toDouble(),
            )
            MacWebViewNative.instance.forceWebViewDisplay(webViewId)
            MacWebViewNative.instance.evaluateJavaScript(
                webViewId,
                "try{window.dispatchEvent(new Event('resize'));}catch(e){}",
            )
        }
    }

    fun refresh() {
        if (webViewId == 0L) return
        runCatching {
            isLoading = MacWebViewNative.instance.webViewIsLoading(webViewId)
            currentUrl = MacWebViewStrings.currentUrl(webViewId)
        }
    }

    fun loadUrl(url: String) {
        if (webViewId == 0L) return
        MacSheetsLog.event("load-url", "url='${url.take(120)}'")
        currentUrl = url
        isLoading = true
        MacWebViewNative.instance.loadURL(webViewId, url)
    }

    fun reload() {
        if (webViewId != 0L) MacWebViewNative.instance.webViewReload(webViewId)
    }

    fun evaluateJavaScript(code: String) {
        if (webViewId == 0L) return
        MacSheetsLog.event("js-eval", "len=${code.length}")
        MacWebViewNative.instance.evaluateJavaScript(webViewId, code)
    }

    fun setVisible(visible: Boolean) {
        if (webViewId == 0L) return
        MacSheetsLog.event("set-visible", "visible=$visible")
        MacWebViewNative.instance.setWebViewVisible(webViewId, visible)
        if (visible) {
            MacWebViewNative.instance.bringWebViewToFront(webViewId)
            MacWebViewNative.instance.forceWebViewDisplay(webViewId)
        }
    }

    fun setNavigationInterceptor(interceptor: ((String) -> Boolean)?) {
        if (webViewId == 0L) return
        navigationCallback = interceptor?.let { fn ->
            object : MacNavigationCallback {
                override fun invoke(webViewId: Long, url: String): Boolean = fn(url)
            }
        }
        MacWebViewNative.instance.setNavigationCallback(webViewId, navigationCallback)
    }

    fun destroy() {
        if (webViewId == 0L) return
        runCatching { MacWebViewNative.instance.destroyWebView(webViewId) }
        webViewId = 0L
        isAttached = false
        currentUrl = null
        navigationCallback = null
    }
}

private class MacSheetsBrowserController(
    private val slot: MacSheetsSlot,
    private val beforeNavigation: () -> Unit = {},
) : SheetsBrowserController {
    override val currentUrl: String?
        get() = slot.state.currentUrl

    override val isRevealing: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = slot.revealingFlow

    override fun loadUrl(url: String) {
        beforeNavigation()
        slot.state.loadUrl(url)
    }
    override fun reload() {
        beforeNavigation()
        slot.state.reload()
    }
    override fun evaluateJavaScript(code: String) = slot.state.evaluateJavaScript(code)
    override fun setVisible(visible: Boolean) {
        slot.externalVisible = visible
        slot.state.setVisible(visible && slot.revealed)
    }
}

private class MacSheetsSlot(
    val key: String,
    val state: MacSheetsWebViewState,
) {
    var initialized by mutableStateOf(false)
    var loadedUrl by mutableStateOf<String?>(null)
    var maskedUrl by mutableStateOf<String?>(null)
    var revealed by mutableStateOf(false)
    var externalVisible by mutableStateOf(true)
    var forcePlainReveal by mutableStateOf(false)

    /** §TZ-DESKTOP-UX-2026-05 0.8.59 — signal-based reveal status для
     *  блокировки tab/file клик в SheetsWorkspace. true пока reveal pipeline
     *  активна (cat splash виден). Выставляется в loadUrl/beforeNavigation
     *  и сбрасывается в false после revealed=true в reveal pipeline.
     *  §0.11.4 — INITIAL = true (см. WinSheetsWebView §0.11.4 коммент). */
    val revealingFlow = kotlinx.coroutines.flow.MutableStateFlow(true)

    val controller = MacSheetsBrowserController(this) {
        revealed = false
        maskedUrl = null
        forcePlainReveal = false
        state.setVisible(false)
        revealingFlow.value = true  // start reveal cycle
    }
}

@Composable
internal fun MacSheetsWebView(
    url: String,
    onBrowserReady: (SheetsBrowserController) -> Unit,
    modifier: Modifier = Modifier,
    revealNonSpreadsheetPages: Boolean = false,
) {
    val slots = remember { mutableStateMapOf<String, MacSheetsSlot>() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var composeWindow by remember { mutableStateOf<ComposeWindow?>(null) }
    var activeKey by remember { mutableStateOf<String?>(null) }
    var pendingFrameJob by remember { mutableStateOf<Job?>(null) }
    var pendingBounds by remember { mutableStateOf<Rect?>(null) }
    var parentHeight by remember { mutableStateOf(800f) }
    val activeSlot = activeKey?.let { slots[it] }

    LaunchedEffect(Unit) {
        MacSheetsLog.event("mount", "url='${url.take(120)}'")
        composeWindow = findComposeWindow()
    }

    LaunchedEffect(composeWindow, url) {
        val window = composeWindow ?: return@LaunchedEffect
        val key = spreadsheetKey(url)
        val slot = slots.getOrPut(key) { MacSheetsSlot(key, MacSheetsWebViewState()) }
        val plainPage = key == "main"

        if (!slot.initialized && slot.state.create() && slot.state.attachToWindow(window)) {
            withContext(Dispatchers.IO) {
                parentHeight = MacWebViewNative.instance
                    .getWindowContentHeight(Pointer(window.windowHandle))
                    .toFloat()
            }
            pendingBounds?.let { bounds ->
                withContext(Dispatchers.IO) {
                    slot.state.updateFrame(bounds, density.density, parentHeight)
                }
            }
            slot.initialized = true
        }

        slots.values.forEach { other ->
            other.state.setVisible(other.key == key && other.revealed && other.externalVisible)
        }
        activeKey = key
        onBrowserReady(slot.controller)

        if (slot.loadedUrl != url) {
            slot.revealed = false
            slot.revealingFlow.value = true  // 0.8.59 — start blocking clicks
            slot.forcePlainReveal = plainPage && revealNonSpreadsheetPages
            slot.state.setVisible(false)
            slot.state.loadUrl(url)
            slot.loadedUrl = url
            slot.maskedUrl = null
        } else if (slot.revealed) {
            slot.state.setVisible(slot.externalVisible)
            slot.revealingFlow.value = false  // already revealed, no block
        }
    }

    // §TZ-DESKTOP-NATIVE-2026-05 0.8.48 — triple-kick recompute при смене
    // активного slot (catches возврат на cached spreadsheet).
    LaunchedEffect(activeKey) {
        val key = activeKey ?: return@LaunchedEffect
        val slot = slots[key] ?: return@LaunchedEffect
        delay(250)
        if (!slot.revealed) return@LaunchedEffect
        val bounds = pendingBounds ?: return@LaunchedEffect
        if (bounds.height < 30f) return@LaunchedEffect
        val nudged = androidx.compose.ui.geometry.Rect(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom - 10f,
        )
        MacSheetsLog.event("frame-nudge-active-change", "key=$key shrink 10px")
        withContext(Dispatchers.IO) {
            slot.state.updateFrame(nudged, density.density, parentHeight)
        }
        delay(80)
        withContext(Dispatchers.IO) {
            slot.state.updateFrame(bounds, density.density, parentHeight)
        }
        slot.state.evaluateJavaScript(
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

    LaunchedEffect(slots) {
        while (true) {
            delay(180)
            val snapshot = slots.values.toList()
            withContext(Dispatchers.IO) {
                snapshot.forEach { slot -> slot.state.refresh() }
            }

            val slot = activeKey?.let { slots[it] } ?: continue
            if (!slot.initialized || slot.revealed) continue

            val current = slot.state.currentUrl.orEmpty()
            val isSpreadsheet = current.contains("docs.google.com/spreadsheets", ignoreCase = true)
            if (slot.forcePlainReveal && current.isNotBlank() && !isSpreadsheet) {
                delay(350)
                slot.revealed = true
                slot.revealingFlow.value = false  // 0.8.59 — unblock clicks
                if (activeKey == slot.key) {
                    slots.values.forEach { other ->
                        other.state.setVisible(other.key == slot.key && other.externalVisible)
                    }
                }
                continue
            }
            if (!slot.state.isLoading && current.isNotBlank()) {
                if (!isSpreadsheet && !revealNonSpreadsheetPages && !slot.forcePlainReveal) {
                    slot.revealed = false
                    slot.state.setVisible(false)
                    continue
                }
                if (isSpreadsheet) {
                    if (slot.maskedUrl != current) {
                        MacSheetsLog.event("css-first-inject", "url='${current.take(80)}'")
                        slot.state.evaluateJavaScript(SheetsCss.INJECT_JS)
                        slot.maskedUrl = current
                    }
                    val firstReveal = current != slot.loadedUrl
                    val waitMs = if (firstReveal) 4_800L else 3_200L
                    MacSheetsLog.event("reveal-wait", "firstReveal=$firstReveal waitMs=$waitMs")
                    delay(waitMs)
                    MacSheetsLog.event("css-second-inject")
                    slot.state.evaluateJavaScript(SheetsCss.INJECT_JS)
                    delay(400)
                    // §TZ-DESKTOP-NATIVE-2026-05 0.8.49 — PRE-REVEAL triple-kick.
                    // 0.8.48 fired triple-kick через scope.launch+delay 300ms
                    // ПОСЛЕ revealed=true → юзер: «после кота вижу полосу и
                    // потом сразу подгонку». Strip визуально мелькает.
                    // Перенесли triple-kick ВНУТРЬ reveal pipeline — пока кот
                    // ещё показан и webview hidden. Sheets recompute проходит
                    // НЕЗАМЕТНО. Когда revealed=true → таблица сразу без strip.
                    val bounds = pendingBounds
                    if (bounds != null && bounds.height >= 30f) {
                        val nudged = androidx.compose.ui.geometry.Rect(
                            left = bounds.left,
                            top = bounds.top,
                            right = bounds.right,
                            bottom = bounds.bottom - 10f,
                        )
                        MacSheetsLog.event("pre-reveal-nudge", "shrink 10px")
                        withContext(Dispatchers.IO) {
                            slot.state.updateFrame(nudged, density.density, parentHeight)
                        }
                        delay(80)
                        withContext(Dispatchers.IO) {
                            slot.state.updateFrame(bounds, density.density, parentHeight)
                        }
                    }
                    slot.state.evaluateJavaScript(
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
                    delay(250)  // wait for Sheets recompute while still hidden
                } else {
                    // Login/permission pages must be visible so the user can act.
                    delay(250)
                }
                MacSheetsLog.event("reveal-done", "isSpreadsheet=$isSpreadsheet")
                slot.revealed = true
                slot.revealingFlow.value = false  // 0.8.59 — unblock clicks
                if (activeKey == slot.key) {
                    slots.values.forEach { other ->
                        other.state.setVisible(other.key == slot.key && other.externalVisible)
                    }
                }
                if (isSpreadsheet) {
                    val window = composeWindow
                    if (window != null) {
                        // §TZ-DESKTOP-0.10.13 — filesList (List), а не files (StateFlow).
                        SheetsRegistry.filesList.forEach { file ->
                            if (slots.containsKey(file.id)) return@forEach
                            val preload = MacSheetsSlot(file.id, MacSheetsWebViewState())
                            if (preload.state.create() && preload.state.attachToWindow(window)) {
                                pendingBounds?.let { bounds ->
                                    withContext(Dispatchers.IO) {
                                        preload.state.updateFrame(bounds, density.density, parentHeight)
                                    }
                                }
                                preload.state.setVisible(false)
                                preload.initialized = true
                                preload.loadedUrl = file.firstTabUrl()
                                preload.state.loadUrl(file.firstTabUrl())
                                slots[file.id] = preload
                            } else {
                                preload.state.destroy()
                            }
                        }
                    }
                }
            }
        }
    }

    // §TZ-DESKTOP-UX-2026-04 — Cmd+V/C/X/A на не-spreadsheet страницах
    // (Google login form). На WKWebView native NSView перехватывает keys и
    // AWT не получает их; но Google login принимает paste через
    // navigator.clipboard.readText() + execCommand. Регистрируем AWT
    // KeyEventDispatcher: на не-spreadsheet URL дёргаем JS, на spreadsheet
    // не trogaem (Google сам paste'ит native).
    DisposableEffect(activeSlot) {
        val slot = activeSlot
        val dispatcher = if (slot != null) {
            java.awt.KeyEventDispatcher { e ->
                if (e.id != java.awt.event.KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
                val cmd = e.isMetaDown
                if (!cmd) return@KeyEventDispatcher false
                // §TZ-DESKTOP-UX-2026-05 0.8.58 — раньше для spreadsheet делали
                // early return полагаясь на native NSView paste handler. Юзер:
                // «не работает копировать в ячейках выделять текст вставлять
                // данные табличные». WKWebView в нашем embedded SwingPanel
                // setup не получает proper user gesture для clipboard access —
                // native paste blocked clipboard policy. Теперь handle paste/
                // copy/cut/selectAll сами через Java clipboard + JS execCommand.
                when (e.keyCode) {
                    java.awt.event.KeyEvent.VK_V -> {
                        // §TZ-DESKTOP-UX-2026-04 — раньше использовали
                        // navigator.clipboard.readText() — WKWebView из-за
                        // security-policy показывал «Paste»-overlay (юзер
                        // должен был ещё раз нажать). Теперь читаем буфер
                        // на Java-стороне через Toolkit.systemClipboard и
                        // передаём готовую строку в JS — никакого confirm.
                        val text = runCatching {
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                        }.getOrNull().orEmpty()
                        if (text.isNotEmpty()) {
                            // JSON.stringify через kotlinx.serialization-style
                            // экранирование: backslash, кавычки, newlines,
                            // tab, control-chars, U+2028/U+2029 (line/para sep).
                            val escaped = buildString(text.length + 16) {
                                for (ch in text) when (ch) {
                                    '\\' -> append("\\\\")
                                    '"'  -> append("\\\"")
                                    '\b' -> append("\\b")
                                    '\u000c' -> append("\\f")
                                    '\n' -> append("\\n")
                                    '\r' -> append("\\r")
                                    '\t' -> append("\\t")
                                    '\u2028' -> append("\\u2028")
                                    '\u2029' -> append("\\u2029")
                                    else -> if (ch.code < 0x20) {
                                        append("\\u%04x".format(ch.code))
                                    } else {
                                        append(ch)
                                    }
                                }
                            }
                            slot.state.evaluateJavaScript(
                                """
                                (function() {
                                  const t = "$escaped";
                                  const el = document.activeElement;
                                  if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) {
                                    if (typeof el.value === 'string') {
                                      const s = el.selectionStart || 0, e2 = el.selectionEnd || 0;
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
                        true
                    }
                    java.awt.event.KeyEvent.VK_C -> {
                        slot.state.evaluateJavaScript("document.execCommand('copy');")
                        true
                    }
                    java.awt.event.KeyEvent.VK_X -> {
                        slot.state.evaluateJavaScript("document.execCommand('cut');")
                        true
                    }
                    java.awt.event.KeyEvent.VK_A -> {
                        slot.state.evaluateJavaScript("document.execCommand('selectAll');")
                        true
                    }
                    else -> false
                }
            }
        } else null
        if (dispatcher != null) {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(dispatcher)
        }
        onDispose {
            if (dispatcher != null) {
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .removeKeyEventDispatcher(dispatcher)
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
                    val window = composeWindow
                    withContext(Dispatchers.IO) {
                        val actualParentHeight = if (window != null) {
                            MacWebViewNative.instance
                                .getWindowContentHeight(Pointer(window.windowHandle))
                                .toFloat()
                        } else {
                            parentHeight
                        }
                        parentHeight = actualParentHeight
                        slots.values.forEach { slot ->
                            slot.state.updateFrame(bounds, scale, actualParentHeight)
                        }
                    }
                }
            },
    ) {
        if (activeSlot?.revealed != true) {
            SheetsSplashOverlay(
                state = SheetsRuntime.State.Initializing("Загрузка..."),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingFrameJob?.cancel()
            slots.values.forEach { it.state.destroy() }
        }
    }
}

private fun spreadsheetKey(url: String): String {
    // §TZ-DESKTOP-UX-2026-04 — баг: если URL = accounts.google.com/AddSession
    // ?continue=https%3A%2F%2Fdocs.google.com%2Fspreadsheets%2Fd%2F<id>%2F...,
    // findId на decoded'е возвращал ID из continue-параметра. Login-страница
    // попадала в "spreadsheet"-slot, plainPage=false, forcePlainReveal=false
    // и cover «Запускаем» никогда не убирался.
    // Фикс: ID считаем spreadsheet'ом ТОЛЬКО если URL начинается с docs.google.com.
    val isDocsSpreadsheet = url.contains("docs.google.com/spreadsheets", ignoreCase = true)
    if (!isDocsSpreadsheet) return "main"

    fun findId(value: String): String? =
        Regex("/spreadsheets/d/([^/?#]+)").find(value)?.groupValues?.getOrNull(1)

    findId(url)?.let { return it }
    val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
    findId(decoded)?.let { return it }
    return "main"
}

private fun findComposeWindow(): ComposeWindow? {
    val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    (focusManager.focusedWindow as? ComposeWindow)?.let { return it }
    (focusManager.activeWindow as? ComposeWindow)?.let { return it }
    return Window.getWindows().filterIsInstance<ComposeWindow>().firstOrNull { it.isVisible }
}
