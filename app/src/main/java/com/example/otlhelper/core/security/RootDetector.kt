package com.example.otlhelper.core.security

import android.os.Build
import java.io.File

/**
 * Облегчённое обнаружение root-прав / эмулятора.
 *
 * ⚠️ Это **сигнал, не защита**. Продвинутый злоумышленник обходит любые
 * такие проверки. Используется только для:
 *   - Телеметрии: «сколько юзеров с rooted устройством?»
 *   - В будущем — как вход в policy (Play Integrity даёт более надёжный
 *     verdict, а здесь мы только видим очевидные случаи).
 *
 * Не блокируем работу приложения при root — блокировка дала бы ложные
 * срабатывания на тестовых устройствах разработчиков.
 *
 * Реализация — проверка:
 *  1. Наличия `su` бинарника в стандартных путях.
 *  2. Наличия Magisk / SuperSU пакетов (косвенно через файлы).
 *  3. Явных emulator-signatures в [Build] fields.
 *
 * Вызывается один раз на старте и отправляется в телеметрию если найдено.
 */
object RootDetector {

    /**
     * True если на устройстве есть явные признаки root-прав.
     * False НЕ гарантирует что устройство не рутовано — только что
     * простые проверки не сработали.
     */
    fun likelyRooted(): Boolean = runCatching {
        SU_PATHS.any { File(it).exists() } ||
            MAGISK_PATHS.any { File(it).exists() } ||
            // Build fingerprint у test-keys (раньше — rooted ROMs, сейчас чаще emulator)
            Build.TAGS?.contains("test-keys") == true
    }.getOrDefault(false)

    /** True если устройство — эмулятор (signature-based detection). */
    fun isEmulator(): Boolean = runCatching {
        val fingerprint = Build.FINGERPRINT.orEmpty().lowercase()
        val model = Build.MODEL.orEmpty().lowercase()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val product = Build.PRODUCT.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        val device = Build.DEVICE.orEmpty().lowercase()
        fingerprint.contains("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("emulator") ||
            model.contains("android sdk") ||
            manufacturer.contains("genymotion") ||
            (brand.startsWith("generic") && device.startsWith("generic")) ||
            product == "sdk_gphone64_arm64" ||
            product.contains("sdk_gphone") ||
            product.contains("google_sdk")
    }.getOrDefault(false)

    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/su",
        "/vendor/bin/su",
        "/system/bin/.ext/.su",
        "/system/etc/init.d/99SuperSUDaemon",
        "/dev/com.koushikdutta.superuser.daemon/",
    )

    private val MAGISK_PATHS = listOf(
        "/sbin/magisk",
        "/data/adb/magisk",
        "/data/adb/modules",
        "/data/magisk",
    )
}
