package com.example.otlhelper.desktop.macro

import com.example.otlhelper.desktop.BuildInfo
import com.example.otlhelper.desktop.data.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.UserPrincipal
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * §TZ-DESKTOP-0.10.13 — Server-orchestrated macro execution.
 *
 * Высокоуровневый flow для запуска SAP VBS макросов через наш сервер:
 *
 *   1. [runMacro] — единая точка входа. Принимает macroId и password
 *      (валидируется server-side через `validate_macro_password` —
 *      этот endpoint можно добавить позже; пока полагаемся на
 *      [submit_macro_data] валидировать токен один раз).
 *
 *   2. Шаг 1: get_macro_bundle → server возвращает VBS source +
 *      macro_token (TTL 15 мин, one-time).
 *
 *   3. Шаг 2: пишем VBS в temp file с random именем + restrictive ACL
 *      (только current user). Cscript.exe запускается с этим путём.
 *
 *   4. Шаг 3: ждём пока cscript завершится. VBS пишет TSV в OUTPUT_PATH
 *      (тоже temp file, передаём через env var OTL_MACRO_OUTPUT).
 *
 *   5. Шаг 4: читаем TSV, удаляем оба temp файла (sha256 wipe),
 *      шлём submit_macro_data с TSV + macro_token через E2E.
 *
 *   6. Шаг 5: server AES-encrypt'ит TSV, POST'ит в Apps Script webhook.
 *      Apps Script вставляет в wf_plan и триггерит B2.
 *
 *   7. Шаг 6: caller (SheetsWorkspace) делает polling B2 status через
 *      pollUntilDoneViaServer и снимает lock-overlay когда B2 done.
 *
 * **Только Windows.** На Mac возвращает [Result.Failure(NOT_WINDOWS)].
 *
 * **VBS код не лежит в EXE.** Каждый запуск — fresh download через E2E.
 * Decompile EXE → не виден ни VBS source, ни AES key, ни webhook URL.
 */
object MacroOrchestrator {

    sealed class Result {
        object Success : Result()
        data class Failure(val reason: String, val detail: String? = null) : Result()
    }

    private const val OUTPUT_ENV = "OTL_MACRO_OUTPUT"
    private const val MACRO_TIMEOUT_SEC = 600L  // 10 минут SAP work max

    /**
     * Запустить macro целиком: get bundle (валидируется password
     * server-side) → write VBS → cscript → read TSV → submit. Caller
     * потом polls B2 status отдельно через
     * [com.example.otlhelper.desktop.sheets.SheetsActionRunner.pollUntilDoneViaServer].
     *
     * @param actionId — id action из SheetsRegistry (server lookup'ит
     *                   macroId и requiresPassword по нему)
     * @param password — введённый юзером, валидируется server-side
     */
    suspend fun runMacro(actionId: String, password: String? = null): Result = withContext(Dispatchers.IO) {
        com.example.otlhelper.desktop.core.debug.DebugLogger.log(
            "MACRO", "runMacro START actionId=$actionId hasPassword=${!password.isNullOrEmpty()}"
        )
        val r = try {
            runMacroInternal(actionId, password)
        } catch (t: Throwable) {
            com.example.otlhelper.desktop.core.debug.DebugLogger.error(
                "MACRO", "runMacroInternal threw exception", t
            )
            Result.Failure(
                "EXCEPTION_${t.javaClass.simpleName}",
                (t.message ?: "no message").take(300)
            )
        }
        com.example.otlhelper.desktop.core.debug.DebugLogger.log(
            "MACRO", "runMacro END result=${if (r is Result.Success) "Success" else "Failure: ${(r as Result.Failure).reason} | ${r.detail.orEmpty().take(200)}"}"
        )
        // diagnostic log на сервер (для tail на server-side когда юзер дёргает)
        runCatching {
            ApiClient.request("client_debug") {
                put("category", "macro_run")
                put("action_id", actionId)
                put("status", if (r is Result.Success) "ok" else "fail")
                if (r is Result.Failure) {
                    put("reason", r.reason)
                    put("detail", r.detail ?: "")
                }
            }
        }
        r
    }

