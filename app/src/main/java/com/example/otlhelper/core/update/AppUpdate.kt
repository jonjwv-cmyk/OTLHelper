package com.example.otlhelper.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.otlhelper.BuildConfig
import com.example.otlhelper.data.network.HttpClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Shared APK update helpers.
 *
 * Stores the downloaded APK under externalFilesDir/Downloads/otl_update.apk
 * and keeps a version tag in SharedPreferences so we can tell whether the
 * cached file matches the version we want to install. Avoids re-downloading
 * when the user dismisses the install prompt and comes back later.
 *
 * §TZ-2.3.40 — Скачивание идёт через наш [HttpClientFactory.downloadClient]:
 * DNS override *.otlhelper.com → VPS, self-signed cert trust. Ранее был
 * системный [android.app.DownloadManager], но он ходит через системный DNS
 * напрямую к CF → на сетях с DPI/блокировкой api.otlhelper.com APK не
 * качался, хотя остальной трафик работал (OkHttp через VPS).
 */
object AppUpdate {
    private const val APK_FILE_NAME = "otl_update.apk"
    private const val PREFS_NAME = "app_update"
    private const val KEY_APK_VERSION = "apk_version"
    private const val KEY_APK_SHA256 = "apk_sha256"

    fun apkFile(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)

    fun storedVersion(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APK_VERSION, "").orEmpty()

    fun storedSha256(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APK_SHA256, "").orEmpty()

    fun setExpectedSha256(context: Context, sha256: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APK_SHA256, sha256.trim().lowercase())
            .apply()
    }

    fun isApkReadyFor(context: Context, version: String): Boolean {
        if (version.isBlank()) return false
        val f = apkFile(context)
        if (!f.exists() || f.length() <= 0L) return false
        return storedVersion(context) == version
    }

    fun markDownloaded(context: Context, version: String, sha256: String = "") {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.putString(KEY_APK_VERSION, version)
        if (sha256.isNotBlank()) editor.putString(KEY_APK_SHA256, sha256.lowercase())
        editor.apply()
    }

    fun clearDownload(context: Context) {
        runCatching { apkFile(context).delete() }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_APK_VERSION)
            .remove(KEY_APK_SHA256)
            .apply()
    }

    /**
     * §TZ-2.3.40 — Вызывается при старте приложения. Если установленный
     * [BuildConfig.VERSION_NAME] совпадает с ранее скачанным APK — значит
     * юзер уже успешно обновился на эту версию, файл в Downloads/ больше
     * не нужен. Чистим чтобы не занимать место на устройстве.
     */
    fun clearStaleAfterUpdate(context: Context) {
        val stored = storedVersion(context)
        if (stored.isNotEmpty() && stored == BuildConfig.VERSION_NAME) {
            clearDownload(context)
        }
    }

    fun computeApkSha256(context: Context): String {
        val f = apkFile(context)
        if (!f.exists() || f.length() <= 0L) return ""
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) { "" }
    }

    fun verifyApkSha256(context: Context, expected: String): Boolean {
        val exp = expected.lowercase().trim()
        if (exp.isEmpty()) return true
        val actual = computeApkSha256(context)
        return actual.isNotEmpty() && actual.equals(exp, ignoreCase = true)
    }

    /**
     * §TZ-2.3.40 — Стримит APK в [apkFile] через [HttpClientFactory.downloadClient]
     * (VPS-override DNS, self-signed cert trust — тот же канал что REST/WS).
     *
     * Возвращает [Job]; UI отменяет его когда юзер закрывает диалог.
     * [onProgress] вызывается в Main-потоке (throttled, ~1% шаг).
     * [onDone]/[onError] — тоже в Main-потоке, ровно один раз.
     */
    fun downloadApk(
        context: Context,
        url: String,
        version: String,
        scope: CoroutineScope,
        onProgress: (Float) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ): Job {
        val target = apkFile(context)
        runCatching { target.delete() }
        return scope.launch(Dispatchers.IO) {
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
                markDownloaded(context, version)
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

    fun installApk(context: Context) {
        val file = apkFile(context)
        if (!file.exists()) return
        val expected = storedSha256(context)
        if (expected.isNotEmpty() && !verifyApkSha256(context, expected)) {
            clearDownload(context)
            android.widget.Toast.makeText(
                context,
                "Хеш APK не совпал — файл удалён. Попробуйте снова.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
