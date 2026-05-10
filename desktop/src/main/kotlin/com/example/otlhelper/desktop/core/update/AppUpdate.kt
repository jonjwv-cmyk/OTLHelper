package com.example.otlhelper.desktop.core.update

import com.example.otlhelper.desktop.BuildInfo
import com.example.otlhelper.desktop.data.network.HttpClientFactory
import com.example.otlhelper.desktop.data.security.IntegrityCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

/**
 * §TZ-DESKTOP-DIST — desktop-аналог Android core/update/AppUpdate.kt.
 *
 * Обязанности:
 *  • Кэширует installer (DMG/EXE) в platform-specific каталог обновлений.
 *  • Стримит скачивание через VPS-override [HttpClientFactory.download]
 *    (тот же self-signed cert что REST/WS — провайдер видит только TLS
 *    до VPS, не до CF/R2).
 *  • Хранит ожидаемый SHA-256 и проверяет байты после загрузки.
 *  • Запускает installer и завершает приложение:
 *      - macOS: открывает DMG в Finder. Юзер сам перетаскивает .app в
 *        ~/Applications (или /Applications). Auto-replace не делаем — без
 *        admin-прав не получится в /Applications, а в ~/Applications не
 *        все туда смотрят.
 *      - Windows: запускает per-user EXE installer. Без admin (jpackage
 *        --win-per-user-install). Installer ставит в %LOCALAPPDATA%, при
 *        конце сам стартует обновлённое приложение.
 *
 * Версии: [BuildInfo.VERSION] — текущая, [storedVersion] — последний
 * успешно скачанный. Pref-file в `~/.otldhelper/update.properties`.
 */
object AppUpdate {

    private const val PREFS_NAME = "update.properties"
    private const val KEY_VERSION = "downloaded_version"
    private const val KEY_SHA256 = "expected_sha256"

    /** Базовая папка локального состояния (та же что использует KCEF). */
    private val baseDir: File by lazy {
        File(System.getProperty("user.home"), ".otldhelper").apply { mkdirs() }
    }

    private val updatesDir: File by lazy {
        File(baseDir, "updates").apply { mkdirs() }
    }

    private val prefsFile: File get() = File(baseDir, PREFS_NAME)

    private fun loadPrefs(): Properties = Properties().apply {
        if (prefsFile.exists()) prefsFile.inputStream().use { load(it) }
    }

    private fun savePrefs(p: Properties) {
        prefsFile.outputStream().use { p.store(it, "OTLD desktop update state") }
    }

    fun storedVersion(): String = loadPrefs().getProperty(KEY_VERSION, "")
    fun storedSha256(): String = loadPrefs().getProperty(KEY_SHA256, "")

    fun setExpectedSha256(sha256: String) {
        val p = loadPrefs()
        p.setProperty(KEY_SHA256, sha256.lowercase(Locale.US).trim())
        savePrefs(p)
    }

    private fun installerFile(version: String): File {
        val ext = when (BuildInfo.OS) {
            "mac" -> "dmg"
            "win" -> "exe"
            else -> "bin"
        }
        return File(updatesDir, "otl-helper-$version.$ext")
    }

    fun isReadyFor(version: String): Boolean {
        if (version.isBlank()) return false
        val f = installerFile(version)
        if (!f.exists() || f.length() <= 0L) return false
        return storedVersion() == version
    }

    fun markDownloaded(version: String, sha256: String = "") {
        val p = loadPrefs()
        p.setProperty(KEY_VERSION, version)
        if (sha256.isNotBlank()) p.setProperty(KEY_SHA256, sha256.lowercase(Locale.US).trim())
        savePrefs(p)
    }

    fun clearDownload() {
        runCatching { updatesDir.listFiles()?.forEach { it.delete() } }
        val p = loadPrefs()
        p.remove(KEY_VERSION)
        p.remove(KEY_SHA256)
        savePrefs(p)
    }

    /** Удалить старые installer-файлы после успешного апгрейда (BuildInfo.VERSION
     *  совпал с тем что мы качали в прошлый раз). */
    fun clearStaleAfterUpdate() {
        val stored = storedVersion()
        if (stored.isNotEmpty() && stored == BuildInfo.VERSION) {
            clearDownload()
        }
    }

