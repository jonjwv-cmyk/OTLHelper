package com.example.otlhelper.desktop.sheets.nativeweb

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.W32APIOptions

/**
 * §TZ-DESKTOP-0.10.2 — Windows 10/11 dark title bar via DwmSetWindowAttribute.
 *
 * Юзер сообщил: «полоса системного окна белая, а всё приложение тёмное».
 * Это потому что Windows по дефолту красит non-client area (title bar)
 * под системную тему. Чтобы сделать тёмным — флаг
 * `DWMWA_USE_IMMERSIVE_DARK_MODE = 20`. С Win10 1903+ работает корректно.
 *
 * На Mac не трогаем — там через `apple.awt.windowAppearance` уже работает
 * (см. Main.kt где для isMac выставляется `NSAppearanceNameDarkAqua`).
 */
internal interface DwmApi : com.sun.jna.Library {
    fun DwmSetWindowAttribute(
        hwnd: HWND,
        attribute: Int,
        attrValue: Pointer,
        attrSize: Int,
    ): Int

    companion object {
        const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20

        val instance: DwmApi by lazy {
            Native.load("dwmapi", DwmApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}

object WinDarkTitleBar {
    /**
     * Применить тёмную тему к non-client area. Идемпотентно (можно вызывать
     * многократно, например после каждого windowState change).
     *
     * @return true если applied, false если не Windows / не удалось
     */
    fun apply(awtWindow: java.awt.Window): Boolean {
        if (!System.getProperty("os.name", "").lowercase().contains("win")) return false
        return runCatching {
            // Получаем HWND из AWT через peer/component reflection.
            // На JDK 17 awtWindow.getNativeHandle() недоступен — используем
            // ComponentPeer внутри Window или JNA helper'ом.
            val hwnd = HWND(Pointer(getHwnd(awtWindow)))
            val flag = com.sun.jna.Memory(4).apply { setInt(0, 1) }
            val result = DwmApi.instance.DwmSetWindowAttribute(
                hwnd,
                DwmApi.DWMWA_USE_IMMERSIVE_DARK_MODE,
                flag,
                4,
            )
            result == 0
        }.getOrElse { false }
    }

    /**
     * Извлекаем native HWND из AWT-окна через JDK internal API.
     * Compose Desktop's ComposeWindow extends JFrame which has .getPeer()
     * (deprecated in JDK 9+ but still works internally).
     */
    private fun getHwnd(window: java.awt.Window): Long {
        // На Windows AWT окна имеют WComponentPeer с public .getHWnd() методом.
        // Через reflection достаём — стабильнее чем sun.awt internal API.
        val peer = runCatching {
            val getPeerMethod = java.awt.Component::class.java.getDeclaredMethod("getPeer")
            getPeerMethod.isAccessible = true
            getPeerMethod.invoke(window)
        }.getOrNull() ?: error("AWT peer unavailable")

        return runCatching {
            val getHWndMethod = peer.javaClass.getMethod("getHWnd")
            getHWndMethod.invoke(peer) as Long
        }.getOrElse {
            // Fallback: JNA FindWindow по имени класса.
            // Если не удалось — silent fail (apply() вернёт false).
            val title = (window as? java.awt.Frame)?.title ?: "OTLD Helper"
            com.sun.jna.platform.win32.User32.INSTANCE
                .FindWindow(null, title)
                ?.let { Pointer.nativeValue(it.pointer) }
                ?: 0L
        }
    }
}
