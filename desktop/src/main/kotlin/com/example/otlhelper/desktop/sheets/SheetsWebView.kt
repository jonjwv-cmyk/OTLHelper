package com.example.otlhelper.desktop.sheets

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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.SwingPanel
import com.example.otlhelper.desktop.sheets.nativeweb.MacSheetsWebView
import com.example.otlhelper.desktop.sheets.nativeweb.WinSheetsWebView
import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFBrowser
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRendering
import org.cef.handler.CefKeyboardHandler
import org.cef.handler.CefKeyboardHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest

/*
 * §TZ-DESKTOP 0.4.x — single-browser host для Sheets-зоны.
 *
 * Round 4 (2026-04-26): попытка multi-instance с CardLayout была откачена —
 * на macOS heavyweight Chromium NSView в CardLayout некорректно
 * инициализировался для не-активных карт (OTIF5 терял selection,
 * каждый switch повторно загружал страницу). Возврат к single-browser
 * с loadURL — стабильно работает, trade-off: switch файла = reload
 * (~2-3с), Google Sheets state теряется.
 *
 * **Альтернатива для будущего**: shared CefClient + N browsers + JLayeredPane
 * со setBounds-toggle вместо CardLayout. Требует тщательной отладки на macOS.
 */
private fun handlePaste(browser: CefBrowser) {
    val frame = browser.focusedFrame ?: browser.mainFrame
    runCatching { frame?.paste() }
}

private fun handleSelectAll(browser: CefBrowser) {
    val frame = browser.focusedFrame ?: browser.mainFrame
    runCatching { frame?.selectAll() }
}

private fun handleCopy(browser: CefBrowser) {
    val frame = browser.focusedFrame ?: browser.mainFrame
    runCatching { frame?.copy() }
}

private fun handleCut(browser: CefBrowser) {
    val frame = browser.focusedFrame ?: browser.mainFrame
    runCatching { frame?.cut() }
}

private fun handleUndo(browser: CefBrowser) {
    val frame = browser.focusedFrame ?: browser.mainFrame
    runCatching { frame?.undo() }
}

private fun handleRedo(browser: CefBrowser) {
    val frame = browser.focusedFrame ?: browser.mainFrame
    runCatching { frame?.redo() }
}

// CEF EventFlags — стандартные битовые маски modifiers в CefKeyEvent.
private const val EVENTFLAG_SHIFT_DOWN = 1 shl 1
private const val EVENTFLAG_CONTROL_DOWN = 1 shl 2
private const val EVENTFLAG_COMMAND_DOWN = 1 shl 7  // macOS ⌘

private class KcefSheetsBrowserController(
    private val browser: KCEFBrowser,
) : SheetsBrowserController {
    override val currentUrl: String?
        get() = browser.url

    override fun loadUrl(url: String) = browser.loadURL(url)
    override fun reload() = browser.reload()
    override fun evaluateJavaScript(code: String) {
        browser.executeJavaScript(code, browser.url ?: "", 0)
    }

    override fun paste() = handlePaste(browser)
    override fun selectAll() = handleSelectAll(browser)
    override fun copy() = handleCopy(browser)
    override fun cut() = handleCut(browser)
    override fun undo() = handleUndo(browser)
    override fun redo() = handleRedo(browser)
    override fun setFocused(focused: Boolean) = browser.setFocus(focused)
}

private fun revealComponentAfterMask(
    componentRef: AtomicReference<Component?>,
    coverRef: AtomicReference<SheetsLoadingCover?>,
    pageVisible: AtomicBoolean,
    revealDelayMs: Int,
) {
    pageVisible.set(true)
    SwingUtilities.invokeLater {
        Timer(revealDelayMs) {
            coverRef.get()?.fadeOut {
                componentRef.get()?.isVisible = true
            }
        }.apply {
            isRepeats = false
            start()
        }
    }
}

private fun hideComponent(
    componentRef: AtomicReference<Component?>,
    coverRef: AtomicReference<SheetsLoadingCover?>,
    pageVisible: AtomicBoolean,
) {
    pageVisible.set(false)
    SwingUtilities.invokeLater {
        componentRef.get()?.isVisible = false
        coverRef.get()?.showLoading()
    }
}

