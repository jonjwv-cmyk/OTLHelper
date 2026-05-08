package com.example.otlhelper.desktop.sheets

import java.io.File
import java.util.Properties

/**
 * §TZ-DESKTOP-UX-2026-04 — persistent preference про режим Google Sheets зоны.
 *
 * Запоминает явный выбор юзера чтобы при следующем запуске не показывать
 * gate повторно:
 *
 *   • [Auto] (default) — детектим автоматически 3 секунды: если в
 *     Chromium-кэше валидный Google cookie → SignedIn без gate, иначе →
 *     Anonymous (просмотр без login).
 *   • [Anonymous] — юзер явно нажал «Выход из Google». При cold-start
 *     пропускаем detection, сразу Anonymous mode (read-only, в TabStrip
 *     кнопка «Войти в Google» чтобы передумать).
 *
 * Файл: `~/.otldhelper/sheets_login.properties`. Lazy IO, держим snapshot
 * в памяти.
 */
internal object SheetsLoginPref {

    enum class Mode { Auto, Anonymous }

    private const val KEY_MODE = "google_login_mode"
    private const val FILE_NAME = "sheets_login.properties"

    private val baseDir: File by lazy {
        File(System.getProperty("user.home"), ".otldhelper").apply { mkdirs() }
    }
    private val prefsFile: File get() = File(baseDir, FILE_NAME)

    @Volatile
    private var cached: Mode? = null

    fun load(): Mode {
        cached?.let { return it }
        val mode = try {
            val p = Properties()
            if (prefsFile.exists()) {
                prefsFile.inputStream().use { p.load(it) }
            }
            when (p.getProperty(KEY_MODE, "auto").lowercase()) {
                "anonymous" -> Mode.Anonymous
                else -> Mode.Auto
            }
        } catch (_: Throwable) { Mode.Auto }
        cached = mode
        return mode
    }

    fun save(mode: Mode) {
        cached = mode
        runCatching {
            val p = Properties()
            if (prefsFile.exists()) {
                prefsFile.inputStream().use { p.load(it) }
            }
            p.setProperty(KEY_MODE, mode.name.lowercase())
            prefsFile.outputStream().use { p.store(it, "OTLD desktop sheets login pref") }
        }
    }
}
