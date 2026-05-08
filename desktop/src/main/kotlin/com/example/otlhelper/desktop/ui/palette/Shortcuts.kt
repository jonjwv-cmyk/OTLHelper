package com.example.otlhelper.desktop.ui.palette

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * §TZ-DESKTOP 0.3.0 — общая шина keyboard-shortcuts.
 *
 * Заведена чтобы Window (в Main.kt) мог перехватывать клавиши GLOBAL'но
 * и дергать методы на этом объекте — а MainScreen подписывается на них
 * через присваивание лямбд (см. [MainScreen] → LaunchedEffect).
 *
 * Почему не CompositionLocal с mutable state?
 * — state-based подход бы работал, но требует отслеживания изменений
 *   через LaunchedEffect каждого поля. Прямые колбэки проще и не создают
 *   лишних recompositions.
 *
 * Для нового разработчика:
 *   • Window.onPreviewKeyEvent → dispatch(ev, shortcuts) в Main.kt
 *   • MainScreen привязывает колбэки в LaunchedEffect(Unit) { ... }
 *   • Добавить новый shortcut = новое поле-lambda + новая ветка в dispatch
 */
class ShortcutDispatcher {
    var onTogglePalette: () -> Unit = {}
    var onSwitchToChats: () -> Unit = {}
    var onSwitchToNews: () -> Unit = {}
    var onToggleSidebar: () -> Unit = {}
    var onToggleSearch: () -> Unit = {}
    var onCloseModals: () -> Unit = {}

    // §TZ-DESKTOP 0.4.0 — edit shortcuts для Sheets-зоны.
    // FocusListener в SheetsBrowserHost вызывает [setSheetsFocused]. Compose
    // MenuBar в Main.kt читает [sheetsFocused] state — реагирует на
    // изменение через recomposition (enabled у Item'ов).
    //
    // Когда sheetsFocused=true:
    //   • macOS Edit menu enabled → CMD+V/A/C/X/Z → menu action → onSheetsEdit
    //   • наш handler инжектит JS в активный input Chromium
    // Когда sheetsFocused=false (фокус в Compose TextField):
    //   • menu disabled → macOS не интерсептит → CMD+V идёт в Skiko keyDown
    //     → Compose TextField paste нативно
    private val sheetsFocusedState = mutableStateOf(false)
    val sheetsFocused: Boolean get() = sheetsFocusedState.value
    fun setSheetsFocused(value: Boolean) { sheetsFocusedState.value = value }
    var onSheetsEdit: (action: String) -> Unit = {}
}

/** CompositionLocal чтобы потомки (MainScreen) достали бэкендовский dispatcher. */
val LocalShortcuts = staticCompositionLocalOf { ShortcutDispatcher() }

/**
 * Обработчик KeyEvent для Window.onPreviewKeyEvent.
 * Mac: ⌘K, ⌘1, ⌘2, ⌘\
 * Win/Linux: Ctrl+K, Ctrl+1, Ctrl+2, Ctrl+\
 * Esc — на всех платформах.
 *
 * Возвращает true если событие обработано (предотвратит дальнейшую
 * обработку native-виджетами типа TextField).
 */
fun dispatchKeyEvent(ev: KeyEvent, shortcuts: ShortcutDispatcher): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    // Mac использует Meta (⌘), Win/Linux — Ctrl. Принимаем оба.
    val cmd = ev.isMetaPressed || ev.isCtrlPressed

    // App-shortcuts.
    when {
        cmd && ev.key == Key.K -> { shortcuts.onTogglePalette(); return true }
        cmd && ev.key == Key.F -> { shortcuts.onToggleSearch(); return true }
        cmd && ev.key == Key.One -> { shortcuts.onSwitchToChats(); return true }
        cmd && ev.key == Key.Two -> { shortcuts.onSwitchToNews(); return true }
        cmd && ev.key == Key.Backslash -> { shortcuts.onToggleSidebar(); return true }
        ev.key == Key.Escape -> { shortcuts.onCloseModals(); return true }
    }

    // §TZ-DESKTOP 0.4.0 — edit shortcuts (CMD+V/A/C/X/Z) обрабатываются через
    // нативный macOS NSMenu в Main.kt (см. MenuBar). На macOS focus в AWT
    // canvas Chromium → Compose НЕ получает события сюда. NSMenu shortcuts
    // работают через NSResponder chain, в обход focus issue.

    return false
}
