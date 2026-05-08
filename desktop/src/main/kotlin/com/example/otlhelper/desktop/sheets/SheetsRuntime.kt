package com.example.otlhelper.desktop.sheets

import com.example.otlhelper.desktop.BuildInfo
import com.example.otlhelper.desktop.data.network.HttpClientFactory
import dev.datlag.kcef.KCEF
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File

/**
 * §TZ-DESKTOP 0.4.0 + §TZ-DESKTOP-DIST 0.5.2 — глобальная обёртка над KCEF
 * (Chromium для Sheets-зоны).
 *
 * # Lifecycle
 *
 *  1. На старте Sheets-зоны вызываем [start] — асинхронно скачивается Chromium
 *     bundle (~150 MB) с `api.otlhelper.com/kcef-bundle/<archive>` (через VPS-
 *     прокси → R2). Прогресс в [state]. Bundle лежит в
 *     `~/.otldhelper/kcef-bundle/`.
 *  2. После [State.Ready] можно через `KCEF.newClientBlocking()` создавать
 *     `KCEFBrowser`'ы.
 *  3. На logout приложения [dispose] закрывает Chromium процессы.
 *
 * # Engine policy
 *
 * §TZ-DESKTOP-DIST 0.5.2 — единый KCEF на Mac+Win. Раньше Mac работал через
 * NATIVE (JavaFX/WKWebView), но это требовало двух codebase'ов для CSS-масок,
 * paste-bridges и тюнинга layout. Унификация:
 *  - один движок → одинаковое поведение Sheets (Google tested reference)
 *  - одна CSS-маска → правки сразу везде
 *  - DevTools available на всех платформах
 *
 * Trade-off: Mac юзеры качают bundle ~150 MB при первом запуске.
 *
 * # Bundle distribution
 *
 * `api.otlhelper.com/kcef-bundle/jcef-<os>-<arch>.tar.gz` отдаётся через CF
 * Worker → R2. Запросы идут через VPS proxy (как и весь app traffic), поэтому
 * Defender/DPI на стороне юзеров не блокирует. Раньше KCEF library качала
 * напрямую с GitHub releases — у части юзеров блокировалось.
 *
 * Cookies / Google login state живут в `cachePath` под Chromium profile.
 */
object SheetsRuntime {
    enum class Engine {
        NATIVE,
        KCEF,
    }

    /**
     * §TZ-DESKTOP-DIST 0.5.2 — единый KCEF на Mac+Win. Bundle качается с
     * api.otlhelper.com/kcef-bundle/ → VPS proxy → R2.
     *
     * Mac на NATIVE engine был временно (до апгрейда KCEF library до
     * 2025.03.23 и фикса prebuild gradle task с isIgnoreExitValue).
     * Теперь оба bundle на R2 — Mac тоже использует Chromium для
     * однотипности codebase'а Sheets-зоны.
     *
     * Env var OTLD_SHEETS_ENGINE=native — ручной override для dev-отладки
     * (откатывает Mac на JavaFX/WKWebView без bundle download).
     */
    val engine: Engine by lazy {
        // §TZ-DESKTOP-NATIVE-2026-05 — миграция на нативные движки ОС:
        //   • Mac → WKWebView (libNativeUtils.dylib + JNA) — Apple обновляет
        //   • Win → WebView2/Edge (NativeUtils.dll + JNA) — Microsoft обновляет
        // KCEF (`dev.datlag:kcef:2025.03.23`) — заброшен с апр.2025, крашит на
        // macOS 26 (pointer auth) и Win 11 24H2 (libcef.dll assertion). Native
        // движки бесплатны, обновляются ОС, нет 150MB Chromium bundle.
        //
        // Override через env var OTLD_SHEETS_ENGINE=kcef для force-fallback
        // (Mac ≤15 если что-то сломается в WKWebView, либо для отладки).
        val envOverride = System.getenv("OTLD_SHEETS_ENGINE")?.trim()?.lowercase()
        if (envOverride in setOf("native", "wkwebview", "webview", "webview2")) return@lazy Engine.NATIVE
        if (envOverride in setOf("kcef", "chromium", "force_kcef")) return@lazy Engine.KCEF

        val isMac = BuildInfo.IS_MAC
        val osName = System.getProperty("os.name", "").lowercase()
        val isWin = osName.contains("win")
        val osMajor = System.getProperty("os.version", "")
            .substringBefore('.')
            .toIntOrNull() ?: 0

        // Win — всегда WebView2 (Edge). KCEF fallback не оставляем т.к. он
        // надёжно крашит на Win 11 24H2 (`EXCEPTION_BREAKPOINT 0x80000003`).
        // Если WebView2 Runtime отсутствует — WinSheetsWebView показывает
        // placeholder с инструкцией `winget install Microsoft.EdgeWebView2`.
        if (isWin) return@lazy Engine.NATIVE

        // Mac 26+ Tahoe — KCEF crash на arm64e (pointer auth failure). NATIVE
        // через WKWebView. Mac ≤15 пока остаётся на KCEF (legacy fallback,
        // libNativeUtils.dylib paste-bridge для cells ещё не доделан).
        if (isMac && osMajor >= 26) return@lazy Engine.NATIVE

        Engine.KCEF
    }

