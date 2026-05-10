package com.example.otlhelper.desktop.core.debug

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

/**
 * Единая точка логирования. Один файл `~/Desktop/otl-debug.log` (Mac) /
 * `Desktop\otl-debug.log` (Win). Юзер шлёт файл — мы анализируем.
 *
 * Формат строки:
 *   `2026-05-10 18:20:15.123 INFO  [TAG] message kv=foo kv2=bar`
 *
 * Уровни: INFO / WARN / ERROR. Отдельный helper [event] для KV-формата.
 *
 * Тэги (договорились в 0.11.13):
 *   BOOT          — старт приложения, версия, окружение
 *   STATE         — переходы AppState (LOGIN/MAIN/RESTORING)
 *   WV-MOUNT      — webview create/attach/destroy
 *   WV-NAV        — Navigate(url), redirects
 *   WV-LOAD       — start/finish/error load events
 *   WV-MASK       — CSS injection, body visibility transitions
 *   WV-REVEAL     — reveal pipeline: each polling tick state
 *   WV-FRAME      — geometry updates (bounds change)
 *   WV-JS         — сообщения от JS через bridge (window.__otldLog)
 *   GOOGLE-AUTH   — login state machine (Detecting/Authenticating/SignedIn)
 *   NET           — HTTP request lifecycle
 *   API           — ApiClient action latency / errors
 *   MACRO         — MacroOrchestrator steps + exit codes
 *   SAP           — SAP detect/launch/inspector
 *   AV            — KasperskyMonitor process/dll scans
 *   TRIPLE-CTRL   — Ctrl polling rising edges
 *   CLIPBOARD     — clipboard sequence triple-copy
 *   PROXY         — corporate proxy / SSPI auth
 *
 * Concurrency: ReentrantLock — один writer. Append-only.
 *
 * Rotation: при превышении 8MB переименовывается в `.log.1` (старый .1
 * удаляется). Хранится максимум 2 файла.
 */
object DebugLogger {

    private const val FILE_NAME = "otl-debug.log"
    private const val MAX_SIZE_BYTES = 8L * 1024 * 1024  // 8MB

    private val lock = ReentrantLock()
    private val timestampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    private val logFile: File by lazy {
        val home = System.getProperty("user.home") ?: "."
        val desktop = File(home, "Desktop")
        if (!desktop.exists()) desktop.mkdirs()
        File(desktop, FILE_NAME)
    }

    /** INFO — обычное событие. */
    fun log(tag: String, message: String) {
        write("INFO ", tag, message)
    }

    /** WARN — нефатальная аномалия. */
    fun warn(tag: String, message: String) {
        write("WARN ", tag, message)
    }

    /** ERROR — фатальная ошибка + опциональный stacktrace. */
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) {
            "$message\n  exception: ${throwable.javaClass.name}: ${throwable.message}\n" +
                throwable.stackTrace.take(12).joinToString("\n") { "    at $it" }
        } else {
            message
        }
        write("ERROR", tag, full)
    }

    /**
     * Структурированное событие с key=value полями.
     * Удобно для машинного парсинга и grep:
     *   `event("WV-REVEAL", "tick" to 12, "isLoading" to false, "url" to "docs.google.com/...")`
     * → `... INFO  [WV-REVEAL] event=tick tick=12 isLoading=false url=docs.google.com/...`
     */
    fun event(tag: String, vararg fields: Pair<String, Any?>) {
        if (fields.isEmpty()) {
            write("INFO ", tag, "event=anonymous")
            return
        }
        val first = fields[0]
        val eventName = (first.second ?: "").toString()
        val rest = fields.drop(1)
        val sb = StringBuilder("event=").append(eventName)
        for ((k, v) in rest) {
            sb.append(' ').append(k).append('=')
            appendValue(sb, v)
        }
        write("INFO ", tag, sb.toString())
    }

    /**
     * Замер длительности блока. Пишет один лог-line с total ms.
     *
     *   val result = DebugLogger.track("API", "action" to "me") { ApiClient.me() }
     *   → ... INFO  [API] event=track action=me ms=234 ok=true
     */
    inline fun <T> track(
        tag: String,
        vararg fields: Pair<String, Any?>,
        block: () -> T,
    ): T {
        val t0 = System.currentTimeMillis()
        var ok = true
        try {
            return block()
        } catch (t: Throwable) {
            ok = false
            throw t
        } finally {
            val dt = System.currentTimeMillis() - t0
            event(tag, *fields, "ms" to dt, "ok" to ok)
        }
    }

    /**
     * Banner на старте — сводная информация о среде.
     * Пишется в начало нового лог-сегмента после ротации.
     */
    fun banner(version: String, appScope: String) {
        val os = System.getProperty("os.name", "?")
        val osVer = System.getProperty("os.version", "?")
        val osArch = System.getProperty("os.arch", "?")
        val javaVer = System.getProperty("java.version", "?")
        val javaVendor = System.getProperty("java.vendor", "?")
        val userHome = System.getProperty("user.home", "?")
        val userName = System.getProperty("user.name", "?")
        val locale = Locale.getDefault().toString()
        val timezone = java.util.TimeZone.getDefault().id
        val pid = ProcessHandle.current().pid()
        val maxMem = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        val cores = Runtime.getRuntime().availableProcessors()

        write("INFO ", "BOOT", "============ OTL Helper ============")
        write("INFO ", "BOOT", "version=$version scope=$appScope pid=$pid")
        write("INFO ", "BOOT", "os=$os $osVer arch=$osArch")
        write("INFO ", "BOOT", "java=$javaVer vendor=\"$javaVendor\"")
        write("INFO ", "BOOT", "user=$userName home=$userHome")
        write("INFO ", "BOOT", "locale=$locale tz=$timezone")
        write("INFO ", "BOOT", "memory_max=${maxMem}MB cores=$cores")
        write("INFO ", "BOOT", "log_file=${logFile.absolutePath}")
        write("INFO ", "BOOT", "====================================")
    }

    private fun write(level: String, tag: String, message: String) {
        lock.lock()
        try {
            rotateIfNeeded()
            val ts = timestampFmt.format(Date())
            val line = "$ts $level [$tag] $message\n"
            logFile.appendText(line, Charsets.UTF_8)
        } catch (_: Throwable) {
            // Silent — logger не должен ронять приложение
        } finally {
            lock.unlock()
        }
    }

    private fun appendValue(sb: StringBuilder, v: Any?) {
        if (v == null) {
            sb.append("null")
            return
        }
        val s = v.toString()
        // Если содержит пробелы/кавычки — оборачиваем в кавычки
        val needsQuote = s.any { it == ' ' || it == '\t' || it == '"' || it == '=' }
        if (needsQuote) {
            sb.append('"')
            for (ch in s) when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
            sb.append('"')
        } else {
            sb.append(s)
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
