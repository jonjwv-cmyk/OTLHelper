package com.example.otlhelper.desktop.sap

import com.example.otlhelper.desktop.core.debug.DebugLogger
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser

/**
 * §0.11.10 — глобальный hotkey Ctrl+Q (Win-only).
 *
 * Реализован через `User32.RegisterHotKey` (стандартный Windows API,
 * не требует low-level keyboard hook). Зарегистрированный hotkey
 * перехватывается **до** обычного приложения в фокусе — нажатие
 * Ctrl+Q в любом окне (SAP, Excel, браузер) сразу вызывает callback.
 *
 * Caveat: Ctrl+Q в других программах (если они его используют для
 * Quit) будет перехвачен нашим listener'ом. На практике Ctrl+Q
 * популярен только в Firefox / некоторых Linux-инструментах.
 *
 * Запускает отдельный поток с message pump (GetMessage), завершается
 * через PostThreadMessage WM_QUIT при stop().
 */
object GlobalCtrlQHotkey {

    private const val TAG = "CTRL_Q"
    private const val HOTKEY_ID = 0xC0DE
    private const val MOD_CONTROL = 0x0002
    private const val VK_Q = 0x51
    private const val WM_HOTKEY = 0x0312
    private const val WM_QUIT = 0x0012

    @Volatile private var hookThread: Thread? = null
    @Volatile private var hookThreadId: Int = 0
    @Volatile private var listener: (() -> Unit)? = null

    fun start(onPressed: () -> Unit) {
        if (hookThread?.isAlive == true) return
        listener = onPressed

        val thread = Thread({
            try {
                hookThreadId = Kernel32Ext.INSTANCE.GetCurrentThreadId()
                val ok = User32.INSTANCE.RegisterHotKey(
                    null,
                    HOTKEY_ID,
                    MOD_CONTROL,
                    VK_Q,
                )
                if (!ok) {
                    DebugLogger.warn(TAG, "RegisterHotKey failed (Ctrl+Q возможно занят)")
                    return@Thread
                }
                DebugLogger.log(TAG, "Ctrl+Q registered, entering message pump")

                val msg = WinUser.MSG()
                while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                    if (msg.message == WM_HOTKEY && msg.wParam.toInt() == HOTKEY_ID) {
                        val cb = listener
                        if (cb != null) {
                            // Run callback off-hotkey-thread чтобы быстрее
                            // вернуться к message pump
                            Thread(cb, "OTLD-CtrlQ-Trigger").apply {
                                isDaemon = true
                                start()
                            }
                        }
                    }
                }
                User32.INSTANCE.UnregisterHotKey(null, HOTKEY_ID)
                DebugLogger.log(TAG, "message pump exited")
            } catch (e: Throwable) {
                DebugLogger.error(TAG, "hotkey thread crashed", e)
            }
        }, "OTLD-CtrlQ-Pump")
        thread.isDaemon = true
        thread.start()
        hookThread = thread
    }

    fun stop() {
        val tid = hookThreadId
        if (tid != 0) {
            // Отправляем WM_QUIT в наш thread чтобы GetMessage вернул 0
            User32.INSTANCE.PostThreadMessage(
                tid,
                WM_QUIT,
                com.sun.jna.platform.win32.WinDef.WPARAM(0),
                com.sun.jna.platform.win32.WinDef.LPARAM(0),
            )
        }
        hookThread = null
        hookThreadId = 0
        listener = null
    }

    /** Минимальный JNA для GetCurrentThreadId. */
    private interface Kernel32Ext : com.sun.jna.Library {
        fun GetCurrentThreadId(): Int

        companion object {
            val INSTANCE: Kernel32Ext by lazy {
                com.sun.jna.Native.load("kernel32", Kernel32Ext::class.java) as Kernel32Ext
            }
        }
    }
}
