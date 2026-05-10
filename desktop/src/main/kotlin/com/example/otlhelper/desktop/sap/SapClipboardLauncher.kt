package com.example.otlhelper.desktop.sap

import com.example.otlhelper.desktop.BuildInfo
import com.example.otlhelper.desktop.core.debug.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.util.UUID

/**
 * §0.11.9 — Orchestrator для triple Ctrl+C → SAP open.
 *
 * Flow:
 *  1. ClipboardTripleCopyDetector видит 3 копирования за 1.5 сек
 *  2. Этот объект читает clipboard, парсит SapNumberParser
 *  3. Если invalid format → publish event (Compose UI rendert error dialog)
 *  4. Если valid → распаковывает bundled VBS из resources в %TEMP% →
 *     запускает cscript с env vars (OTL_SAP_NUMBER, OTL_SAP_TYPE)
 *  5. SAP открывает заказ/поставку в новом окне
 *
 * Listener стартует на init() и работает пока приложение запущено.
 */
object SapClipboardLauncher {

    private const val TAG = "SAP_LAUNCHER"
    private val tempDir: File by lazy { File(System.getProperty("java.io.tmpdir")) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableStateFlow<SapLauncherEvent?>(null)
    val events: StateFlow<SapLauncherEvent?> = _events.asStateFlow()

    @Volatile private var initialized = false

    /**
     * Запускает clipboard polling. Вызывается один раз при старте приложения.
     * No-op если уже запущено или не Win.
     */
    fun init() {
        if (initialized) return
        if (!BuildInfo.IS_WINDOWS) {
            DebugLogger.log(TAG, "skip init: not Windows (current=${BuildInfo.OS})")
            return
        }
        initialized = true
        DebugLogger.log(TAG, "init: starting triple-copy detector")
        ClipboardTripleCopyDetector.start { handleTriple() }
    }

    fun shutdown() {
        if (!initialized) return
        ClipboardTripleCopyDetector.stop()
        initialized = false
        DebugLogger.log(TAG, "shutdown")
    }

    /** Юзер закрыл error dialog — сбрасываем event. */
    fun dismissEvent() {
        _events.value = null
    }

    private fun handleTriple() {
        DebugLogger.log(TAG, "triple Ctrl+C detected, reading clipboard")
        val text = readClipboard()
        DebugLogger.log(TAG, "clipboard='${text.take(80).replace("\n", "\\n")}' len=${text.length}")
        val target = SapNumberParser.parse(text)
        if (target == null) {
            DebugLogger.log(TAG, "clipboard invalid for SAP — publish error event")
            _events.value = SapLauncherEvent.InvalidFormat(text.take(80))
            // Auto-dismiss через 4 сек
            scope.launch {
                delay(4_000)
                if (_events.value is SapLauncherEvent.InvalidFormat) {
                    _events.value = null
                }
            }
            return
        }
        DebugLogger.log(
            TAG,
            "target=${target::class.simpleName} number=${target.number}",
        )
        runVbs(target)
    }

    private fun readClipboard(): String {
        return runCatching {
            val cb = Toolkit.getDefaultToolkit().systemClipboard
            cb.getData(DataFlavor.stringFlavor) as? String ?: ""
        }.onFailure {
            DebugLogger.warn(TAG, "readClipboard failed: ${it.message}")
        }.getOrDefault("")
    }

    private fun runVbs(target: SapTarget) {
        val vbsContent = readVbsResource() ?: run {
            DebugLogger.error(TAG, "VBS resource not found in classpath")
            _events.value = SapLauncherEvent.InternalError("VBS resource not bundled")
            scheduleAutoDismissError()
            return
        }
        val sessionId = UUID.randomUUID().toString().take(8)
        val tempVbs = File(tempDir, "otl_sap_open_$sessionId.vbs")

        try {
            tempVbs.writeText(vbsContent, Charsets.UTF_8)
        } catch (e: Throwable) {
            DebugLogger.error(TAG, "failed to write temp VBS: ${e.message}")
            _events.value = SapLauncherEvent.InternalError("write VBS failed: ${e.message}")
            scheduleAutoDismissError()
            return
        }

        val typeStr = when (target) {
            is SapTarget.Order -> "ORDER"
            is SapTarget.Delivery -> "DELIVERY"
        }

        try {
            ProcessBuilder("cscript.exe", "//Nologo", tempVbs.absolutePath)
                .apply {
                    environment()["OTL_SAP_NUMBER"] = target.number
                    environment()["OTL_SAP_TYPE"] = typeStr
                }
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            DebugLogger.log(
                TAG,
                "cscript spawned: vbs=${tempVbs.absolutePath} type=$typeStr",
            )
        } catch (e: Throwable) {
            DebugLogger.error(TAG, "cscript spawn failed", e)
            _events.value = SapLauncherEvent.InternalError("cscript spawn failed: ${e.message}")
            scheduleAutoDismissError()
            return
        }

        // Cleanup VBS через 60s (cscript должен успеть прочитать)
        scope.launch {
            delay(60_000)
            runCatching { tempVbs.delete() }
        }
    }

    private fun readVbsResource(): String? {
        return runCatching {
            javaClass.classLoader
                ?.getResourceAsStream("sap_open_clipboard.vbs")
                ?.use { it.readBytes().decodeToString() }
        }.getOrNull()
    }

    private fun scheduleAutoDismissError() {
        scope.launch {
            delay(4_000)
            if (_events.value is SapLauncherEvent.InternalError) {
                _events.value = null
            }
        }
    }
}

sealed class SapLauncherEvent {
    /** Clipboard не пустой но формат не подходит. */
    data class InvalidFormat(val clipPreview: String) : SapLauncherEvent()
    /** Внутренняя ошибка (не VBS — это мы сами). */
    data class InternalError(val message: String) : SapLauncherEvent()
}
