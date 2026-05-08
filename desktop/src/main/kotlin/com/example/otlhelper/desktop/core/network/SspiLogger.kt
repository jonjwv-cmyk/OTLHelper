package com.example.otlhelper.desktop.core.network

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * §TZ-DESKTOP-0.10.4 — file output ОТКЛЮЧЕН.
 *
 * До 0.10.4 писали в `~/Desktop/OTL-debug.log`. Юзер: «убираем все debug
 * файлы, остаются только версии в настройках». Сохраняем функцию [log] и
 * [logPath] для compile compatibility (вызывается из existing code), но
 * она теперь только в console (System.out).
 *
 * Если нужен debug в production — включить через `-Dotl.debug=true` JVM
 * flag (создаст файл). По дефолту off.
 *
 * В 0.10.5 переносим diagnostic UI в Android (только для developer/admin).
 */
object SspiLogger {

    private val df = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Включён ли debug-файл. По дефолту false. Активируется через
     * `-Dotl.debug=true` JVM-аргумент (для специально подготовленных билдов).
     */
    private val fileLoggingEnabled: Boolean by lazy {
        System.getProperty("otl.debug", "false").equals("true", ignoreCase = true)
    }

    private val logFile: File? by lazy {
        if (!fileLoggingEnabled) return@lazy null
        runCatching {
            val home = System.getProperty("user.home") ?: return@runCatching null
            val candidates = listOf(
                File(home, "Desktop"),
                File(home, "Рабочий стол"),
                File(home),
            )
            val dir = candidates.firstOrNull { it.exists() && it.isDirectory } ?: File(home)
            File(dir, "OTL-debug.log").apply {
                runCatching {
                    writeText(
                        "[OTL debug log started ${Date()}] " +
                            "OS=${System.getProperty("os.name")} " +
                            "JVM=${System.getProperty("java.version")} " +
                            "user.home=$home\n",
                    )
                }
            }
        }.getOrNull()
    }

    fun log(line: String) {
        val ts = df.format(Date())
        val full = "[$ts] $line\n"
        // Console (если запущено из IDE / cmd)
        runCatching { System.out.print(full) }
        // File — только если -Dotl.debug=true
        if (fileLoggingEnabled) runCatching { logFile?.appendText(full) }
    }

    /** Путь к лог-файлу (если включён). */
    fun logPath(): String = logFile?.absolutePath ?: "(disabled)"
}
