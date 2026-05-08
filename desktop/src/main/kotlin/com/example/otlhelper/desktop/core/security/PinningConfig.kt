package com.example.otlhelper.desktop.core.security

/**
 * §TZ-2.4.0 — desktop mirror of `app/.../core/security/PinningConfig.kt`.
 *
 * Pinning на VPS self-signed cert SPKI (не CF). См. подробности и команду
 * снятия пинов в Android-версии. Один и тот же cert файл `otl_vps_cert.pem`
 * на обоих клиентах → одинаковый SPKI.
 */
object PinningConfig {

    const val HOST: String = "api.otlhelper.com"

    /** SHA-256 over SPKI (X.509 pubkey DER) текущего VPS self-signed cert. */
    const val PRIMARY_PIN: String = "IvrWDtD7Arjrtu/gI0J68V+RAuuHxU3BHXiet00E5w8="

    const val BACKUP_PIN: String = "IvrWDtD7Arjrtu/gI0J68V+RAuuHxU3BHXiet00E5w8="

    fun enabledForHost(host: String): Boolean {
        if (host != HOST) return false
        if (PRIMARY_PIN.startsWith("<FILL_ME")) return false
        return true
    }

    fun pinsForHost(host: String): List<String> {
        if (!enabledForHost(host)) return emptyList()
        return if (PRIMARY_PIN == BACKUP_PIN) listOf(PRIMARY_PIN) else listOf(PRIMARY_PIN, BACKUP_PIN)
    }
}