    sealed interface State {
        data object Idle : State
        data class Locating(val message: String) : State
        data class Downloading(val percent: Int) : State
        data class Extracting(val message: String) : State
        data class Initializing(val message: String) : State
        data object Ready : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val installDir: File by lazy {
        val home = System.getProperty("user.home") ?: "."
        File(home, ".otldhelper/kcef-bundle").also { it.mkdirs() }
    }

    @Volatile
    private var started: Boolean = false

    /** Идемпотентно. Повторный вызов после Ready — no-op. */
    fun start() {
        if (started) return
        started = true
        if (engine == Engine.NATIVE) {
            _state.value = State.Ready
            return
        }
        scope.launch {
            try {
                _state.value = State.Locating("Поиск библиотек...")
                ensureBundleFromCloudflare()
                KCEF.init(
                    builder = {
                        installDir(installDir)
                        progress {
                            onLocating {
                                _state.value = State.Locating("Подготовка библиотек...")
                            }
                            onDownloading { percent ->
                                // §TZ-DESKTOP-DIST 0.5.2 — этот callback может вызваться
                                // ТОЛЬКО если ensureBundleFromCloudflare упал и KCEF
                                // делает fallback download с github. Норма — мы уже
                                // скачали и install.lock на месте.
                                _state.value = State.Downloading(percent.toInt().coerceIn(0, 100))
                            }
                            onExtracting {
                                _state.value = State.Extracting("Распаковка библиотек...")
                            }
                            onInitializing {
                                _state.value = State.Initializing("Инициализация библиотек...")
                            }
                            onInitialized {
                                _state.value = State.Ready
                            }
                        }
                        settings {
                            cachePath = File(installDir, "browser-cache").absolutePath
                            // §TZ-DESKTOP 0.4.0 — критично: KCEF default ставит
                            // windowlessRenderingEnabled=true, и тогда даже
                            // CefRendering.DEFAULT browser получает warning
                            // "failed to retrieve platform window handle" и
                            // рисует пустой white rect. Для windowed (DEFAULT)
                            // нужен false — тогда Chromium создаёт native NSView.
                            windowlessRenderingEnabled = false

                            // §TZ-DESKTOP 0.4.x — DevTools для тюнинга
                            // CSS-маски на живом DOM Google Sheets. Опт-ин
                            // через env-var (по умолчанию выключено в проде).
                            //   $ OTLD_SHEETS_DEVTOOLS_PORT=9222 ./gradlew :desktop:run
                            //   → chrome://inspect → Configure → localhost:9222
                            //   → видим встроенный Chromium → инспектим DOM.
                            // Use case: при изменении Google'ом классов/ID
                            // элементов tab-bar, title-bar и т.д. — добавляем
                            // актуальные селекторы в SheetsCss.kt.
                            System.getenv("OTLD_SHEETS_DEVTOOLS_PORT")
                                ?.toIntOrNull()
                                ?.let { remoteDebuggingPort = it }
                        }
                    },
                    onError = { err ->
                        _state.value = State.Error(err?.message ?: "kcef_init_failed")
                    },
                    onRestartRequired = {
                        _state.value = State.Error("Требуется перезапуск приложения")
                    },
                )
            } catch (e: Throwable) {
                _state.value = State.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun dispose() {
        if (engine == Engine.KCEF) {
            try { KCEF.disposeBlocking() } catch (_: Throwable) {}
        }
        started = false
        _state.value = State.Idle
    }

    /**
     * §TZ-DESKTOP-DIST 0.5.2 — скачиваем pre-built Chromium bundle с нашего
     * CF Worker (через VPS proxy → R2). Если install.lock уже есть в
     * installDir — bundle ранее установлен, ничего не делаем.
     *
     * Endpoint: `https://api.otlhelper.com/kcef-bundle/jcef-<os>-<arch>.tar.gz`
     *
     * Bundle pre-built нашим CI workflow и содержит уже-нормализованный layout
     * (как KCEF library его делает после download+extract+move). После extract
     * + создания install.lock KCEF.init() пропускает download phase и сразу
     * load native libs.
     *
     * При ошибке скачки/распаковки бросаем — caller (в [start]) обработает в
     * catch и переведёт state в Error.
     */
    private fun ensureBundleFromCloudflare() {
        val lock = File(installDir, "install.lock")
        if (lock.exists()) return

        val bundleName = bundleNameForCurrentPlatform()
            ?: error("Unsupported platform for KCEF bundle: ${platformId()}")
        val url = "https://api.otlhelper.com/kcef-bundle/$bundleName"

        _state.value = State.Locating("Загрузка библиотек...")
        val tmpFile = File.createTempFile("kcef-", ".tar.gz")

        try {
            val req = Request.Builder().url(url).build()
            HttpClientFactory.rest.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("bundle_download_http_${resp.code}")
                }
                val body = resp.body ?: error("bundle_empty_body")
                val total = body.contentLength()
                tmpFile.outputStream().use { out ->
                    val src = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastReportPercent = -1
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = ((read * 100) / total).toInt().coerceIn(0, 99)
                            // Throttle UI updates — раз в 1% хватит, не флудим Compose.
                            if (percent != lastReportPercent) {
                                _state.value = State.Downloading(percent)
                                lastReportPercent = percent
                            }
                        }
                    }
                }
            }

            _state.value = State.Extracting("Распаковка библиотек...")
            extractTarGz(tmpFile, installDir)

            // §TZ-DESKTOP-DIST 0.5.2 — install.lock — маркер для KCEF library
            // что bundle готов. Содержимое не используется, но писать пустой
            // файл достаточно (KCEF делает existsSafely() check).
            lock.writeText(System.currentTimeMillis().toString())
        } finally {
            runCatching { tmpFile.delete() }
        }
    }

