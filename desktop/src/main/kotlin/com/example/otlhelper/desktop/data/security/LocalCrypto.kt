package com.example.otlhelper.desktop.data.security

import com.example.otlhelper.desktop.data.session.SessionStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * §TZ-DESKTOP-DIST 0.5.1 — encryption-at-rest для локальных файлов в
 * `~/.otldhelper/`. Гарантирует что media (gif/photo/video из ленты),
 * inbox/news JSON snapshot, session metadata НЕ хранятся в plain виде —
 * никто с доступом к диску не разберёт что внутри.
 *
 * # Wire format
 *
 *   [0]        version byte (0x01)
 *   [1..13)    nonce (12 bytes, random per-encrypt)
 *   [13..end)  AES-256-GCM ciphertext || 16-byte tag
 *
 * Совместим в плане формата с [com.example.otlhelper.shared.security.BlobCrypto]
 * но другой ключевой material — там per-blob ключ из D1, тут master ключ
 * derived от login + device_id (PBKDF2-HMAC-SHA256, 100k iterations).
 *
 * # Master key
 *
 * Привязан к (login, device_id):
 *   key = PBKDF2(password = login + ":" + device_id, salt = APP_SALT, iter = 100k, len = 32)
 *
 * Это даёт per-user per-machine binding: украденный кэш не расшифруется
 * на другой машине (другой device_id) или другим юзером (другой login).
 *
 * Если SessionStore пустой (юзер не залогинен) — `master()` возвращает null,
 * вызывающий код должен либо отказаться от шифрования (cache disabled), либо
 * использовать device-only fallback (только device_id без login).
 */
object LocalCrypto {

    private const val VERSION: Byte = 0x01
    private const val NONCE_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERS = 100_000

    // §TZ-DESKTOP 0.5.1 — Salt константный (не secret сам по себе).
    // Безопасность держится на (login, device_id), не на salt.
    private val APP_SALT: ByteArray = byteArrayOf(
        0x4F, 0x54, 0x4C, 0x44, 0x2D, 0x41, 0x70, 0x70,
        0x53, 0x61, 0x6C, 0x74, 0x2D, 0x76, 0x31, 0x21,
    )

    private val rng = SecureRandom()

    @Volatile
    private var cachedKey: SecretKey? = null

    @Volatile
    private var cachedKeyPrincipal: String? = null

    /**
     * Master key derived от текущей сессии. Возвращает null если юзер не
     * залогинен (тогда шифрование пропускается; вызывающий код должен это
     * учитывать).
     */
    fun master(): SecretKey? {
        val session = SessionStore.load() ?: return null
        val login = session.login.ifBlank { return null }
        val deviceId = session.deviceId.ifBlank { SessionStore.ensureDeviceId() }
        val principal = "$login:$deviceId"
        cachedKey?.let { if (cachedKeyPrincipal == principal) return it }
        synchronized(this) {
            val again = cachedKey
            if (again != null && cachedKeyPrincipal == principal) return again
            val derived = derive(principal)
            cachedKey = derived
            cachedKeyPrincipal = principal
            return derived
        }
    }

    /** Device-bound fallback (без login) — для случаев когда юзер не залогинен. */
    fun deviceOnly(): SecretKey {
        val deviceId = SessionStore.ensureDeviceId()
        return derive("device:$deviceId")
    }

    /** Сбросить cached master key (на logout — следующий call снова derive'ит). */
    fun invalidate() {
        synchronized(this) {
            cachedKey = null
            cachedKeyPrincipal = null
        }
    }

    /** Encrypt с явно указанным ключом (например deviceOnly() для session.json). */
    fun encryptWith(key: SecretKey, plain: ByteArray): ByteArray = encrypt(plain, key)

    /** Decrypt с явно указанным ключом. */
    fun decryptWith(key: SecretKey, envelope: ByteArray): ByteArray? {
        if (envelope.size < 1 + NONCE_LEN + 16) return null
        if (envelope[0] != VERSION) return null
        return tryDecrypt(envelope, key)
    }

    fun encrypt(plain: ByteArray, key: SecretKey = master() ?: deviceOnly()): ByteArray {
        val nonce = ByteArray(NONCE_LEN).also(rng::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ct = cipher.doFinal(plain)
        val out = ByteArray(1 + NONCE_LEN + ct.size)
        out[0] = VERSION
        System.arraycopy(nonce, 0, out, 1, NONCE_LEN)
        System.arraycopy(ct, 0, out, 1 + NONCE_LEN, ct.size)
        return out
    }

    /**
     * Расшифровка. Если master key не подходит (например изменился device_id
     * после переустановки) и [fallbackToDeviceOnly] = true — пробует device-only.
     */
    fun decrypt(envelope: ByteArray, fallbackToDeviceOnly: Boolean = true): ByteArray? {
        if (envelope.size < 1 + NONCE_LEN + 16) return null
        if (envelope[0] != VERSION) return null
        val key = master() ?: deviceOnly()
        return tryDecrypt(envelope, key)
            ?: if (fallbackToDeviceOnly && master() != null) tryDecrypt(envelope, deviceOnly()) else null
    }

    private fun tryDecrypt(envelope: ByteArray, key: SecretKey): ByteArray? = runCatching {
        val nonce = envelope.copyOfRange(1, 1 + NONCE_LEN)
        val ct = envelope.copyOfRange(1 + NONCE_LEN, envelope.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.doFinal(ct)
    }.getOrNull()

    private fun derive(principal: String): SecretKey {
        val spec = PBEKeySpec(principal.toCharArray(), APP_SALT, PBKDF2_ITERS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }
}
