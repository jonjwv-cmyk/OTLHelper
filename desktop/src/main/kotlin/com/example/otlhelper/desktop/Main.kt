package com.example.otlhelper.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.OtldTheme
import com.example.otlhelper.desktop.ui.App
import kotlinx.coroutines.launch
import com.example.otlhelper.desktop.sheets.SheetsRuntime
import com.example.otlhelper.desktop.ui.LocalAppOverlayHost
import com.example.otlhelper.desktop.ui.main.AppTitleBar
import com.example.otlhelper.desktop.ui.palette.LocalShortcuts
import com.example.otlhelper.desktop.ui.palette.ShortcutDispatcher
import com.example.otlhelper.desktop.ui.palette.dispatchKeyEvent

private val isMac: Boolean =
    System.getProperty("os.name")?.lowercase()?.contains("mac") == true

fun main() {
    // §TZ-DESKTOP-0.10.4 — Single instance lock. Если уже запущено — поднимаем
    // существующее окно в фокус и exit'имся. Не разрешаем 2 параллельные копии.
    if (!com.example.otlhelper.desktop.core.SingleInstanceLock.acquireOrSignal()) {
        println("[OTL] App already running — focus signal sent, exiting")
        kotlin.system.exitProcess(0)
    }

    // §TZ-DESKTOP-DIST 0.5.1 — стираем оставшиеся расшифрованные media-tmp
    // от предыдущего запуска (если был краш — onCloseRequest не отработал).
    runCatching {
        com.example.otlhelper.desktop.data.security.AttachmentCache.wipeTempCache()
    }

    // §TZ-DESKTOP-0.9.1 — раз на старте: включаем JVM ProxySelector чтобы
    // он читал Windows IE/WPAD settings + чистим disabledSchemes для
    // tunneling NTLM/Basic. Должно быть ДО любого OkHttp builder'а.
    com.example.otlhelper.desktop.core.network.CorporateProxy.init()

    // §TZ-2.4.0 — pre-flight TCP ping primary + (optional) backup VPS.
    // Async: не блокирует UI startup.
    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { com.example.otlhelper.desktop.core.network.RouteState.warmRoute() }
        // §TZ-DESKTOP-0.9.1 — auto-detect corporate proxy (PowerShell call ~200ms).
        // Раз пропихиваем сразу чтобы первый OkHttp.rest builder уже знал маршрут.
        runCatching { com.example.otlhelper.desktop.core.network.CorporateProxy.detect() }
        runCatching {
            val routeDesc = com.example.otlhelper.desktop.data.network.HttpClientFactory.routeDescription()
            println("[OTL] Network route: $routeDesc")
        }

        // §TZ-DESKTOP-0.10.0 — auto-run network self-test ~30 sec после старта.
        // Цель: записать в OTL-debug.log реальные latency/status для diag URL'ов
        // (sslip.io vs IP direct vs CF) — юзер на корпе сможет потом проверить
        // что media routing работает быстро и без Касперского-warning'а.
        runCatching {
            kotlinx.coroutines.delay(30_000)
            com.example.otlhelper.desktop.core.network.NetworkSelfTest.run()
        }
    }

    // §TZ-2.4.0 — observability hook. ApiClient.onActionLatency → buffer →
    // server log_metrics. Раз в 60 сек / 30 запросов — batched flush.
    // §TZ-DESKTOP-0.10.2 — Дополнительно: action+status пишем в OTL-debug.log
    // ВСЕГДА (для 500 ошибок чтобы понимать какой action упал).
    com.example.otlhelper.desktop.data.network.ApiClient.onActionLatency =
        { action, durationMs, ok, httpStatus, errorCode ->
            com.example.otlhelper.desktop.core.metrics.NetworkMetricsBuffer.record(
                action = action,
                durationMs = durationMs,
                httpStatus = httpStatus,
                ok = ok,
                errorCode = errorCode,
            )
            // Логируем в OTL-debug.log:
            //   - все ошибки (HTTP != 200, или ok=false, или errorCode не пусто)
            //   - + сэмплирование 1/20 успехов, чтобы был контекст без захламления
            val isError = !ok || httpStatus !in 200..299 || errorCode.isNotBlank()
            if (isError || (System.currentTimeMillis() / 1000) % 20 == 0L) {
                runCatching {
                    val tag = if (isError) "[api-ERR]" else "[api]"
                    com.example.otlhelper.desktop.core.network.NetMetricsLogger.event(
                        "$tag action=$action http=$httpStatus ok=$ok ${durationMs}ms" +
                            if (errorCode.isNotBlank()) " err=$errorCode" else ""
                    )
                }
            }
        }
    com.example.otlhelper.desktop.core.metrics.NetworkMetricsBuffer.start()

    application { appContent() }
}