    private fun extractTarGz(tarGzFile: File, destDir: File) {
        destDir.mkdirs()
        BufferedInputStream(tarGzFile.inputStream()).use { rawIn ->
            GzipCompressorInputStream(rawIn).use { gz ->
                TarArchiveInputStream(gz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        // Защита от path traversal.
                        val safeName = entry.name.replace('\\', '/')
                        if (safeName.contains("..")) {
                            entry = tar.nextEntry
                            continue
                        }
                        val out = File(destDir, safeName)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { os -> tar.copyTo(os, bufferSize = 64 * 1024) }
                            // Сохраняем executable bit для native libs.
                            val mode = entry.mode
                            if ((mode and 0b001_001_001) != 0) {
                                runCatching { out.setExecutable(true, false) }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

    /**
     * §TZ-DESKTOP-DIST 0.5.2 — имя tarball'а для текущей платформы. URL
     * `api.otlhelper.com/kcef-bundle/<name>`. Соответствует ключам в R2:
     *  - jcef-windows-amd64.tar.gz   (Win 64-bit)
     *  - jcef-macos-amd64.tar.gz     (Mac Intel)
     *  - jcef-macos-arm64.tar.gz     (Mac Apple Silicon)
     *
     * Если платформа не поддержана — null (caller бросит ошибку).
     */
    private fun bundleNameForCurrentPlatform(): String? {
        val os = (System.getProperty("os.name") ?: "").lowercase()
        val arch = (System.getProperty("os.arch") ?: "").lowercase()
        val archNorm = when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64") -> "amd64"
            else -> return null
        }
        val osNorm = when {
            os.contains("win") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("nux") || os.contains("nix") -> "linux"
            else -> return null
        }
        return "jcef-$osNorm-$archNorm.tar.gz"
    }

    private fun platformId(): String {
        val os = System.getProperty("os.name") ?: "unknown"
        val arch = System.getProperty("os.arch") ?: "unknown"
        return "$os/$arch"
    }
}
