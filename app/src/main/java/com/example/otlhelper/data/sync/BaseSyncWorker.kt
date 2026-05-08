package com.example.otlhelper.data.sync

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.otlhelper.ApiClient
import com.example.otlhelper.MainActivity
import com.example.otlhelper.core.push.NotificationChannels
import com.example.otlhelper.core.telemetry.Telemetry
import com.example.otlhelper.data.network.HttpClientFactory
import com.example.otlhelper.data.repository.MolRepository
import com.example.otlhelper.domain.model.MolRecord
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * §TZ-2.3.19 — Надёжная загрузка справочника МОЛ через OkHttp streaming.
 *
 * # Почему уход от DownloadManager
 *
 * До 2.3.18 использовали Android `DownloadManager`. Он надёжный
 * (переживает OEM-killer'ов батареи), но ходит через **системный DNS**
 * и системный TLS stack → трафик идёт напрямую на Cloudflare IP → РФ
 * ISP DPI троттлит (тот же механизм что душил медиа в 2.3.12-2.3.16).
 * База 109KB грузилась 3+ сек, при росте размера тормозила бы больше.
 *
 * В 2.3.19 переходим на OkHttp streaming через наш
 * `HttpClientFactory.downloadClient()` — который имеет DNS override
 * `cdn.otlhelper.com` → VPS `45.12.239.5` → TLS termination → re-encrypt
 * → CF edge → R2. Тот же быстрый путь что у REST/media. На тесте 109KB
 * через VPS ≈ 250мс вместо 3000мс через DM.
 *
 * # OEM-killer risk
 *
 * Worker использует `setForeground()` notification → система не убивает
 * foreground service. Для мелкой базы (<1MB) это точно достаточно.
 * Если база вырастет до 10+MB — рассмотреть split-chunked скачивание.
 *
 * # Шифрование
 *
 * - **В пути**: TLS 1.3 до VPS (наш self-signed cert, pinned через
 *   `HttpClientFactory.buildVpsSslFactory`) → TLS 1.3 VPS→CF. Приватность
 *   trade-off задокументирован в CLAUDE_HANDOVER.md.
 * - **В R2**: имя snapshot содержит 32-char random token → unguessable.
 * - **В Room**: SQLCipher AES-256 через Android Keystore (не меняется).
 */
class BaseSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncEntryPoint {
        fun molRepository(): MolRepository
        fun basePrefs(): BaseSyncPrefs
        fun telemetry(): Telemetry
    }

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            SyncEntryPoint::class.java,
        )
    }
    private val repo: MolRepository get() = entryPoint.molRepository()
    private val prefs: BaseSyncPrefs get() = entryPoint.basePrefs()
    private val telemetry: Telemetry get() = entryPoint.telemetry()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { setForeground(buildForegroundInfo("Проверяю справочник…")) }
            .onFailure { Log.w(TAG, "setForeground failed", it) }

        try {
            return@withContext syncImpl()
        } catch (e: Exception) {
            reportStageError("doWork", e)
            Result.retry()
        }
    }

    private fun reportStageError(stage: String, e: Throwable) {
        runCatching {
            val wrapped = RuntimeException("BaseSync[$stage]: ${e.message ?: e.javaClass.simpleName}", e)
            telemetry.reportCrash(wrapped)
        }
        Log.w(TAG, "stage=$stage failed: ${e.message}", e)
    }

    private fun isFatalError(errorCode: String): Boolean = errorCode in FATAL_ERROR_CODES

    private suspend fun syncImpl(): Result {
        // 1) Проверяем версию на сервере.
        val versionResponse = safeCall("get_version") { ApiClient.getBaseVersion() }
            ?: return Result.retry()
        if (!versionResponse.optBoolean("ok")) {
            val err = versionResponse.optString("error", "unknown")
            if (isFatalError(err)) {
                reportStageError("get_version", RuntimeException("base_version fatal: $err"))
                return Result.failure()
            }
            Log.w(TAG, "base_version transient: $err")
            return Result.retry()
        }
        val base = versionResponse.optJSONObject("base")
        val serverVersion = base?.optString("version", "").orEmpty()
        val serverUpdatedAt = base?.optString("updated_at", "").orEmpty()

        if (serverVersion.isBlank()) {
            Log.w(TAG, "server returned empty version, rescheduling")
            return Result.retry()
        }

        val localVersion = repo.getLocalVersion()
        val hasLocal = repo.hasLocalBase()
        if (hasLocal && localVersion == serverVersion) {
            Log.i(TAG, "base up-to-date: $localVersion")
            prefs.resetProgress()
            return Result.success(workDataOf(KEY_FRESHLY_DOWNLOADED to false))
        }

        // 2) Получаем URL snapshot-файла в R2.
        val urlResponse = safeCall("get_url") { ApiClient.getBaseDownloadUrl() }
            ?: return Result.retry()
        if (!urlResponse.optBoolean("ok")) {
            val err = urlResponse.optString("error", "unknown")
            if (isFatalError(err)) {
                reportStageError("get_url", RuntimeException("base_download_url fatal: $err"))
                return Result.failure()
            }
            Log.w(TAG, "base_download_url transient: $err")
            return Result.retry()
        }
        val data = urlResponse.optJSONObject("data") ?: run {
            reportStageError("get_url", RuntimeException("base_download_url returned ok=true without data field"))
            return Result.retry()
        }
        val url = data.optString("url", "").takeIf { it.isNotBlank() }
            ?: run {
                reportStageError("get_url", RuntimeException("base_download_url returned empty URL"))
                return Result.retry()
            }
        // §TZ-2.3.27 — если сервер прислал blob_key_b64, значит snapshot
        // зашифрован AES-256-GCM. Key+nonce хранятся в D1, доезжают до нас
        // через E2E-encrypted API — VPS не видит их plaintext.
        val blobKeyB64 = data.optString("blob_key_b64", "").takeIf { it.isNotBlank() }
        val blobNonceB64 = data.optString("blob_nonce_b64", "").takeIf { it.isNotBlank() }

        // 3) Стримим bytes через OkHttp (c DNS override → VPS).
        val destFile = snapshotFile()
        destFile.parentFile?.mkdirs()
        if (destFile.exists()) destFile.delete()
        if (destFile.parentFile == null) {
            reportStageError("enqueue", RuntimeException("destFile.parentFile is null — external storage unavailable"))
            return Result.failure()
        }

        runCatching { setForeground(buildForegroundInfo("Загрузка справочника…")) }

        val downloadOk = streamDownload(url, destFile)
        if (!downloadOk) {
            if (destFile.exists()) destFile.delete()
            return Result.retry()
        }

        // §TZ-2.3.27 — если encrypted, расшифровываем файл на диске
        // перед gunzip. Плуггинг в parseAndCommit: он ждёт gzipped bytes.
        if (blobKeyB64 != null && blobNonceB64 != null) {
            val decryptOk = try {
                decryptSnapshotInPlace(destFile, blobKeyB64, blobNonceB64)
                true
            } catch (e: Exception) {
                reportStageError("decrypt", e)
                destFile.delete()
                false
            }
            if (!decryptOk) return Result.retry()
        }

        // 4) Парсим gzipped JSON и коммитим в Room.
        runCatching { setForeground(buildForegroundInfo("Сохраняю справочник…")) }
        val parseResult = try {
            parseAndCommit(destFile, serverVersion, serverUpdatedAt)
        } catch (e: Exception) {
            reportStageError("parse_commit", e)
            destFile.delete()
            return Result.retry()
        }
        destFile.delete()
        prefs.resetProgress()

        return if (parseResult > 0) {
            Log.i(TAG, "base synced: $parseResult records @ $serverVersion via OkHttp stream")
            postFinishedNotification(parseResult)
            Result.success(workDataOf(KEY_FRESHLY_DOWNLOADED to true))
        } else {
            reportStageError("parse_commit", RuntimeException("parse produced 0 records at version=$serverVersion"))
            Result.retry()
        }
    }

    private fun snapshotFile(): File =
        File(applicationContext.getExternalFilesDir(null), "base_snapshot.bin")

    /**
     * §TZ-2.3.27 — in-place decrypt AES-256-GCM blob на диске. Сначала
     * читаем весь файл (109КБ), расшифровываем, перезаписываем. На мобиле
     * 109КБ → ~10мс, никакой заметной паузы.
     */
    private fun decryptSnapshotInPlace(file: java.io.File, keyB64: String, nonceB64: String) {
        val key = android.util.Base64.decode(keyB64, android.util.Base64.DEFAULT)
        val nonce = android.util.Base64.decode(nonceB64, android.util.Base64.DEFAULT)
        val encrypted = file.readBytes()
        val plain = com.example.otlhelper.shared.security.BlobCrypto.decrypt(encrypted, key, nonce)
        file.writeBytes(plain)
    }

    /**
     * OkHttp streaming download с progress callback в foreground notif.
     * Возвращает true при успешной загрузке, false (+ reportStageError) при ошибке.
     */
    private suspend fun streamDownload(url: String, dest: File): Boolean {
        val request = Request.Builder().url(url).build()
        val client = HttpClientFactory.downloadClient()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    reportStageError("download", RuntimeException("HTTP ${response.code} from snapshot URL"))
                    return false
                }
                val body = response.body ?: run {
                    reportStageError("download", RuntimeException("empty response body"))
                    return false
                }
                val total = body.contentLength()
                val source = body.source()
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    var lastProgressTick = 0L
                    while (currentCoroutineContext().isActive && !isStopped) {
                        val read = source.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                        written += read
                        // Обновляем foreground notif не чаще раза в 300мс
                        val now = System.currentTimeMillis()
                        if (now - lastProgressTick > 300) {
                            lastProgressTick = now
                            if (total > 0) {
                                val pct = (written * 100 / total).toInt()
                                runCatching {
                                    setForeground(buildForegroundInfo("Загрузка справочника: ${pct}%"))
                                }
                            }
                        }
                    }
                }
                Log.i(TAG, "streamed ${dest.length()} bytes")
                true
            }
        } catch (e: IOException) {
            reportStageError("download", e)
            false
        } catch (e: Exception) {
            reportStageError("download", e)
            false
        }
    }

    private suspend fun parseAndCommit(file: File, version: String, updatedAt: String): Int {
        val text = GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val root = JSONObject(text)
        val recordsArr = root.optJSONArray("records") ?: JSONArray()
        val records = buildList(recordsArr.length()) {
            for (i in 0 until recordsArr.length()) {
                val obj = recordsArr.optJSONObject(i) ?: continue
                add(
                    MolRecord(
                        remoteId = obj.optLong("id"),
                        warehouseId = obj.optString("warehouse_id"),
                        warehouseName = obj.optString("warehouse_name"),
                        warehouseDesc = obj.optString("warehouse_desc"),
                        warehouseMark = obj.optString("warehouse_mark"),
                        warehouseKeeper = obj.optString("warehouse_keeper"),
                        warehouseWorkPhones = obj.optString("warehouse_work_phones"),
                        fio = obj.optString("fio"),
                        status = obj.optString("status"),
                        position = obj.optString("position"),
                        mobile = obj.optString("mobile"),
                        work = obj.optString("work"),
                        mail = obj.optString("mail"),
                        tab = obj.optString("tab"),
                        searchText = obj.optString("search_text"),
                        createdAt = obj.optString("created_at"),
                    ),
                )
            }
        }
        if (records.isEmpty()) return 0

        repo.replaceAll(records)
        repo.saveMeta(version, updatedAt)
        return records.size
    }

    // ── Foreground notification ──────────────────────────────────────────────

    private fun buildForegroundInfo(text: String): ForegroundInfo {
        val intent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0,
            intent ?: android.content.Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = NotificationCompat.Builder(applicationContext, NotificationChannels.SYNC_ID)
            .setContentTitle("Справочник")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setColor(0xFFD4A467.toInt())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NotificationChannels.SYNC_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationChannels.SYNC_NOTIF_ID, notif)
        }
    }

    private fun postFinishedNotification(count: Int) {
        runCatching {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            val notif = NotificationCompat.Builder(applicationContext, NotificationChannels.SYNC_ID)
                .setContentTitle("Справочник обновлён")
                .setContentText("$count записей")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setColor(0xFFD4A467.toInt())
                .setAutoCancel(true)
                .setTimeoutAfter(5_000L)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(NotificationChannels.SYNC_NOTIF_ID, notif)
        }.onFailure { Log.w(TAG, "post finish notif failed: ${it.message}") }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private inline fun <T> safeCall(stage: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        reportStageError(stage, e)
        null
    }

    companion object {
        private const val TAG = "BaseSync"
        const val UNIQUE_NAME = "otl_base_sync"
        const val UNIQUE_NAME_PERIODIC = "otl_base_sync_periodic"
        const val KEY_LOADED = "loaded"
        const val KEY_TOTAL = "total"
        const val KEY_FRESHLY_DOWNLOADED = "freshly_downloaded"

        private val FATAL_ERROR_CODES = setOf(
            "unknown_action",
            "password_reset",
            "snapshot_build_failed",
            "base_meta_not_found",
        )
    }
}
