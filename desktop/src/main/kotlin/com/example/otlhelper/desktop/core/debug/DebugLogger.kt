package com.example.otlhelper.desktop.core.debug

import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

/**
 * §TZ-DESKTOP-0.10.18 — Debug logger для диагностики на машине пользователя.
 *
 * Пишет события в `Desktop\otl-debug.log` (на Win) или
 * `~/Desktop/otl-debug.log` (на Mac). Юзер открывает файл в Notepad
 * и присылает содержимое для анализа причин ошибок.
 *
 * **Что логируется:**
 *  - App start/stop/version
 *  - Login/logout/kick events
 *  - Session lifecycle (lock, extend, expire)
 *  - Network errors (401, 426, 5xx, timeouts)
 *  - Proxy auth attempts (Kerberos NTLM result)
 *  - Sheets webview load events (start, end, error)
 *  - Macro orchestrator: каждый шаг + exit code + stderr
 *
 * **Ротация:** файл >5MB → переименовывается в `otl-debug.log.1`
 * (старый .1 удаляется), создаётся новый. Хранится максимум 2 файла
 * (текущий + предыдущий).
 *
 * **Concurrency:** thread-safe через ReentrantLock. Один writer.
 */
object DebugLogger {

    private const val FILE_NAME = "otl-debug.log"
    private const val MAX_SIZE_BYTES = 5L * 1024 * 1024  // 5MB

    private val lock = ReentrantLock()
    private val timestampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    private val logFile: File by lazy {
        val home = System.getProperty("user.home") ?: "."
        // На Win стандартный Desktop = %USERPROFILE%\Desktop, на Mac
        // ~/Desktop. На обеих системах user.home возвращает корень
        // профиля, добавляем /Desktop.
        val desktop = File(home, "Desktop")
        if (!desktop.exists()) desktop.mkdirs()
        File(desktop, FILE_NAME)
    }

    /** Запись простого сообщения с тегом. */
    fun log(tag: String, message: String) {
        write("INFO ", tag, message)
    }

    /** Warn-уровень — не фатальные проблемы. */
    fun warn(tag: String, message: String) {
        write("WARN ", tag, message)
    }

    /** Error-уровень — фатальные ошибки + stacktrace если есть. */
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) {
            "$message\n  exception: ${throwable.javaClass.name}: ${throwable.message}\n" +
                throwable.stackTrace.take(8).joinToString("\n") { "    at $it" }
        } else {
            message
        }
        write("ERROR", tag, full)
    }

    private fun write(level: String, tag: String, message: String) {
        lock.lock()
        try {
            rotateIfNeeded()
            val ts = timestampFmt.format(Date())
            // Формат: 2026-05-09 18:20:15.123 INFO  [tag] message
            val line = "$ts $level [$tag] $message\n"
            logFile.appendText(line, Charsets.UTF_8)
        } catch (_: Throwable) {
            // Silent — debug logger не должен ломать приложение
        } finally {
            lock.unlock()
        }
    }

    private fun rotateIfNeeded() {
        if (!logFile.exists()) return
        if (logFile.length() < MAX_SIZE_BYTES) return
        runCatching {
            val backup = File(logFile.parentFile, "$FILE_NAME.1")
            if (backup.exists()) backup.delete()
            logFile.renameTo(backup)
        }
    }

    /** Удалить лог-файлы (на запрос юзера для приватности). */
    fun clear() {
        lock.lock()
        try {
            runCatching { logFile.delete() }
            runCatching { File(logFile.parentFile, "$FILE_NAME.1").delete() }
        } finally {
            lock.unlock()
        }
    }

    /** Путь к лог-файлу для UI (показать юзеру где найти). */
    fun logFilePath(): String = logFile.absolutePath
}
