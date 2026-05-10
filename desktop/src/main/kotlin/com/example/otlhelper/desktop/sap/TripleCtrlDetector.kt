package com.example.otlhelper.desktop.sap

import com.example.otlhelper.desktop.core.debug.DebugLogger
import com.sun.jna.platform.win32.User32

/**
 * §0.11.12 — Triple Ctrl detector через **polling** `GetAsyncKeyState`.
 *
 * Раньше пробовали:
 *  • Ctrl+Q (RegisterHotKey) — Касперский OK
 *  • Ctrl+Space (RegisterHotKey) — Касперский HIPS Self-Defense **paniked**,
 *    перевёл наш процесс в защищённый режим, UI не открывается, kill требует
 *    admin прав. Юзер потерял возможность работать.
 *
 * Поэтому ОТКАЗЫВАЕМСЯ от RegisterHotKey/keyboard hook вообще. Используем
 * `GetAsyncKeyState(VK_CONTROL)` polling каждые 30ms — это **не hook**,
 * это просто чтение текущего состояния клавиатуры. Так работают игры,
 * Caps Lock indicators, accessibility tools — Касперский такие тулзы не
 * считает подозрительными.
 *
 * Триггер: 3 rising edges (no→pressed) клавиши Ctrl за окно 1 секунды.
 */
object TripleCtrlDetector {

    private const val TAG = "TRIPLE_CTRL"
    private const val VK_CONTROL = 0x11
    private const val POLL_MS = 30L
    private const val WINDOW_MS = 1000L
    private const val MIN_PRESSES = 3

    @Volatile private var pollThread: Thread? = null
    @Volatile private var listener: (() -> Unit)? = null

    fun start(onTriple: () -> Unit) {
        if (pollThread?.isAlive == true) return
        listener = onTriple

        val thread = Thread({
            DebugLogger.log(TAG, "polling started (no hook, no RegisterHotKey)")
            var prevPressed = false
            val pressTimes = ArrayDeque<Long>()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(POLL_MS)
                    val state = User32.INSTANCE.GetAsyncKeyState(VK_CONTROL).toInt()
                    // High bit (0x8000) = key currently pressed
                    val pressed = (state and 0x8000) != 0
                    if (pressed && !prevPressed) {
                        // Rising edge — Ctrl just got pressed
                        val now = System.currentTimeMillis()
                        // Drop outdated presses
                        while (pressTimes.isNotEmpty() && (now - pressTimes.first()) > WINDOW_MS) {
                            pressTimes.removeFirst()
                        }
                        pressTimes.addLast(now)
                        if (pressTimes.size >= MIN_PRESSES) {
                            pressTimes.clear()
                            DebugLogger.log(TAG, "triple Ctrl detected → trigger")
                            val cb = listener
                            if (cb != null) {
                                Thread(cb, "OTLD-TripleCtrl-Trigger").apply {
                                    isDaemon = true
                                    start()
                                }
                            }
                        }
                    }
                    prevPressed = pressed
                }
            } catch (_: InterruptedException) {
                // Graceful exit
            } catch (e: Throwable) {
                DebugLogger.error(TAG, "polling crashed", e)
            }
            DebugLogger.log(TAG, "polling stopped")
        }, "OTLD-TripleCtrl-Poll")
        thread.isDaemon = true
        thread.start()
        pollThread = thread
    }

    fun stop() {
        pollThread?.interrupt()
        pollThread = null
        listener = null
    }
}
