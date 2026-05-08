package com.example.otlhelper.desktop.data.security

import org.json.JSONObject

/**
 * §TZ-DESKTOP-0.1.0 этап 5 — desktop-порт [app/.../BlobAwareUrl.kt].
 *
 * Читает URL-поле + соседние `_blob_key_b64` / `_blob_nonce_b64` поля и
 * композитит URL с fragment `#k=...&n=...` (если blob encrypted) или plain.
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
