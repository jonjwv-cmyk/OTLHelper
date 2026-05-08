package com.example.otlhelper.desktop.sheets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Small browser surface used by Sheets UI.
 *
 * It keeps Google Sheets actions independent from the concrete engine:
 * KCEF is still available as fallback, while macOS can use the native
 * WKWebView implementation.
 */
interface SheetsBrowserController {
    val currentUrl: String?

    /**
     * §TZ-DESKTOP-UX-2026-05 0.8.59 — signal-based reveal status.
     *
     * True пока reveal pipeline активна (cat splash виден, webview hidden).
     * SheetsWorkspace через collectAsState блокирует tab/file клики — иначе
     * юзер кликает другой лист пока грузится первый → triggeredUrl change →
     * race condition, лист не тот что хотели.
     *
     * Native pipelines (Mac/Win) emit через MutableStateFlow в slot:
     * setVisible(true) после reveal-done → flow.value = false.
     */
    val isRevealing: StateFlow<Boolean>
        get() = DEFAULT_NOT_REVEALING

    fun loadUrl(url: String)
    fun reload()
    fun evaluateJavaScript(code: String)
    fun popBridgeMessage(prefix: String? = null): String? = null

    fun paste() {}
    fun selectAll() {}
    fun copy() {}
    fun cut() {}
    fun undo() {}
    fun redo() {}
    fun setFocused(focused: Boolean) {}

    /**
     * Hide/show webview entirely. Used by overlay scrim (CentralSearchDialog,
     * CommandPalette): heavyweight WKWebView/Chromium NSView eats mouse events
     * regardless of AWT layered-pane z-order on macOS — even когда ComposePanel
     * визуально выше. Прячем webview пока активен модал → ComposePanel в
     * MODAL_LAYER получает все клики корректно. После dismiss → re-show.
     */
    fun setVisible(visible: Boolean) {}

    companion object {
        /** Default flow для controllers которые не имплементируют isRevealing
         *  (KCEF fallback engine). Всегда false → блокировка не сработает. */
        val DEFAULT_NOT_REVEALING: StateFlow<Boolean> = MutableStateFlow(false)
    }
}
