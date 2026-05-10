package com.example.otlhelper.desktop.sap

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * §0.11.9 — Детектор «3 копирования в буфер за 1.5 секунды» (Win-only).
 *
 * Подход: следим за `GetClipboardSequenceNumber` (Win32 API) — это счётчик
 * который Windows инкрементит при каждом изменении clipboard. Когда юзер
 * жмёт Ctrl+C три раза подряд (даже на одном и том же тексте) — счётчик
 * вырастает на 3 за короткое время. Это **независимо от раскладки**:
 * русская С и английская C — обе triggers Ctrl+C → clipboard инкремент.
 *
 * Преимущества vs низкоуровневый keyboard hook:
 *  • Не нужны admin permissions
 *  • Не блокирует другие global hotkeys
 *  • Нет конфликта с keyboard shortcuts приложений
 *  • Минимум CPU (poll 100ms, no hooks)
 *
 * Polling thread daemon — завершается с приложением.
 */
object ClipboardTripleCopyDetector {

    private const val POLL_MS = 100L
    private const val WINDOW_MS = 1500L
    private const val MIN_COPIES = 3

    @Volatile private var pollThread: Thread? = null
    @Volatile private var listener: (() -> Unit)? = null

    fun start(onTripleCopy: () -> Unit) {
        if (pollThread?.isAlive == true) return
        listener = onTripleCopy

        val thread = Thread({
            val u32 = User32ClipboardSeq.INSTANCE
            var lastSeq = u32.GetClipboardSequenceNumber()
            val copies = ArrayDeque<Long>()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(POLL_MS)
                    val seq = u32.GetClipboardSequenceNumber()
                    if (seq != lastSeq) {
                        lastSeq = seq
                        val now = System.currentTimeMillis()
                        // Drop outdated copies
                        while (copies.isNotEmpty() && (now - copies.first()) > WINDOW_MS) {
                            copies.removeFirst()
                        }
                        copies.addLast(now)
                        if (copies.size >= MIN_COPIES) {
                            copies.clear()
                            // Fire callback in separate thread to не блочить poll loop
                            val cb = listener
                            if (cb != null) {
                                Thread(cb, "OTLD-SAP-Trigger").apply {
                                    isDaemon = true
                                    start()
                                }
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // graceful exit
            } catch (_: Throwable) {
                // any other error → silent exit (детектор просто перестаёт работать)
            }
        }, "OTLD-ClipboardPoll")
        thread.isDaemon = true
        thread.start()
        pollThread = thread
    }

    fun stop() {
        pollThread?.interrupt()
        pollThread = null
        listener = null
    }

    /**
     * Минимальный JNA interface к user32!GetClipboardSequenceNumber.
     * Стандартный `com.sun.jna.platform.win32.User32` НЕ имеет этого метода
     * (он редко используется), поэтому отдельный interface.
     */
    private interface User32ClipboardSeq : Library {
        fun GetClipboardSequenceNumber(): Int

        companion object {
            val INSTANCE: User32ClipboardSeq by lazy {
                Native.load("user32", User32ClipboardSeq::class.java) as User32ClipboardSeq
            }
        }
    }
}
