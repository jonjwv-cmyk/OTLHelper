package com.example.otlhelper.desktop.sap

import com.example.otlhelper.desktop.BuildInfo
import com.example.otlhelper.desktop.core.debug.DebugLogger
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * §0.11.10 — Детектор открытых документов в SAP.
 *
 * Запускает bundled VBS sap_detect_open.vbs через cscript, ждёт завершения
 * (короткое — ~500ms типично), читает JSON output.
 *
 * Возвращает [DetectResult]:
 *   • Success(list of SapOpenDoc) — может быть 0/1/many документов
 *   • SapNotRunning — SAP GUI не запущен
 *   • ScriptingDisabled — SAP scripting выключен
 *   • InternalError — что-то в нашем коде
 */
object SapDocumentDetector {

    private const val TAG = "SAP_DETECT"
    /**
     * §1.0.2 — VBS НЕ в %TEMP%. Раньше: `C:\Users\<user>\AppData\Local\Temp\otl_sap_detect_*.vbs`,
     * на что Kaspersky PDM срабатывал как "not-a-virus:PDM:WebToolbar.Win32.MultiPlug.ab"
     * (классический malware path: .vbs в Temp = быстрая download-and-execute).
     * Теперь: `%LOCALAPPDATA%\OTLD Helper\macros\` — наша install папка,
     * для AV выглядит как legit data dir приложения.
     */
    private val tempDir: File by lazy {
        val localAppData = System.getenv("LOCALAPPDATA")
            ?: System.getProperty("user.home", ".")
        val dir = File(localAppData, "OTLD Helper${File.separator}macros")
        if (!dir.exists()) dir.mkdirs()
        // Fallback на %TEMP% если не смогли создать (например read-only fs / ACL).
        if (dir.exists() && dir.canWrite()) dir
        else File(System.getProperty("java.io.tmpdir"))
    }

    fun detect(timeoutMs: Long = 5_000): DetectResult {
        if (!BuildInfo.IS_WINDOWS) {
            return DetectResult.InternalError("not Windows")
        }
        val vbsContent = readVbsResource()
            ?: return DetectResult.InternalError("VBS resource not bundled")

        val sessionId = UUID.randomUUID().toString().take(8)
        val tempVbs = File(tempDir, "otl_sap_detect_$sessionId.vbs")
        val outputJson = File(tempDir, "otl_sap_detect_$sessionId.json")

        try {
            tempVbs.writeText(vbsContent, Charsets.UTF_8)
        } catch (e: Throwable) {
            return DetectResult.InternalError("write VBS failed: ${e.message}")
        }

        try {
            val proc = ProcessBuilder("cscript.exe", "//Nologo", tempVbs.absolutePath)
                .apply {
                    environment()["OTL_SAP_DETECT_OUT"] = outputJson.absolutePath
                }
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()

            val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                DebugLogger.warn(TAG, "cscript timeout ${timeoutMs}ms")
                return DetectResult.InternalError("VBS timeout")
            }

            val exit = proc.exitValue()
            DebugLogger.log(TAG, "cscript exit=$exit, output=${outputJson.absolutePath}")

            if (!outputJson.exists()) {
                return DetectResult.InternalError("VBS produced no output (exit=$exit)")
            }
            val json = outputJson.readText(Charsets.UTF_16LE).removePrefix("﻿")
            DebugLogger.log(TAG, "json=${json.take(200)}")
            return parseJson(json)
        } catch (e: Throwable) {
            DebugLogger.error(TAG, "detect failed", e)
            return DetectResult.InternalError(e.message ?: "exec failed")
        } finally {
            runCatching { tempVbs.delete() }
            runCatching { outputJson.delete() }
        }
    }

    private fun parseJson(json: String): DetectResult {
        return try {
            val obj = JSONObject(json)
            // §0.11.11 — VBS пишет debug-trace для диагностики
            // (children_count, conns_count, active_session_ok и т.п.)
            val debug = obj.optString("debug", "")
            if (debug.isNotEmpty()) {
                DebugLogger.log(TAG, "vbs_debug: $debug")
            }
            val ok = obj.optBoolean("ok", false)
            if (!ok) {
                val err = obj.optString("error", "unknown_error")
                return when (err) {
                    "sap_not_running" -> DetectResult.SapNotRunning
                    "scripting_disabled" -> DetectResult.ScriptingDisabled
                    else -> DetectResult.InternalError(err)
                }
            }
            val sessionsArr = obj.optJSONArray("sessions")
            val list = mutableListOf<SapOpenDoc>()
            if (sessionsArr != null) {
                for (i in 0 until sessionsArr.length()) {
                    val s = sessionsArr.getJSONObject(i)
                    val docTypeStr = s.optString("docType", "OTHER")
                    val docType = when (docTypeStr.uppercase()) {
                        "ORDER" -> SapDocType.ORDER
                        "DELIVERY" -> SapDocType.DELIVERY
                        else -> SapDocType.OTHER
                    }
                    list += SapOpenDoc(
                        sessionId = s.optString("sessId", ""),
                        transactionCode = s.optString("txn", ""),
                        documentNumber = s.optString("docNum", ""),
                        documentType = docType,
                        titleText = s.optString("title", ""),
                    )
                }
            }
            DetectResult.Success(list)
        } catch (e: Throwable) {
            DebugLogger.warn(TAG, "parseJson failed: ${e.message}")
            DetectResult.InternalError("parse failed: ${e.message}")
        }
    }

    private fun readVbsResource(): String? {
        return runCatching {
            javaClass.classLoader
                ?.getResourceAsStream("sap_detect_open.vbs")
                ?.use { it.readBytes().decodeToString() }
        }.getOrNull()
    }
}

enum class SapDocType { ORDER, DELIVERY, OTHER }

data class SapOpenDoc(
    val sessionId: String,
    val transactionCode: String,
    val documentNumber: String,
    val documentType: SapDocType,
    val titleText: String,
) {
    /** Имеет валидный номер документа? */
    val hasNumber: Boolean
        get() = documentNumber.isNotEmpty() && documentNumber.all { it.isDigit() }

    /** Только номер (без декорации) для display. */
    val displayLabel: String
        get() = when {
            !hasNumber -> "[$transactionCode] $titleText".take(80)
            documentType == SapDocType.ORDER -> "Заказ $documentNumber"
            documentType == SapDocType.DELIVERY -> "Поставка $documentNumber"
            else -> "$transactionCode $documentNumber"
        }
}

sealed class DetectResult {
    data class Success(val docs: List<SapOpenDoc>) : DetectResult()
    object SapNotRunning : DetectResult()
    object ScriptingDisabled : DetectResult()
    data class InternalError(val message: String) : DetectResult()
}