@androidx.compose.runtime.Composable
private fun ApplicationScope.appContent() {
    // §TZ-2.4.4 — close-to-background: при нажатии «X» окно прячется,
    // приложение остаётся в трее (mac status bar / win system tray).
    // Полный exit — только через tray-menu «Выход».
    var isWindowVisible by remember { mutableStateOf(true) }

    val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
    val shortcuts = remember { ShortcutDispatcher() }
    val useSheetsMenuBridge = SheetsRuntime.engine == SheetsRuntime.Engine.KCEF

    // §TZ-2.4.4 — Tray icon в системном трее. На Mac — статус-бар (сверху
    // справа, рядом с WiFi/battery), на Win — system tray (правый нижний
    // угол). Click по иконке открывает окно. Через context-menu — «Открыть»
    // и «Выход».
    // §TZ-DESKTOP-NATIVE-2026-05 0.8.11 — bring window to front при tray click.
    // isWindowVisible=true только показывает окно, но если оно minimized или
    // под другими window'ами — юзер не увидит. toFront() + requestFocus()
    // принудительно делает окно активным.
    val openWindow: () -> Unit = {
        isWindowVisible = true
        java.awt.Window.getWindows()
            .filterIsInstance<androidx.compose.ui.awt.ComposeWindow>()
            .firstOrNull()
            ?.let { w ->
                runCatching {
                    if (w.state == java.awt.Frame.ICONIFIED) w.state = java.awt.Frame.NORMAL
                    w.isVisible = true
                    w.toFront()
                    w.requestFocus()
                }
            }
    }

    // §TZ-DESKTOP-0.10.4 — connect SingleInstanceLock probe to openWindow.
    // Когда юзер пытается запустить вторую копию — старая получает «focus»
    // signal, и мы поднимаем окно в фокус (на любом OS thread → marshall на EDT).
    DisposableEffect(Unit) {
        com.example.otlhelper.desktop.core.SingleInstanceLock.onFocusRequested = {
            javax.swing.SwingUtilities.invokeLater { openWindow() }
        }
        onDispose {
            com.example.otlhelper.desktop.core.SingleInstanceLock.onFocusRequested = {}
        }
    }

    val performExit: () -> Unit = {
        runCatching { SheetsRuntime.dispose() }
        runCatching {
            com.example.otlhelper.desktop.data.security.AttachmentCache.wipeTempCache()
        }
        runCatching {
            com.example.otlhelper.desktop.sheets.nativeweb.shutdownWebView2()
        }
        exitApplication()
        Thread {
            Thread.sleep(500)
            kotlin.system.exitProcess(0)
        }.apply { isDaemon = true }.start()
    }

    // §TZ-DESKTOP-NATIVE-2026-05 0.8.12 — AWT TrayIcon вместо Compose Tray.
    // Compose Tray.onAction не fire'ит на single LMB click (это API limitation
    // и Win и Mac), а только на double-click. AWT TrayIcon с MouseListener
    // даёт явный контроль — single click открывает окно.
    DisposableEffect(Unit) {
        var trayIconRef: java.awt.TrayIcon? = null
        var appReopenedListener: Any? = null
        runCatching {
            if (java.awt.SystemTray.isSupported()) {
                val image = ::main.javaClass.classLoader.getResource("icon.png")
                    ?.let { javax.imageio.ImageIO.read(it) }
                if (image != null) {
                    val popup = java.awt.PopupMenu().apply {
                        add(java.awt.MenuItem("Открыть OTLD Helper").apply {
                            addActionListener { openWindow() }
                        })
                        addSeparator()
                        add(java.awt.MenuItem("Выход").apply {
                            addActionListener { performExit() }
                        })
                    }
                    val icon = java.awt.TrayIcon(image, "OTLD Helper", popup).apply {
                        isImageAutoSize = true
                        // Double-click handler.
                        addActionListener { openWindow() }
                        // Single-click handler — это ключевой фикс: Compose
                        // Tray единственно слушал double-click.
                        addMouseListener(object : java.awt.event.MouseAdapter() {
                            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                                if (e.button == java.awt.event.MouseEvent.BUTTON1) {
                                    openWindow()
                                }
                            }
                        })
                    }
                    java.awt.SystemTray.getSystemTray().add(icon)
                    trayIconRef = icon
                }
            }
        }
        // §TZ-DESKTOP-NATIVE-2026-05 0.8.12 — Mac dock icon click → reopen.
        // На Mac dock click fires AppReopenedEvent если app already running но
        // window hidden. Без listener — клик ignored.
        runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            val listener = java.awt.desktop.AppReopenedListener { _ -> openWindow() }
            desktop.addAppEventListener(listener)
            appReopenedListener = listener
        }
        onDispose {
            runCatching {
                trayIconRef?.let { java.awt.SystemTray.getSystemTray().remove(it) }
            }
            runCatching {
                (appReopenedListener as? java.awt.desktop.SystemEventListener)
                    ?.let { java.awt.Desktop.getDesktop().removeAppEventListener(it) }
            }
        }
    }

    Window(
        visible = isWindowVisible,
        onCloseRequest = {
            // §TZ-2.4.4 — закрытие = свернуть в трей, не exit. Полный exit
            // юзер делает через tray menu «Выход» или Cmd+Q (Mac) / Alt+F4
            // (Win обычно тоже; но Compose default = exit, поэтому
            // intercept'им).
            isWindowVisible = false
        },
        state = windowState,
        title = "OTLD Helper",
        // §TZ-DESKTOP-0.10.2 — иконка в title bar и taskbar окне (на Win
        // показывается слева в заголовке + в Alt-Tab + в taskbar). Без
        // этого параметра Compose Desktop показывает дефолтную "java duke"
        // или пустую иконку. icon.png лежит в desktop/src/main/resources.
        icon = painterResource("icon.png"),
        undecorated = false,
        onPreviewKeyEvent = { ev -> dispatchKeyEvent(ev, shortcuts) },
    ) {
        // §TZ-2.4.4 — реактивно применяем dark appearance + transparent title bar.
        // Без NSAppearanceNameDarkAqua native title bar остаётся светло-серым
        // на macOS 26 (visible как "белая полоса" с traffic-lights). С dark
        // appearance OS красит chrome под app theme.
        DisposableEffect(window, windowState.placement) {
            if (isMac) {
                val rootPane = window.rootPane
                rootPane?.putClientProperty("apple.awt.fullWindowContent", true)
                rootPane?.putClientProperty("apple.awt.transparentTitleBar", true)
                // §TZ-2.4.4 — заставить title bar быть тёмным.
                // NSAppearanceNameDarkAqua = macOS Dark Mode.
                rootPane?.putClientProperty("apple.awt.windowAppearance", "NSAppearanceNameDarkAqua")
                window.title = ""
            }
            // §0.10.26 — RETRY Win dark title bar. В 0.10.4 был reverted
            // т.к. ломал KCEF Sheets webview (DWM compositor invalidation).
            // Сейчас Sheets на WebView2 (не KCEF) — попробуем снова.
            // Если ломает Sheets рендер — rollback в 0.10.27.
            else if (System.getProperty("os.name", "").lowercase().contains("win")) {
                runCatching {
                    com.example.otlhelper.desktop.sheets.nativeweb.WinDarkTitleBar.apply(window)
                }
            }
            onDispose { }
        }

        // §TZ-2.4.2 — MenuBar только на macOS+KCEF (NSMenu paste-bridge для
        // AWT canvas focus issue). На Win и macOS-NATIVE — не нужен.
        if (useSheetsMenuBridge && isMac) {
            MenuBar {
                Menu("Правка") {
                    Item(
                        text = "Вставить",
                        shortcut = KeyShortcut(Key.V, meta = true),
                        enabled = shortcuts.sheetsFocused,
                        onClick = { shortcuts.onSheetsEdit("paste") },
                    )
                    Item(
                        text = "Копировать",
                        shortcut = KeyShortcut(Key.C, meta = true),
                        enabled = shortcuts.sheetsFocused,
                        onClick = { shortcuts.onSheetsEdit("copy") },
                    )
                    Item(
                        text = "Вырезать",
                        shortcut = KeyShortcut(Key.X, meta = true),
                        enabled = shortcuts.sheetsFocused,
                        onClick = { shortcuts.onSheetsEdit("cut") },
                    )
                    Item(
                        text = "Выделить всё",
                        shortcut = KeyShortcut(Key.A, meta = true),
                        enabled = shortcuts.sheetsFocused,
                        onClick = { shortcuts.onSheetsEdit("selectAll") },
                    )
                    Separator()
                    Item(
                        text = "Отменить",
                        shortcut = KeyShortcut(Key.Z, meta = true),
                        enabled = shortcuts.sheetsFocused,
                        onClick = { shortcuts.onSheetsEdit("undo") },
                    )
                    Item(
                        text = "Повторить",
                        shortcut = KeyShortcut(Key.Z, meta = true, shift = true),
                        enabled = shortcuts.sheetsFocused,
                        onClick = { shortcuts.onSheetsEdit("redo") },
                    )
                }
            }
        }

        OtldTheme {
            CompositionLocalProvider(
                LocalShortcuts provides shortcuts,
                LocalAppOverlayHost provides window.layeredPane,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BgApp),
                ) {
                    AppTitleBar(windowState = windowState, onClose = { isWindowVisible = false })
                    App()
                }
            }
        }
    }
}