    private suspend fun runMacroInternal(actionId: String, password: String?): Result = withContext(Dispatchers.IO) {
        if (!BuildInfo.IS_WINDOWS) {
            return@withContext Result.Failure("NOT_WINDOWS",
                "Макрос работает только на Windows (требуется SAP GUI Scripting)")
        }

        // 1. Запрашиваем bundle с сервера (включает password validate)
        com.example.otlhelper.desktop.core.debug.DebugLogger.log("MACRO", "Step 1: get_macro_bundle")
        val bundle = runCatching {
            ApiClient.request("get_macro_bundle") {
                put("action_id", actionId)
                if (!password.isNullOrEmpty()) put("password", password)
            }
        }.getOrElse { e ->
            com.example.otlhelper.desktop.core.debug.DebugLogger.error("MACRO", "get_macro_bundle network error", e)
            return@withContext Result.Failure("BUNDLE_FETCH", e.message)
        }
        if (!bundle.optBoolean("ok", false)) {
            val err = bundle.optString("error")
            com.example.otlhelper.desktop.core.debug.DebugLogger.warn("MACRO", "get_macro_bundle returned error: $err")
            return@withContext Result.Failure(
                if (err == "wrong_password") "WRONG_PASSWORD" else "BUNDLE_ERROR",
                err
            )
        }

        val vbsSource = bundle.optString("vbs_source")
        val macroToken = bundle.optString("macro_token")
        if (vbsSource.isEmpty() || macroToken.isEmpty()) {
            com.example.otlhelper.desktop.core.debug.DebugLogger.warn("MACRO", "bundle missing vbs_source or macro_token")
            return@withContext Result.Failure("BUNDLE_INCOMPLETE")
        }
        com.example.otlhelper.desktop.core.debug.DebugLogger.log("MACRO", "bundle OK: vbs ${vbsSource.length} chars, token ${macroToken.length} chars")

        // 2. Подготавливаем temp файлы (VBS + output TSV)
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val sessionId = UUID.randomUUID().toString().take(12)
        val vbsFile = File(tempDir, "otl_macro_${sessionId}.vbs")
        val outputFile = File(tempDir, "otl_macro_out_${sessionId}.txt")

        try {
            vbsFile.writeText(vbsSource, Charsets.UTF_8)
            // §TZ-DESKTOP-0.10.14 — ACL restriction TEMPORARILY DISABLED.
            // Подозрение: restrictAclToCurrentUser убирает inheritance →
            // cscript почему-то не может прочитать файл (или специфика NTFS).
            // Папка TEMP сама по себе restricted к user (default ACL).
            // restrictAclToCurrentUser(vbsFile)

            // §TZ-DESKTOP-0.10.17 — Убрали //B (batch mode). //B подавляет
            // stderr → 0.10.16 показал "(no stderr)" даже при exit 1.
            // Без //B cscript печатает ошибки в stderr нормально.
            // MsgBox в VBS НЕТ (все удалены) — //B был нужен только
            // для подавления UI-диалогов, но их нет.
            // ALSO: НЕ удаляем VBS file (закомментил secureDelete) —
            // юзер может найти файл в TEMP и запустить cscript руками
            // для дальнейшей диагностики.
            val proc = ProcessBuilder(
                "cscript.exe",
                "//Nologo",
                vbsFile.absolutePath,
            ).apply {
                environment()[OUTPUT_ENV] = outputFile.absolutePath
                redirectErrorStream(true)
                // Не DISCARD — читаем для diagnostic
            }.start()

            com.example.otlhelper.desktop.core.debug.DebugLogger.log(
                "MACRO", "Step 3: cscript spawned, vbs=${vbsFile.absolutePath}, output=${outputFile.absolutePath}"
            )

            val finished = proc.waitFor(MACRO_TIMEOUT_SEC, TimeUnit.SECONDS)
            val cscriptOutput = runCatching {
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(1500) }
            }.getOrDefault("(no output captured)")

            com.example.otlhelper.desktop.core.debug.DebugLogger.log(
                "MACRO", "cscript finished=$finished exit=${if (finished) proc.exitValue() else -1} stderr=${cscriptOutput.take(200)}"
            )

            if (!finished) {
                proc.destroyForcibly()
                return@withContext Result.Failure("MACRO_TIMEOUT",
                    "VBS не завершился за $MACRO_TIMEOUT_SEC сек | $cscriptOutput")
            }
            if (proc.exitValue() != 0) {
                return@withContext Result.Failure(
                    "MACRO_EXIT_${proc.exitValue()}",
                    cscriptOutput.ifBlank { "(no stderr)" }
                )
            }

            // 4. Читаем output TSV
            if (!outputFile.exists() || outputFile.length() == 0L) {
                com.example.otlhelper.desktop.core.debug.DebugLogger.warn(
                    "MACRO", "Step 4: output file missing or empty (path=${outputFile.absolutePath} exists=${outputFile.exists()} size=${if (outputFile.exists()) outputFile.length() else -1L})"
                )
                return@withContext Result.Failure("NO_OUTPUT",
                    "VBS завершился но output файл пуст или отсутствует")
            }
            val tsv = outputFile.readText(Charsets.UTF_16LE).removePrefix("﻿")
            com.example.otlhelper.desktop.core.debug.DebugLogger.log(
                "MACRO", "Step 4: TSV read ${tsv.length} chars"
            )
            if (tsv.isBlank()) {
                return@withContext Result.Failure("EMPTY_TSV")
            }

            // 5. Submit на сервер через E2E
            com.example.otlhelper.desktop.core.debug.DebugLogger.log("MACRO", "Step 5: submit_macro_data")
            val submitResp = runCatching {
                ApiClient.request("submit_macro_data") {
                    put("macro_token", macroToken)
                    put("data", tsv)
                }
            }.getOrElse { e ->
                com.example.otlhelper.desktop.core.debug.DebugLogger.error("MACRO", "submit_macro_data network", e)
                return@withContext Result.Failure("SUBMIT_NETWORK", e.message)
            }
            com.example.otlhelper.desktop.core.debug.DebugLogger.log(
                "MACRO", "submit response ok=${submitResp.optBoolean("ok",false)} rows=${submitResp.optInt("rows_inserted",-1)} b2=${submitResp.optBoolean("b2_triggered",false)} body=${submitResp.toString().take(300)}"
            )

            if (!submitResp.optBoolean("ok", false)) {
                return@withContext Result.Failure(
                    "SUBMIT_ERROR",
                    submitResp.optString("error") + " " + submitResp.optString("detail")
                )
            }

            // Параллельно small-delay перед polling — чтобы B2 успел
            // стартануть на Apps Script side.
            delay(2_000)
            return@withContext Result.Success
        } finally {
            // §TZ-DESKTOP-0.10.17 — secureDelete VBS ВРЕМЕННО ОТКЛЮЧЕН для
            // diagnostic. После решения проблемы вернуть. Output file всё
            // ещё чистится (не содержит секретов).
            // secureDelete(vbsFile)
            secureDelete(outputFile)
        }
    }

    /**
     * Restrict ACL на файл — только current user может читать/писать.
     * Защита от других процессов (на Win) которые могли бы прочитать
     * VBS source во время короткого окна между write и cscript spawn.
     */
    private fun restrictAclToCurrentUser(file: File) {
        runCatching {
            val view = Files.getFileAttributeView(file.toPath(), AclFileAttributeView::class.java)
                ?: return@runCatching
            val owner: UserPrincipal = view.owner
            val newAcl = listOf(
                AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(*AclEntryPermission.values())
                    .build()
            )
            view.acl = newAcl
        }
    }

    /**
     * Best-effort secure-delete: overwrite content random bytes (1 pass)
     * → delete. Не настоящий secure-erase (на SSD не работает на 100%
     * из-за wear-leveling), но защищает от forensic file-recovery после
     * наивного удаления.
     */
    private fun secureDelete(file: File) {
        runCatching {
            if (!file.exists()) return@runCatching
            val length = file.length()
            if (length > 0 && length < 100 * 1024 * 1024) {  // не overwrite файлы >100MB
                file.outputStream().use { os ->
                    val random = java.security.SecureRandom()
                    val buf = ByteArray(8192)
                    var written = 0L
                    while (written < length) {
                        random.nextBytes(buf)
                        val toWrite = minOf(buf.size.toLong(), length - written).toInt()
                        os.write(buf, 0, toWrite)
                        written += toWrite
                    }
                    os.flush()
                }
            }
            file.delete()
        }
    }
}