private class SheetsBrowserPanel(
    private val browserComponent: Component,
    private val cover: SheetsLoadingCover,
) : JLayeredPane() {
    init {
        layout = null
        background = java.awt.Color(0x0E, 0x0E, 0x10)
        add(browserComponent, Integer.valueOf(DEFAULT_LAYER))
        add(cover, Integer.valueOf(PALETTE_LAYER))
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = layoutChildren()
            override fun componentShown(e: ComponentEvent?) = layoutChildren()
        })
    }

    override fun doLayout() {
        super.doLayout()
        layoutChildren()
    }

    private fun layoutChildren() {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        browserComponent.setBounds(0, 0, w, h)
        cover.setBounds(0, 0, w, h)
    }
}

private class SheetsLoadingCover : JPanel(BorderLayout()) {
    private val alphaState = mutableFloatStateOf(1f)
    private var fadeTimer: Timer? = null

    init {
        isOpaque = true
        background = java.awt.Color(0x0E, 0x0E, 0x10)
        isVisible = true
        add(ComposePanel().apply {
            setContent {
                SheetsLoadingOverlay(alpha = alphaState.floatValue)
            }
        }, BorderLayout.CENTER)
    }

    fun showLoading() {
        fadeTimer?.stop()
        alphaState.floatValue = 1f
        isVisible = true
    }

    fun fadeOut(onDone: () -> Unit) {
        fadeTimer?.stop()
        var step = 0
        fadeTimer = Timer(24) {
            step += 1
            val alpha = (1f - step / 10f).coerceAtLeast(0f)
            alphaState.floatValue = alpha
            if (step >= 10) {
                fadeTimer?.stop()
                isVisible = false
                alphaState.floatValue = 1f
                onDone()
            }
        }.apply {
            isRepeats = true
            start()
        }
    }

    fun dispose() {
        fadeTimer?.stop()
    }
}

/**
 * §TZ-DESKTOP 0.4.0 — Compose-обёртка над KCEFBrowser.
 *
 * Browser создаётся один раз на mount и переиспользуется при смене [url] —
 * вызывается `loadURL(url)`, без пересоздания. Это критично: пересоздание
 * сбрасывает Google-сессию (cookies остаются на диске, но live state и
 * iframe'ы теряются → reload ленты на каждое переключение).
 *
 * Только вызывается ПОСЛЕ `SheetsRuntime.State.Ready` (вызывающий обязан
 * проверить состояние через `SheetsRuntime.state` flow).
 *
 * **onBrowserReady**: callback вызывается один раз когда KCEFBrowser создан
 * и доступен. Caller хранит ref для navigation / reload / Apps Script POST.
 */