    fun computeSha256(file: File): String {
        if (!file.exists() || file.length() <= 0L) return ""
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    fun verifySha256(file: File, expected: String): Boolean {
        val exp = expected.lowercase(Locale.US).trim()
        if (exp.isEmpty()) return true  // expected пуст → пропускаем (релиз без хеша)
        val actual = computeSha256(file)
        return actual.isNotEmpty() && actual.equals(exp, ignoreCase = true)
    }

    /**
     * Стримит installer по [url] в [installerFile]. Колбэки [onProgress]/
     * [onDone]/[onError] вызываются на Main (Swing EDT) ровно один раз.
     */
    fun downloadInstaller(
        url: String,
        version: String,
        scope: CoroutineScope,
        onProgress: (Float) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ): Job {
        val target = installerFile(version)
        runCatching { target.delete() }
        return scope.launch(Dispatchers.IO) {
            try {
                // §TZ-DESKTOP-0.10.0 — Win+corp-proxy → переписываем host на sslip.io
                // (LE cert → no Kaspersky interstitial → installer проходит через
                // browser-trusted chain на VPS-direct path с локально хранимым EXE).
                val resolvedUrl = com.example.otlhelper.desktop.core.network
                    .MediaUrlResolver.resolve(url)
                if (resolvedUrl != url) {
                    com.example.otlhelper.desktop.core.network.NetMetricsLogger.event(
                        "AppUpdate.downloadInstaller: rewrote $url → $resolvedUrl"
                    )
                }
                val request = Request.Builder().url(resolvedUrl).build()
                HttpClientFactory.download.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty body")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(target).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var downloaded = 0L
                            var lastReported = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                downloaded += n
                                if (total > 0) {
                                    val pct = ((downloaded * 100) / total).toInt()
                                    if (pct != lastReported) {
                                        lastReported = pct
                                        withContext(Dispatchers.Main) {
                                            onProgress((pct / 100f).coerceIn(0f, 1f))
                                        }
                                    }
                                }
                            }
                            out.flush()
                        }
                    }
                }
                markDownloaded(version)
                withContext(Dispatchers.Main) {
                    onProgress(1f)
                    onDone()
                }
            } catch (t: Throwable) {
                runCatching { target.delete() }
                withContext(Dispatchers.Main) { onError(t) }
            }
        }
    }

    /**
     * Запускает installer и завершает приложение **одной кнопкой**, без
     * Finder-drag и Next-Next-Finish.
     *
     * **macOS:** mount DMG → `ditto` копирует .app поверх установленного
     * (находим через [IntegrityCheck.selfPath] — куда юзер реально
     * установил, обычно `~/Applications` или `/Applications`) → unmount
     * → `open` обновлённого .app → `exitProcess`. Если target в
     * `/Applications` и не writable (нет admin) — fallback к `open DMG`,
     * юзер тащит сам (rare case — мы рекомендуем `~/Applications`).
     *
     * **Windows:** `cmd /c "installer /q & timeout 4 & start exe"` —
     * silent install через jpackage `/q`, потом запуск свежего EXE
     * из `%LOCALAPPDATA%\OTLD Helper\` (per-user install path). Без UAC.
     *
     * Сессия (`~/.otldhelper/session.properties`) переживает обновление
     * без вмешательства — install переписывает только app dir, home
     * directory не задевается. Новый процесс восстанавливает сессию
     * через `SessionStore.load()` в `LaunchedEffect(Unit)`.
     *
     * Возвращает true если update-командa успешно запущена. Текущий
     * процесс exit'ится через [scheduleExit] через 1.5-3 секунды (даём
     * cmd/installer'у зацепиться).
     */
    fun runInstaller(version: String): Boolean {
        val file = installerFile(version)
        if (!file.exists()) return false
        val expected = storedSha256()
        if (expected.isNotEmpty() && !verifySha256(file, expected)) {
            clearDownload()
            return false
        }
        return try {
            when (BuildInfo.OS) {
                "mac" -> runMacReplace(file)
                "win" -> runWindowsSilent(file)
                else -> {
                    if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
                    scheduleExit(delayMs = 1500)
                    true
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * macOS auto-replace pipeline.
     *
     * 1. Определить путь установленного .app по [IntegrityCheck.selfPath].
     *    `protectionDomain.codeSource` running JAR'а у jpackage-bundle
     *    выглядит так: `/Users/X/Applications/OTLD Helper.app/Contents/app/desktop-...jar`.
     *    Вырезаем до `.app`.
     * 2. Если не нашли (dev-режим / запуск из IDE) — fallback к
     *    `open <dmg>` (юзер тащит сам).
     * 3. Если нашли но не writable — fallback к `open <dmg>` плюс лог.
     * 4. Иначе: shell-script через `bash -c` делает hdiutil attach →
     *    ditto → hdiutil detach → open new app → exit.
     */
    private fun runMacReplace(dmg: File): Boolean {
        val installedApp = locateInstalledMacApp()
        if (installedApp == null) {
            // Не нашли установку — открываем DMG, юзер тащит вручную.
            ProcessBuilder("open", dmg.absolutePath).start()
            scheduleExit(delayMs = 1500)
            return true
        }
        if (!installedApp.canWrite() && !installedApp.parentFile.canWrite()) {
            // /Applications без admin — fallback к Finder-drag.
            ProcessBuilder("open", dmg.absolutePath).start()
            scheduleExit(delayMs = 1500)
            return true
        }

        // Уникальный mount-point чтобы не конфликтовать с уже смонтированным DMG.
        val mountPoint = "/tmp/otld-update-${System.currentTimeMillis()}"
        // Кавычим пробелы в путях (OTLD Helper.app содержит пробел).
        val script = buildString {
            append("set -e\n")
            append("MNT=\"$mountPoint\"\n")
            append("hdiutil attach -nobrowse -quiet -mountpoint \"\$MNT\" \"${dmg.absolutePath}\"\n")
            append("SRC=\$(ls -1d \"\$MNT\"/*.app 2>/dev/null | head -1)\n")
            append("if [ -z \"\$SRC\" ]; then hdiutil detach \"\$MNT\" -quiet >/dev/null 2>&1 || true; exit 1; fi\n")
            // ditto сохраняет xattrs (extended attributes) и code-signing если есть.
            append("ditto \"\$SRC\" \"${installedApp.absolutePath}\"\n")
            append("hdiutil detach \"\$MNT\" -quiet >/dev/null 2>&1 || true\n")
            // Дать macOS clear quarantine от загруженного через нас DMG'а.
            append("xattr -dr com.apple.quarantine \"${installedApp.absolutePath}\" 2>/dev/null || true\n")
            // Запустить обновлённое приложение в новом процессе.
            append("open \"${installedApp.absolutePath}\"\n")
        }
        ProcessBuilder("bash", "-c", script)
            .redirectErrorStream(true)
            .start()
        // Чуть подольше — ditto на 200MB-bundle может занять 2-5 секунд.
        scheduleExit(delayMs = 3000)
        return true
    }

    private fun locateInstalledMacApp(): File? {
        val self = IntegrityCheck.selfPath
        if (self.isBlank()) return null
        // jpackage bundle: .../OTLD Helper.app/Contents/app/desktop-X.Y.Z.jar
        val marker = ".app/"
        val idx = self.indexOf(marker)
        if (idx < 0) return null
        val appPath = self.substring(0, idx + marker.length - 1)
        val app = File(appPath)
        return if (app.isDirectory) app else null
    }

    /**
     * Windows silent install via cmd.exe chain:
     *   1. `installer.exe /q` — quiet install (jpackage exe-installer
     *      поддерживает /quiet и /q). Per-user install (мы установили
     *      `windows.perUserInstall = true`) не требует UAC.
     *   2. `timeout 4` — даём installer'у завершить запись файлов в
     *      `%LOCALAPPDATA%\OTLD Helper\`.
     *   3. `start "" "%LOCALAPPDATA%\OTLD Helper\OTLD Helper.exe"` —
     *      запускаем обновлённое приложение.
     *
     * Текущий процесс выйдет через 1.5s, installer работает в фоне.
     */
    private fun runWindowsSilent(installer: File): Boolean {
        val localAppData = System.getenv("LOCALAPPDATA")
            ?: File(System.getProperty("user.home"), "AppData\\Local").absolutePath
        val installedExe = "$localAppData\\OTLD Helper\\OTLD Helper.exe"
        val updateLog = File(localAppData, ".otldhelper\\update.log").absolutePath

        // §TZ-DESKTOP-NATIVE-2026-05 0.8.16 — destroy WebView2 controllers +
        // Edge subprocesses ДО installer. Без этого Edge держит lock на app
        // files, installer не может перезаписать → silent fail. После
        // shutdown ждём 2с чтобы Edge subprocesses успели exit.
        runCatching {
            com.example.otlhelper.desktop.sheets.nativeweb.shutdownWebView2()
        }
        runCatching { Thread.sleep(2000) }

        // §0.11.1 — explicit release SingleInstanceLock ДО запуска cmd chain.
        // Иначе race: старый JVM exit'ится через scheduleExit(1500), но lock
        // file release через shutdownHook может опоздать. Cmd chain через 10s
        // запускает new EXE → SingleInstanceLock.acquireOrSignal видит занятый
        // lock → шлёт focus signal → exit'ится. Старый EXE продолжает работать
        // в tray со старой версией, юзер думает «обновление не сработало».
        runCatching {
            com.example.otlhelper.desktop.core.SingleInstanceLock.releaseForUpdate()
        }

        // §TZ-DESKTOP-NATIVE-2026-05 0.8.16 — логируем installer output в
        // %LOCALAPPDATA%\.otldhelper\update.log. Без этого "тихий fail"
        // (юзер видит "ничего не происходит" после Обновить).
        // /q — silent install (no UI). taskkill на всякий случай прибьёт
        // зависший OTLD Helper если scheduleExit не успел.
        // §TZ-DESKTOP-NATIVE-2026-05 0.8.19 — taskkill /T flag убивал cmd.exe
        // потому что cmd.exe descendant of "OTLD Helper.exe" (через JVM).
        // Без cmd.exe весь chain прерывался → installer не запускался.
        // Решение: kill by exe name только specific processes, не tree.
        // msedgewebview2.exe — Edge subprocesses, должны быть убиты до
        // installer чтобы освободить file locks.
        val cmd = buildString {
            append("(")
            append("taskkill /F /IM msedgewebview2.exe > \"$updateLog\" 2>&1 & ")
            append("taskkill /F /IM \"OTLD Helper.exe\" >> \"$updateLog\" 2>&1 & ")
            append("timeout /t 2 /nobreak > nul & ")
            append("\"${installer.absolutePath}\" /q >> \"$updateLog\" 2>&1 & ")
            append("timeout /t 6 /nobreak > nul & ")
            append("start \"\" \"$installedExe\"")
            append(")")
        }
        ProcessBuilder("cmd.exe", "/c", cmd)
            .redirectErrorStream(true)
            .start()
        scheduleExit(delayMs = 1500)
        return true
    }

    private fun scheduleExit(delayMs: Long) {
        Thread {
            runCatching { Thread.sleep(delayMs) }
            kotlin.system.exitProcess(0)
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * §TZ-DESKTOP-DIST 0.5.1 — программный restart для случая когда KCEF
     * запросил `onRestartRequired` (после первой настройки Chromium native
     * libs). Юзер жмёт кнопку «Перезапустить» → запускаем новый процесс
     * того же EXE/Mac.app → текущий exit'ится через 1.5s.
     *
     * Возвращает true если команда запуска отработала. На fail возвращаем
     * false — caller должен показать «закройте и откройте вручную».
     */
    fun restartApp(): Boolean = try {
        when (BuildInfo.OS) {
            "mac" -> {
                // §TZ-DESKTOP-DIST 0.5.1 — open -n запускает новый instance .app.
                // Если юзер установил в ~/Applications/, fallback на bundle id.
                val candidates = listOf(
                    "/Applications/OTLD Helper.app",
                    "${System.getProperty("user.home")}/Applications/OTLD Helper.app",
                )
                val appPath = candidates.firstOrNull { File(it).exists() }
                if (appPath != null) {
                    ProcessBuilder("open", "-n", appPath)
                        .redirectErrorStream(true)
                        .start()
                } else {
                    // Fallback по bundle id
                    ProcessBuilder("open", "-n", "-b", "com.otl.otldhelper")
                        .redirectErrorStream(true)
                        .start()
                }
                scheduleExit(delayMs = 1500)
                true
            }
            "win" -> {
                val localAppData = System.getenv("LOCALAPPDATA")
                    ?: File(System.getProperty("user.home"), "AppData\\Local").absolutePath
                val installedExe = "$localAppData\\OTLD Helper\\OTLD Helper.exe"
                ProcessBuilder("cmd.exe", "/c", "start", "\"\"", "\"$installedExe\"")
                    .redirectErrorStream(true)
                    .start()
                scheduleExit(delayMs = 1500)
                true
            }
            else -> false
        }
    } catch (_: Throwable) {
        false
    }
}
