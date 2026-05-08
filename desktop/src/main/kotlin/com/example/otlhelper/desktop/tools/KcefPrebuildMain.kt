package com.example.otlhelper.desktop.tools

import dev.datlag.kcef.KCEF
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * §TZ-DESKTOP-DIST 0.5.1 — build-time prebuild Chromium runtime для Win EXE.
 *
 * Запускается из Gradle JavaExec task'а (см. desktop/build.gradle.kts) ТОЛЬКО
 * на CI Win runner перед `:desktop:packageReleaseExe`. Цель — заранее скачать
 * JCEF native libs (~150 MB), сложить их в `desktop/resources/windows-x64/kcef-bundle/`
 * и закомитить как часть EXE installer'а.
 *
 * На юзеровской Win-машине runtime download падает (Defender / DPI / прокси),
 * поэтому bundle'им offline. SheetsRuntime.ensureBundle() копирует папку из
 * resources в `~/.otldhelper/kcef-bundle/` при первом запуске + создаёт
 * install.lock → KCEF.init() видит lock → пропускает download phase.
 *
 * Особенности запуска:
 * - JVM property `kcef.installDir` — куда KCEF положит bundle
 * - JVM property `kcef.timeoutSeconds` — сколько ждать install.lock (default 600)
 * - Headless mode (java.awt.headless=true) — без Display нужно только для
 *   download+extract; full CefApp.init не нужен
 * - Polling install.lock каждую секунду; как только появился — exit(0)
 */
fun main() {
    val installDir = System.getProperty("kcef.installDir")
        ?: error("kcef.installDir system property required")
    val timeoutSeconds = System.getProperty("kcef.timeoutSeconds")?.toLongOrNull() ?: 600L

    val target = File(installDir).apply { mkdirs() }
    println("[KcefPrebuild] installDir=${target.absolutePath} timeout=${timeoutSeconds}s")

    val installLock = File(target, "install.lock")
    if (installLock.exists()) {
        println("[KcefPrebuild] install.lock already exists — nothing to do")
        exitProcess(0)
    }

    val watcher = Thread {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (installLock.exists()) {
                println("[KcefPrebuild] install.lock detected — bundle ready")
                Thread.sleep(2000)
                exitProcess(0)
            }
            Thread.sleep(1000)
        }
        System.err.println("[KcefPrebuild] TIMEOUT after ${timeoutSeconds}s, install.lock not found")
        exitProcess(2)
    }.apply {
        isDaemon = true
        name = "kcef-prebuild-watcher"
        start()
    }

    runBlocking {
        try {
            KCEF.init(
                builder = {
                    installDir(target)
                    // GitHub Download.Builder.release() (без аргументов) =
                    // последний release JCEF-форка (то же что release(true)
                    // в старом deprecated API).
                    download { github { release() } }
                    progress {
                        onDownloading { percent ->
                            println("[KcefPrebuild] downloading ${percent.toInt()}%")
                        }
                        onExtracting {
                            println("[KcefPrebuild] extracting...")
                        }
                        onInitializing {
                            println("[KcefPrebuild] initializing... (will skip via watcher)")
                        }
                        onInitialized {
                            println("[KcefPrebuild] initialized — bundle ready")
                        }
                    }
                },
                onError = { err ->
                    System.err.println("[KcefPrebuild] ERROR: ${err?.message}")
                    err?.printStackTrace()
                    exitProcess(3)
                },
                onRestartRequired = {
                    println("[KcefPrebuild] restart-required (normal — bundle is on disk now)")
                    if (installLock.exists()) exitProcess(0)
                },
            )
        } catch (e: Throwable) {
            if (installLock.exists()) {
                println("[KcefPrebuild] init threw but install.lock present — bundle ready")
                exitProcess(0)
            }
            System.err.println("[KcefPrebuild] FATAL: ${e.message}")
            e.printStackTrace()
            exitProcess(4)
        }
    }
    watcher.join()
}
