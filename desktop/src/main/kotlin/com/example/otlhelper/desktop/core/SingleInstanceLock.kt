package com.example.otlhelper.desktop.core

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * §TZ-DESKTOP-0.10.4 — Single instance enforcement.
 *
 * Юзер: «приложение наше может быть запущено несколько раз а так не должно
 * быть. если запущено то всё уже».
 *
 * Стратегия (двухуровневая, для надёжности):
 *  1. **File lock** — `~/.otldhelper/.app.lock` через FileChannel.tryLock().
 *     Lock автоматически отпускается при завершении JVM (даже при kill -9
 *     OS освободит дескриптор). Кросс-платформенно.
 *  2. **TCP probe port** — слушаем `127.0.0.1:18373` чтобы новый instance мог
 *     отправить «focus me» сигнал старому. Старое окно поднимается в фокус,
 *     новый instance корректно exit'ится.
 *
 * Использование в Main.kt:
 * ```
 * fun main() {
 *     if (!SingleInstanceLock.acquireOrSignal()) {
 *         println("[OTL] Already running, sent focus signal — exit")
 *         kotlin.system.exitProcess(0)
 *     }
 *     // ... rest of main
 * }
 * ```
 */
object SingleInstanceLock {

    private const val PROBE_PORT = 18373
    private const val LOCK_DIR_NAME = ".otldhelper"
    private const val LOCK_FILE_NAME = ".app.lock"

    @Volatile
    private var fileLock: FileLock? = null

    @Volatile
    private var lockChannel: FileChannel? = null

    @Volatile
    private var probeServer: ServerSocket? = null

    /**
     * Callback вызываемый когда new instance попытался запуститься.
     * Поднимает window в фокус.
     */
    @Volatile
    var onFocusRequested: () -> Unit = {}

    /**
     * Acquire lock на запуск. Если уже занят — пытается дозвониться до
     * существующего instance и попросить его поднять window в фокус.
     *
     * Возвращает:
     *  - true — lock acquired, наш instance первый, продолжаем launch
     *  - false — instance уже запущен, focus signal отправлен, нужно exit
     */
    fun acquireOrSignal(): Boolean {
        // 1. Try file lock
        val lockFile = lockFile()
        return runCatching {
            val raf = RandomAccessFile(lockFile, "rw")
            val ch = raf.channel
            val lock = ch.tryLock()
            if (lock != null) {
                fileLock = lock
                lockChannel = ch
                // Запускаем TCP probe listener для будущих instances
                startProbeListener()
                // Регистрируем shutdown hook чтобы lock освободился при close
                Runtime.getRuntime().addShutdownHook(Thread {
                    runCatching { fileLock?.release() }
                    runCatching { lockChannel?.close() }
                    runCatching { probeServer?.close() }
                    runCatching { lockFile.delete() }
                })
                println("[OTL] SingleInstanceLock: acquired (${lockFile.absolutePath})")
                true
            } else {
                // Lock уже занят — попробуем сигнализировать существующему
                runCatching { ch.close() }
                signalExistingInstance()
                false
            }
        }.getOrElse {
            // Любая ошибка — пропускаем lock check, разрешаем запуск
            // (лучше чем заблокировать запуск из-за file system error)
            println("[OTL] SingleInstanceLock: error during acquire, allowing start: $it")
            true
        }
    }

    /**
     * §0.11.1 — explicit release ДО запуска installer chain. Иначе race:
     * 1. Старый JVM exitProcess(0) → shutdownHook releases lock через X ms
     * 2. Cmd chain start "" "$installedExe" → new EXE стартует
     * 3. Если lock release не успел — new EXE acquireOrSignal() видит lock,
     *    шлёт focus signal, exits. Юзер думает "обновление не сработало"
     *    (фоновый старый EXE продолжает в tray со старой версией).
     *
     * Вызывается из AppUpdate.runWindowsSilent() ПЕРЕД scheduleExit.
     */
    fun releaseForUpdate() {
        runCatching { fileLock?.release() }
        runCatching { lockChannel?.close() }
        runCatching { probeServer?.close() }
        runCatching { lockFile().delete() }
        fileLock = null
        lockChannel = null
        probeServer = null
        println("[OTL] SingleInstanceLock: explicitly released for update")
    }

    private fun lockFile(): File {
        val home = System.getProperty("user.home")
            ?: System.getenv("USERPROFILE")
            ?: "."
        val dir = File(home, LOCK_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, LOCK_FILE_NAME)
    }

    private fun startProbeListener() {
        try {
            probeServer = ServerSocket(PROBE_PORT, 1, InetAddress.getByName("127.0.0.1"))
            Thread {
                val server = probeServer ?: return@Thread
                while (!server.isClosed) {
                    runCatching {
                        val client = server.accept()
                        // Любой connect = "focus me" signal
                        runCatching { client.close() }
                        runCatching { onFocusRequested() }
                    }.onFailure {
                        // Server closed or accept failed — выходим из loop
                        if (server.isClosed) return@Thread
                    }
                }
            }.apply {
                isDaemon = true
                name = "OTL-SingleInstance-Probe"
                start()
            }
        } catch (t: Throwable) {
            // Если порт занят (предыдущий crash оставил orphan socket?) — лог,
            // но не блокируем запуск. Lock держится, focus сигнал просто
            // не сработает.
            println("[OTL] SingleInstanceLock: probe listener failed: $t")
        }
    }

    private fun signalExistingInstance() {
        runCatching {
            Socket(InetAddress.getByName("127.0.0.1"), PROBE_PORT).use { sock ->
                sock.soTimeout = 1500
                // Просто открыли connection — этого достаточно. Старый instance
                // accept'ит, видит connect, вызывает onFocusRequested.
                println("[OTL] SingleInstanceLock: signaled existing instance")
            }
        }.onFailure {
            // Probe failed — старый instance не отвечает. Возможно завис.
            // Тогда юзер увидит что наш new instance просто молча выйдет.
            // Альтернатива: показать диалог «App уже запущен, проверьте трей».
            println("[OTL] SingleInstanceLock: cannot reach existing instance: $it")
        }
    }
}
