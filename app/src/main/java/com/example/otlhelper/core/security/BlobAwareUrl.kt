package com.example.otlhelper.core.security

import org.json.JSONObject

/**
 * §TZ-2.3.28 — JSON→URL helper. Читает URL-поле + соседние blob key/nonce
 * поля из JSON item'а и возвращает композированный URL с fragment
 * (`#k=...&n=...`) если blob encrypted, иначе plain URL.
 *
 * Top-level функция (не extension) — удобнее вызывать fully-qualified
 * без import'а в каждом callsite:
 *   com.example.otlhelper.core.security.blobAwareUrl(item, "avatar_url")
 *
 * Обрабатывает все конвенции сервера:
 *   - `avatar_url` + `avatar_blob_key_b64` + `avatar_blob_nonce_b64`
 *   - `sender_avatar_url` + `sender_avatar_blob_key_b64` + `sender_avatar_blob_nonce_b64`
 *   - `receiver_avatar_url` + `receiver_avatar_blob_key_b64` + `receiver_avatar_blob_nonce_b64`
 *   - `file_url` + `blob_key_b64` + `blob_nonce_b64` (attachments)
 *
 * Для необычных полей (`user_avatar_url` из legacy endpoints) можно явно
 * передать имена key/nonce полей.
 */
fun blobAwareUrl(
    item: JSONObject,
    urlField: String,
    keyField: String = deriveKeyField(urlField),
    nonceField: String = deriveNonceField(urlField),
): String {
    val url = item.optString(urlField, "")
    val k = item.optString(keyField, "").ifBlank { null }
    val n = item.optString(nonceField, "").ifBlank { null }
    return BlobUrlComposer.compose(url, k, n)
}

private fun deriveKeyField(urlField: String): String = when {
    urlField == "file_url" -> "blob_key_b64"
    urlField.endsWith("_url") -> urlField.removeSuffix("_url") + "_blob_key_b64"
    else -> "${urlField}_blob_key_b64"
}

private fun deriveNonceField(urlField: String): String = when {
    urlField == "file_url" -> "blob_nonce_b64"
    urlField.endsWith("_url") -> urlField.removeSuffix("_url") + "_blob_nonce_b64"
    else -> "${urlField}_blob_nonce_b64"
}
