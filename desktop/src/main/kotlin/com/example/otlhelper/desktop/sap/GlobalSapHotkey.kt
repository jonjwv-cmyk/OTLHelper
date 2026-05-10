package com.example.otlhelper.desktop.sap

import com.example.otlhelper.desktop.core.debug.DebugLogger
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser

/**
 * §0.11.10 → §0.11.11 — глобальный hotkey **Ctrl+Space** (Win-only).
 *
 * Через `User32.RegisterHotKey` (не нужен low-level keyboard hook,
 * не нужны admin permissions). Срабатывает в любом окне ОС.
 *
 * Раньше был Ctrl+Q (0.11.10) — заменён на Ctrl+Space по запросу юзера.
 */
object GlobalSapHotkey {

    private const val TAG = "SAP_HOTKEY"
    private const val HOTKEY_ID = 0xC0DE
    private const val MOD_CONTROL = 0x0002
    private const val VK_SPACE = 0x20
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
                    VK_SPACE,
                )
                if (!ok) {
                    DebugLogger.warn(TAG, "RegisterHotKey Ctrl+Space failed (возможно занят)")
                    return@Thread
                }
                DebugLogger.log(TAG, "Ctrl+Space registered, entering message pump")

                val msg = WinUser.MSG()
                while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                    if (msg.message == WM_HOTKEY && msg.wParam.toInt() == HOTKEY_ID) {
                        val cb = listener
                        if (cb != null) {
                            Thread(cb, "OTLD-SAP-Trigger").apply {
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
        }, "OTLD-SAP-HotkeyPump")
        thread.isDaemon = true
        thread.start()
        hookThread = thread
    }

    fun stop() {
        val tid = hookThreadId
        if (tid != 0) {
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

    private interface Kernel32Ext : com.sun.jna.Library {
        fun GetCurrentThreadId(): Int

        companion object {
            val INSTANCE: Kernel32Ext by lazy {
                com.sun.jna.Native.load("kernel32", Kernel32Ext::class.java) as Kernel32Ext
            }
        }
    }
}
