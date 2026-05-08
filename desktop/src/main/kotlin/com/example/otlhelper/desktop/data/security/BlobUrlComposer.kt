package com.example.otlhelper.desktop.data.security

import java.util.Base64

/**
 * §TZ-DESKTOP-0.1.0 этап 5 — desktop-порт [app/.../BlobUrlComposer.kt].
 *
 * Формат: `https://.../file.bin#k=BASE64&n=BASE64`. Fragment не шлётся на сервер.
 */
object BlobUrlComposer {

    private const val KEY_PARAM = "k"
    private const val NONCE_PARAM = "n"

    fun compose(url: String?, keyB64: String?, nonceB64: String?): String {
        val base = url?.trim().orEmpty()
        if (base.isEmpty()) return ""
        if (keyB64.isNullOrBlank() || nonceB64.isNullOrBlank()) return base
        val separator = if (base.contains('#')) '&' else '#'
        return "$base$separator$KEY_PARAM=$keyB64&$NONCE_PARAM=$nonceB64"
    }

    fun isEncrypted(url: String): Boolean {
        val frag = extractFragment(url) ?: return false
        return frag.contains("$KEY_PARAM=") && frag.contains("$NONCE_PARAM=")
    }

    fun stripFragment(url: String): String {
        val hash = url.indexOf('#')
        return if (hash >= 0) url.substring(0, hash) else url
    }

    fun parseKeys(url: String): Pair<ByteArray, ByteArray>? {
        val frag = extractFragment(url) ?: return null
        val params = frag.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) null
            else pair.substring(0, eq) to pair.substring(eq + 1)
        }.toMap()
        val keyB64 = params[KEY_PARAM] ?: return null
        val nonceB64 = params[NONCE_PARAM] ?: return null
        return try {
            val key = Base64.getDecoder().decode(keyB64)
            val nonce = Base64.getDecoder().decode(nonceB64)
            key to nonce
        } catch (_: Throwable) { null }
    }

    private fun extractFragment(url: String): String? {
        val hash = url.indexOf('#').takeIf { it >= 0 } ?: return null
        return url.substring(hash + 1).ifBlank { null }
    }
}
