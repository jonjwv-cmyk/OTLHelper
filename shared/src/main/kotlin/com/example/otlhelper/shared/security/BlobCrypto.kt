package com.example.otlhelper.shared.security

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * §TZ-2.3.27 — blob-level AES-256-GCM decryption (совместим с
 * `server-modular/crypto-blob.js`).
 *
 * Wire format в R2:
 *   [0]        version byte (0x01)
 *   [1..13)    nonce (12 bytes)
 *   [13..end)  AES-GCM ciphertext || 16-byte GCM tag
 *
 * Nonce дублируется в D1 `blob_nonce_b64` (sanity check + backward
 * compat если формат поменяется). `key` всегда из D1 — 32 байта per blob.
 *
 * # Scope
 *
 * Используется для:
 *  - Base snapshot (справочник МОЛ) — gzipped JSON, зашифрованный blob'ом.
 *  - Avatars, media attachments, [in future] APK releases.
 *
 * §TZ-CLEANUP-2026-04-26 — вынесено в `:shared` чтобы Android и Desktop
 * читали один и тот же файл (раньше были две идентичные копии).
 */
object BlobCrypto {

    private const val VERSION: Byte = 0x01
    private const val NONCE_LEN = 12
    private const val GCM_TAG_BITS = 128

    /**
     * Расшифровывает blob envelope.
     *
     * @param envelope полный файл из R2 (version + nonce + ciphertext+tag).
     * @param key raw 32 байта (из D1 blob_key_b64, base64-decoded).
     * @param expectedNonce опциональная проверка что nonce в envelope
     *        совпадает с тем что в D1. Null = не проверять.
     */
    fun decrypt(envelope: ByteArray, key: ByteArray, expectedNonce: ByteArray? = null): ByteArray {
        require(envelope.size >= 1 + NONCE_LEN + 16) { "blob envelope too short: ${envelope.size}" }
        require(envelope[0] == VERSION) { "blob bad version: ${envelope[0]}" }
        require(key.size == 32) { "blob key must be 32 bytes, got ${key.size}" }

        val nonce = envelope.copyOfRange(1, 1 + NONCE_LEN)
        if (expectedNonce != null) {
            require(nonce.contentEquals(expectedNonce)) { "nonce mismatch between metadata and envelope" }
        }
        val ciphertext = envelope.copyOfRange(1 + NONCE_LEN, envelope.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        return cipher.doFinal(ciphertext)
    }
}
