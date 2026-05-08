package com.example.otlhelper.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * §TZ-DESKTOP 0.4.x — host для модальных overlay'ев которые должны
 * рендериться **внутри** главного окна (а не отдельным OS-window),
 * **поверх** heavyweight webview Sheets-зоны.
 *
 * **Why JLayeredPane MODAL_LAYER:** lightweight Compose Box overlay не
 * покрывает heavyweight Chromium/WKWebView NSView (см. memory rule).
 * Отдельный DialogWindow — отдельное OS-окно, чувствуется детачнутым,
 * не двигается with main app. Решение — поместить **новую ComposePanel**
 * (heavyweight Skiko surface) в `JLayeredPane.MODAL_LAYER` главного окна,
 * выше DEFAULT_LAYER где живёт main Compose UI + Sheets webview. AWT
 * layered pane корректно z-order'ит heavyweight peers.
 *
 * **Result:** overlay визуально внутри main window (двигается, ресайзится
 * вместе с ним), покрывает webview, intercept'ит все клики в области.
 *
 * **CompositionContext propagation** через `rememberCompositionContext()`
 * — overlay's Compose tree наследует CompositionLocals (theme, shortcuts)
 * от главного дерева → не нужно providing их повторно.
 */
val LocalAppOverlayHost = compositionLocalOf<JLayeredPane?> { null }

private val isWindows: Boolean =
    System.getProperty("os.name")?.lowercase()?.contains("win") == true

/**
 * Mounts [content] как overlay поверх главного окна.
 *
 * - **macOS**: ComposePanel в [LocalAppOverlayHost] MODAL_LAYER. Heavyweight
 *   Skiko surface корректно перекрывает heavyweight WKWebView NSView;
 *   overlay визуально внутри main window (двигается, ресайзится с ним).
 * - **Windows**: отдельный [DialogWindow] (undecorated, transparent если
 *   поддерживается, modal). Причина: на Win JLayeredPane MODAL_LAYER не
 *   z-order'ит ComposePanel поверх heavyweight Chromium HWND — overlay
 *   получался белым. DialogWindow создаёт top-level OS-window которое
 *   гарантированно выше HWND child'ов. Из правила из памяти 0.4.x
 *   «overlay в зоне Chromium/JCEF SwingPanel = ОБЯЗАТЕЛЬНО DialogWindow».
 *
 * [onDismiss] нужен для Win-варианта — DialogWindow.onCloseRequest. На Mac
 * можно не передавать (существующая логика scrim + click-outside в content
 * сама закрывает overlay).
 */
@Composable
fun AppOverlay(
    onDismiss: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    if (isWindows) {
        WindowsDialogOverlay(onDismiss = onDismiss, content = content)
        return
    }
    val pane = LocalAppOverlayHost.current ?: return
    // updated.value пере-присваивается на каждый outer recompose; inner
    // Compose tree (внутри ComposePanel) подписывается на State и triggers
    // рекомпозицию при смене ссылки. Это даёт reactivity между outer
    // SearchDialog state (query, results) и inner ComposePanel rendering
    // даже без parent CompositionContext (не поддерживается в Compose
    // Multiplatform 1.7.3 ComposePanel.setContent).
    val updatedContent by rememberUpdatedState(content)
    DisposableEffect(pane) {
        val composePanel = ComposePanel().apply {
            isOpaque = false
            background = Color(0, 0, 0, 0)
            setContent { updatedContent() }
        }
        // JPanel wrapper с BorderLayout — proven pattern (см. SheetsLoadingCover
        // в SheetsWebView.kt). Direct ComposePanel в JLayeredPane null-layout
        // имеет проблемы с mouse event routing — клики не доходят до Compose
        // tree даже при правильных bounds. JPanel(BorderLayout) корректно
        // sizes child + properly registers с AWT focus/event manager.
        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            background = Color(0, 0, 0, 0)
            add(composePanel, BorderLayout.CENTER)
        }

        fun syncBounds() {
            val w = maxOf(pane.width, pane.parent?.width ?: 0).coerceAtLeast(1)
            val h = maxOf(pane.height, pane.parent?.height ?: 0).coerceAtLeast(1)
            wrapper.setBounds(0, 0, w, h)
            wrapper.revalidate()
            wrapper.repaint()
        }
        syncBounds()

        val rootWindow = SwingUtilities.getWindowAncestor(pane)
        val rootListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = syncBounds()
            override fun componentMoved(e: ComponentEvent?) = syncBounds()
            override fun componentShown(e: ComponentEvent?) = syncBounds()
        }
        rootWindow?.addComponentListener(rootListener)
        val paneListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = syncBounds()
        }
        pane.addComponentListener(paneListener)

        // POPUP_LAYER держим выше WKWebView и основного Compose-слоя, чтобы
        // модальные кнопки получали клики, а не отдавали их таблице под ними.
        pane.add(wrapper, JLayeredPane.POPUP_LAYER as Any)
        pane.revalidate()
        pane.repaint()

        // Defer once to ensure layout finishes before final sync.
        SwingUtilities.invokeLater {
            syncBounds()
            composePanel.requestFocusInWindow()
        }

        onDispose {
            rootWindow?.removeComponentListener(rootListener)
            pane.removeComponentListener(paneListener)
            pane.remove(wrapper)
            pane.revalidate()
            pane.repaint()
        }
    }
}

/**
 * §TZ-DESKTOP-DIST 0.5.1 — Win-вариант [AppOverlay] через [DialogWindow].
 *
 * Размер совпадает с main window для full-screen scrim feel — content уже
 * рисует свой scrim Box(fillMaxSize).clickable. Undecorated + transparent
 * чтобы dialog window не показывал свой title bar (наш scrim +
 * центрированный card покрывают всё).
 *
 * Если transparent не поддержан системой (старые Win) — fallback в opaque
 * full-screen dialog без scrim. Это не идеал, но overlay видим.
 */
@Composable
private fun WindowsDialogOverlay(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    // §TZ-2.4.5 — раньше rememberDialogState() без size → default 400x300 dp
    // → юзер на Win видел "узкое вытянутое окно" вместо нормального overlay.
    // Берём размер матчащий main window + центрирование на screen, чтобы
    // scrim Box(fillMaxSize) реально покрывал больше площади.
    val state = rememberDialogState(
        size = DpSize(1280.dp, 820.dp),
        position = WindowPosition.Aligned(Alignment.Center),
    )
    DialogWindow(
        onCloseRequest = onDismiss,
        state = state,
        title = "",
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = true,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}
