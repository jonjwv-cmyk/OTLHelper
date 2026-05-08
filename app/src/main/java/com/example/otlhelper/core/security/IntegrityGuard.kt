package com.example.otlhelper.core.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Debug
import android.os.Process
import com.example.otlhelper.BuildConfig
import java.security.MessageDigest

/**
 * §TZ-2.3.31 Phase 4c — runtime hardening checks. Вызывается из
 * [com.example.otlhelper.OtlApp.onCreate] один раз при старте процесса.
 *
 * В **release** сборке мгновенно убивает процесс если:
 *  - APK помечен `FLAG_DEBUGGABLE` (злой пересбор с debug-флагом)
 *  - К процессу подключён JDWP-debugger (злой dynamic analysis)
 *  - APK signing cert SHA-256 не совпадает с [BuildConfig.EXPECTED_SIGNING_SHA256]
 *    (если задан — см. build.gradle.kts buildConfigField). Пустая строка =
 *    skip проверку (dev-keystore, CI pre-sign).
 *
 * В **debug** сборке — no-op (ничего не проверяем, кроме лога fingerprint'а
 * в logcat один раз, чтобы было из чего сделать expected hash).
 */
object IntegrityGuard {

    /**
     * Главный вход — вызывается из OtlApp.onCreate ПОСЛЕ Hilt inject,
     * но ДО любых сетевых операций. Если что-то не так — процесс умирает.
     */
    fun enforceOrDie(context: Context) {
        // В debug ничего не enforce'им, только лог fingerprint'а.
        if (BuildConfig.DEBUG) {
            runCatching {
                val fp = computeSigningSha256(context)
                android.util.Log.i("IntegrityGuard", "signing_sha256=$fp")
            }
            return
        }

        // Release path — hard enforcement.
        val ai = context.applicationInfo
        val isDebuggable = (ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) die("debuggable_release")
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) die("debugger_attached")

        val expected = BuildConfig.EXPECTED_SIGNING_SHA256
        if (expected.isNotEmpty()) {
            val actual = runCatching { computeSigningSha256(context) }.getOrNull()
            if (actual == null || !actual.equals(expected, ignoreCase = true)) {
                die("signing_mismatch")
            }
        }
    }

    private fun die(reason: String) {
        android.util.Log.e("IntegrityGuard", "integrity_violation:$reason")
        Process.killProcess(Process.myPid())
        // Hard fallback if killProcess didn't exit.
        kotlin.system.exitProcess(10)
    }

    private fun computeSigningSha256(context: Context): String {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val signatures: Array<Signature> = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            info.signingInfo?.let { si ->
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            } ?: emptyArray()
        } else {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            info.signatures ?: emptyArray()
        }
        if (signatures.isEmpty()) error("no_signatures")
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(signatures[0].toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
