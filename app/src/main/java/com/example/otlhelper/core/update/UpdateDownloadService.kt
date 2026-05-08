package com.example.otlhelper.core.update

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.otlhelper.MainActivity
import com.example.otlhelper.R
import com.example.otlhelper.core.push.NotificationChannels
import com.example.otlhelper.data.network.HttpClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.FileOutputStream
import java.io.IOException

/**
 * §TZ-2.5.4 — Foreground Service для **manual** скачивания APK обновления.
 *
 * Сценарий: юзер нажал «Скачать» в SoftUpdateDialog → запускается этот
 * Service → startForeground с notification «Загрузка обновления N%». Юзер
 * может свернуть приложение / закрыть его — service продолжит скачивать.
 * По завершении notification меняется на «Готово, нажмите для установки» с
 * tap-intent открывающим MainActivity (где появится install confirm).
 *
 * Использует тот же [HttpClientFactory.downloadClient] что и старый
 * inline-flow в [AppUpdate] (DNS override → VPS, self-signed cert).
 *
 * Прогресс broadcast'ится через LocalBroadcastManager → UI dialog ловит его
 * чтобы показать progress bar если юзер вернулся.
 */
class UpdateDownloadService : Service() {

    companion object {
        const val ACTION_START = "com.example.otlhelper.update.START"
        const val ACTION_CANCEL = "com.example.otlhelper.update.CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_VERSION = "version"
        const val EXTRA_EXPECTED_SHA = "expected_sha"

        const val BROADCAST_PROGRESS = "com.example.otlhelper.update.PROGRESS"
        const val BROADCAST_DONE = "com.example.otlhelper.update.DONE"
        const val BROADCAST_ERROR = "com.example.otlhelper.update.ERROR"
        const val EXTRA_PROGRESS_PCT = "pct"
        const val EXTRA_ERROR_MSG = "msg"

        private const val FOREGROUND_NOTIF_ID = 42_002
        private const val DONE_NOTIF_ID = 42_003

        fun start(context: Context, url: String, version: String, expectedSha: String) {
            val i = Intent(context, UpdateDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_VERSION, version)
                putExtra(EXTRA_EXPECTED_SHA, expectedSha)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun cancel(context: Context) {
            val i = Intent(context, UpdateDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(i)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * §TZ-2.5.5 — Wake/WiFi locks. Без них Android при выключении экрана
     * через 30 сек идёт в Doze: CPU паузится, WiFi отключается, OkHttp
     * read() висит, скачка прерывается. С PARTIAL_WAKE_LOCK + WIFI_FULL_HIGH_PERF
     * download продолжается даже когда экран выключен.
     */
    private fun acquireLocks() {
        if (wakeLock != null || wifiLock != null) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OTL:UpdateDownload").apply {
                setReferenceCounted(false)
                acquire(30L * 60L * 1000L) // max 30 min — на всякий fail-safe
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "OTL:UpdateDownloadWifi",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                downloadJob?.cancel()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
                val version = intent?.getStringExtra(EXTRA_VERSION).orEmpty()
                val expectedSha = intent?.getStringExtra(EXTRA_EXPECTED_SHA).orEmpty()
                if (url.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                NotificationChannels.ensureCreated(this)
                startForeground(FOREGROUND_NOTIF_ID, buildProgressNotification(version, 0))
                acquireLocks()
                downloadJob?.cancel()
                downloadJob = scope.launch {
                    runDownload(url, version, expectedSha)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun runDownload(url: String, version: String, expectedSha: String) {
        val ctx = applicationContext
        val target = AppUpdate.apkFile(ctx)
        runCatching { target.delete() }

        if (expectedSha.isNotBlank()) AppUpdate.setExpectedSha256(ctx, expectedSha)

        try {
            val request = Request.Builder().url(url).build()
            HttpClientFactory.downloadClient().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IOException("empty body")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(target).use { out ->
                        // §TZ-2.5.4 — 256K buffer (вместо 64K) для меньшего overhead на write
                        // syscalls и лучшего throughput на быстрых сетях.
                        val buf = ByteArray(256 * 1024)
                        var downloaded = 0L
                        var lastReportedPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt()
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct
                                    updateProgress(version, pct)
                                }
                            }
                        }
                        out.flush()
                    }
                }
            }
            AppUpdate.markDownloaded(ctx, version, expectedSha)

            // SHA verification — если provided.
            if (expectedSha.isNotBlank() && !AppUpdate.verifyApkSha256(ctx, expectedSha)) {
                target.delete()
                broadcastError("Проверка SHA не прошла — файл удалён")
                showDoneNotification(version, ok = false, error = "SHA mismatch")
                stopForegroundCompat()
                stopSelf()
                return
            }

            broadcastDone()
            showDoneNotification(version, ok = true, error = "")
        } catch (t: Throwable) {
            runCatching { target.delete() }
            broadcastError(t.message ?: t.javaClass.simpleName)
            showDoneNotification(version, ok = false, error = t.message ?: "ошибка")
        } finally {
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun updateProgress(version: String, pct: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(FOREGROUND_NOTIF_ID, buildProgressNotification(version, pct))
        val intent = Intent(BROADCAST_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS_PCT, pct)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDone() {
        val intent = Intent(BROADCAST_DONE).apply { setPackage(packageName) }
        sendBroadcast(intent)
    }

    private fun broadcastError(msg: String) {
        val intent = Intent(BROADCAST_ERROR).apply {
            putExtra(EXTRA_ERROR_MSG, msg)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun buildProgressNotification(version: String, pct: Int): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (version.isNotBlank()) "Загрузка обновления $version" else "Загрузка обновления"
        return NotificationCompat.Builder(this, NotificationChannels.SYNC_ID)
            .setContentTitle(title)
            .setContentText("$pct%")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showDoneNotification(version: String, ok: Boolean, error: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("install_apk", ok)
        }
        val pi = PendingIntent.getActivity(
            this, 1, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (ok)
            "Обновление $version скачано"
        else
            "Не удалось скачать обновление"
        val text = if (ok) "Нажмите для установки" else error.ifBlank { "Попробуйте снова" }
        val notif = NotificationCompat.Builder(this, NotificationChannels.SYNC_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(DONE_NOTIF_ID, notif)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLocks()
        scope.cancel()
    }
}