@Composable
fun SheetsWebView(
    url: String,
    onBrowserReady: (SheetsBrowserController) -> Unit = {},
    modifier: Modifier = Modifier,
    revealNonSpreadsheetPages: Boolean = false,
) {
    if (SheetsRuntime.engine == SheetsRuntime.Engine.NATIVE) {
        if (isMacOs()) {
            MacSheetsWebView(
                url = url,
                onBrowserReady = onBrowserReady,
                modifier = modifier,
                revealNonSpreadsheetPages = revealNonSpreadsheetPages,
            )
        } else if (isWindows()) {
            // §TZ-DESKTOP-NATIVE-2026-05 — WebView2 (Edge) через JNA bridge.
            // Если WebView2 Runtime не установлен — WinSheetsWebView сам
            // показывает placeholder с инструкцией.
            WinSheetsWebView(
                url = url,
                onBrowserReady = onBrowserReady,
                modifier = modifier,
                revealNonSpreadsheetPages = revealNonSpreadsheetPages,
            )
        } else {
            SheetsUnavailablePlaceholder(modifier = modifier)
        }
        return
    }

    val shortcuts = com.example.otlhelper.desktop.ui.palette.LocalShortcuts.current
    val browserComponentRef = remember { AtomicReference<Component?>() }
    val coverRef = remember { AtomicReference<SheetsLoadingCover?>() }
    val pageVisible = remember { AtomicBoolean(false) }
    val firstSpreadsheetRevealPending = remember { AtomicBoolean(true) }

    // KCEF.newClientBlocking() требует чтобы init был завершён — гарантируется
    // нашим SheetsWorkspace (рендерит SheetsWebView только в Ready state).
    val client = remember {
        KCEF.newClientBlocking().apply {
            // §TZ-DESKTOP 0.4.0 коммит 2 — CSS-маска. На каждый успешный
            // load page инжектим стили которые прячут chrome Google Sheets.
            addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadStart(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    transitionType: CefRequest.TransitionType?,
                ) {
                    if (frame == null || !frame.isMain) return
                    hideComponent(browserComponentRef, coverRef, pageVisible)
                }

                override fun onLoadEnd(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    httpStatusCode: Int,
                ) {
                    if (frame == null || !frame.isMain) return
                    cefBrowser?.executeJavaScript(SheetsCss.INJECT_JS, frame.url, 0)
                    val isSpreadsheet = frame.url.contains("docs.google.com/spreadsheets", ignoreCase = true)
                    val revealDelay = when {
                        isSpreadsheet && firstSpreadsheetRevealPending.getAndSet(false) -> 8_000
                        isSpreadsheet -> 420
                        else -> 1_200
                    }
                    Timer(120) {
                        revealComponentAfterMask(browserComponentRef, coverRef, pageVisible, revealDelay)
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }

                override fun onLoadError(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    errorCode: CefLoadHandler.ErrorCode?,
                    errorText: String?,
                    failedUrl: String?,
                ) {
                    if (frame == null || !frame.isMain) return
                    revealComponentAfterMask(browserComponentRef, coverRef, pageVisible, 180)
                }
            })

            // §TZ-DESKTOP 0.4.0 — CefKeyboardHandler как правильный hook для
            // CMD+V/A/C/X/Z на macOS. AWT KeyListener на canvas НЕ работает
            // — Chromium NSView перехватывает keyboard events первым.
            addKeyboardHandler(object : CefKeyboardHandlerAdapter() {
                override fun onPreKeyEvent(
                    cefBrowser: CefBrowser?,
                    event: CefKeyboardHandler.CefKeyEvent?,
                    isKeyboardShortcut: BoolRef?,
                ): Boolean {
                    if (cefBrowser == null || event == null) return false
                    if (event.type != CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN) {
                        return false
                    }
                    val isMac = System.getProperty("os.name")?.lowercase()?.contains("mac") == true
                    val cmdMask = if (isMac) EVENTFLAG_COMMAND_DOWN else EVENTFLAG_CONTROL_DOWN
                    if ((event.modifiers and cmdMask) == 0) return false

                    return when (event.windows_key_code) {
                        86 -> { handlePaste(cefBrowser); true }
                        65 -> { handleSelectAll(cefBrowser); true }
                        67 -> { handleCopy(cefBrowser); true }
                        88 -> { handleCut(cefBrowser); true }
                        90 -> {
                            val redo = (event.modifiers and EVENTFLAG_SHIFT_DOWN) != 0
                            cefBrowser.executeJavaScript(
                                "document.execCommand('${if (redo) "redo" else "undo"}');",
                                cefBrowser.url, 0,
                            )
                            true
                        }
                        89 -> {
                            cefBrowser.executeJavaScript("document.execCommand('redo');", cefBrowser.url, 0)
                            true
                        }
                        else -> false
                    }
                }
            })
        }
    }

    var lastUrl by remember { mutableStateOf(url) }
    val browser = remember(client) {
        client.createBrowser(
            url,
            CefRendering.DEFAULT,
            /* isTransparent = */ false,
        )
    }
    val controller = remember(browser) { KcefSheetsBrowserController(browser) }

    // §TZ-DESKTOP 0.4.x round 4 — pre-mask body перед loadURL. Иначе
    // между моментом загрузки нового URL и onLoadEnd (CSS injection)
    // юзер видит ~300-500ms голый Google chrome (title-bar, share, и т.д.).
    // body visibility:hidden до тех пор пока не уберётся load handler-ом.
    LaunchedEffect(url) {
        if (url != lastUrl) {
            // Hash navigation in Google Sheets is unreliable: the URL can
            // change while the grid remains on the previous sheet. Use the
            // stable path: full URL navigation + visual mask.
            hideComponent(browserComponentRef, coverRef, pageVisible)
            runCatching {
                browser.executeJavaScript(
                    "if (document.body) document.body.style.visibility = 'hidden';",
                    browser.url ?: "",
                    0,
                )
            }
            browser.loadURL(url)
            lastUrl = url
        }
    }

    LaunchedEffect(controller) { onBrowserReady(controller) }

    DisposableEffect(browser) {
        shortcuts.onSheetsEdit = { action ->
            when (action) {
                        "paste" -> controller.paste()
                        "selectAll" -> controller.selectAll()
                        "copy" -> controller.copy()
                        "cut" -> controller.cut()
                        "undo" -> controller.undo()
                        "redo" -> controller.redo()
            }
        }
        shortcuts.setSheetsFocused(true)

        val keyDispatcher = java.awt.KeyEventDispatcher { e ->
            if (e.id != java.awt.event.KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            if (!shortcuts.sheetsFocused) return@KeyEventDispatcher false
            val isMac = System.getProperty("os.name")?.lowercase()?.contains("mac") == true
            val cmd = if (isMac) e.isMetaDown else e.isControlDown
            if (!cmd) return@KeyEventDispatcher false
            when (e.keyCode) {
                java.awt.event.KeyEvent.VK_V -> { controller.paste(); true }
                java.awt.event.KeyEvent.VK_A -> { controller.selectAll(); true }
                java.awt.event.KeyEvent.VK_C -> { controller.copy(); true }
                java.awt.event.KeyEvent.VK_X -> { controller.cut(); true }
                java.awt.event.KeyEvent.VK_Z -> {
                    if (e.isShiftDown) controller.redo() else controller.undo()
                    true
                }
                java.awt.event.KeyEvent.VK_Y -> { controller.redo(); true }
                else -> false
            }
        }
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(keyDispatcher)

        onDispose {
            shortcuts.onSheetsEdit = {}
            shortcuts.setSheetsFocused(false)
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(keyDispatcher)
        }
    }

    SwingPanel(
        modifier = modifier.fillMaxSize(),
        factory = {
            val component = browser.uiComponent
            val cover = SheetsLoadingCover()
            val panel = SheetsBrowserPanel(component, cover)
            browserComponentRef.set(component)
            coverRef.set(cover)
            component.isVisible = pageVisible.get()
            component.isFocusable = true
            component.addFocusListener(object : java.awt.event.FocusListener {
                override fun focusGained(e: java.awt.event.FocusEvent) {
                    controller.setFocused(true)
                    shortcuts.setSheetsFocused(true)
                }
                override fun focusLost(e: java.awt.event.FocusEvent) {
                    controller.setFocused(false)
                    shortcuts.setSheetsFocused(false)
                }
            })
            component.requestFocusInWindow()
            panel
        },
        update = { panel ->
            val w = panel.width
            val h = panel.height
            if (w > 0 && h > 0) browser.wasResized(w, h)
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            try { coverRef.get()?.dispose() } catch (_: Throwable) {}
            try { browser.dispose() } catch (_: Throwable) {}
            try { client.dispose() } catch (_: Throwable) {}
        }
    }
}

private fun isMacOs(): Boolean =
    System.getProperty("os.name")?.lowercase()?.contains("mac") == true

private fun isWindows(): Boolean =
    System.getProperty("os.name")?.lowercase()?.contains("win") == true

/**
 * §TZ-2.4.4 — placeholder UI для NATIVE engine на Windows.
 * KCEF на Win 11 крашит, WebView2 bridge не реализован → app должен
 * показать осмысленный экран а не падать с "CEF was not initialized".
 */
@Composable
private fun SheetsUnavailablePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BgApp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Sheets-зона временно недоступна на Windows",
                color = TextSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Лента, чат, опросы и админка работают нормально",
                color = TextTertiary,
                fontSize = 13.sp,
            )
        }
    }
}
