package com.example.otlhelper.core.security

/**
 * §TZ-2.3.28 — helper для кодирования/декодирования blob decryption-ключей
 * в URL fragment. Fragment (`#...`) не отправляется HTTP-клиентом на сервер,
 * поэтому ключи живут только внутри клиента и НЕ попадают в access логи.
 *
 * Формат:
 *   https://avatars.otlhelper.com/avatars/user123/123.bin?v=456#k=BASE64&n=BASE64
 *
 * При fetch'е Coil/ExoPlayer custom-fetcher парсит fragment, скачивает
 * baseUrl (без fragment), расшифровывает bytes с `k` и `n`.
 *
 * JSON-responses с сервера приходят с отдельными полями `avatar_url`,
 * `avatar_blob_key_b64`, `avatar_blob_nonce_b64` — клиент композитит
 * финальный URL через [compose] прямо перед передачей в UI.
 */
object BlobUrlComposer {

    private const val KEY_PARAM = "k"
    private const val NONCE_PARAM = "n"

    /**
     * Добавляет blob key+nonce в URL fragment, если оба заданы.
     * Если url пуст — возвращает "". Если key/nonce null/blank —
     * возвращает url без изменений (legacy plain file).
     */
    fun compose(url: String?, keyB64: String?, nonceB64: String?): String {
        val base = url?.trim().orEmpty()
        if (base.isEmpty()) return ""
        if (keyB64.isNullOrBlank() || nonceB64.isNullOrBlank()) return base
        val separator = if (base.contains('#')) '&' else '#'
        return "$base$separator$KEY_PARAM=$keyB64&$NONCE_PARAM=$nonceB64"
    }

    /** true если URL содержит blob fragment (надо расшифровывать перед декодированием). */
    fun isEncrypted(url: String): Boolean {
        val frag = extractFragment(url) ?: return false
        return frag.contains("$KEY_PARAM=") && frag.contains("$NONCE_PARAM=")
    }

    /** Возвращает URL без fragment — то что реально отправляется на сервер. */
    fun stripFragment(url: String): String {
        val hash = url.indexOf('#')
        return if (hash >= 0) url.substring(0, hash) else url
    }

    /**
     * Парсит blob key+nonce из URL fragment. Null если отсутствуют или
     * фрагмент невалидный.
     */
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
            val key = android.util.Base64.decode(keyB64, android.util.Base64.DEFAULT)
            val nonce = android.util.Base64.decode(nonceB64, android.util.Base64.DEFAULT)
            key to nonce
        } catch (_: Throwable) { null }
    }

    private fun extractFragment(url: String): String? {
        val hash = url.indexOf('#').takeIf { it >= 0 } ?: return null
        return url.substring(hash + 1).ifBlank { null }
    }
}
