package com.example.otlhelper.core.security

import android.content.Context
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * §TZ-2.3.25 — E2E envelope encryption для API запросов.
 *
 * Cipher suite: X25519 ECDH + HKDF-SHA256 + AES-256-GCM.
 * Wire format идентичен `server-modular/crypto-envelope.js`.
 *
 * # Request
 *
 * Каждый запрос:
 *  1. Генерим ephemeral X25519 keypair (~0.5мс на современных ARM).
 *  2. ECDH(ephemeralPriv, serverPub) → shared secret (32 байта).
 *  3. HKDF-SHA256(shared, salt="", info="otl-e2e-v1", 64 байта) →
 *     первые 32 байта = requestKey, следующие 32 = responseKey.
 *  4. Random AES-GCM nonce (12 байт).
 *  5. AAD = [version byte 0x01] || ephemeralPub.
 *  6. Encrypt plaintext (JSON) с requestKey → ciphertext+tag.
 *  7. Envelope: version || ephemeralPub || nonce || ciphertext.
 *
 * # Response
 *
 * Сервер шифрует ответ с responseKey (тот же HKDF) и своим random
 * nonce. Клиент расшифровывает тем же responseKey (symmetrically derived).
 *
 * # Почему не Tink HybridEncrypt
 *
 * Tink высокоуровневый `HybridEncrypt` ping-ponged stateless один-в-одну
 * сторону, не даёт shared context для симметричного ответа. Нам нужен
 * bidirectional session → используем subtle primitives напрямую.
 *
 * # Forward secrecy
 *
 * Partial: каждый запрос новый ephemeral → если server private key утечёт
 * завтра, **сохранённые VPS'ом** ciphertext'ы прошлого станут расшифровываемы.
 * Но мы VPS настроили не логировать body. Реальный риск минимален.
 */
object E2ECrypto {

    private const val VERSION: Byte = 0x01
    private const val PUB_LEN = 32
    private const val NONCE_LEN = 12
    private const val GCM_TAG_BITS = 128
    private val INFO = "otl-e2e-v1".toByteArray(Charsets.US_ASCII)

    @Volatile
    private var cachedServerPub: ByteArray? = null

    /** Загружает сервер-паблик ключ из res/raw/otl_server_pub.bin (32 байта). */
    fun serverPublicKey(context: Context): ByteArray {
        cachedServerPub?.let { return it }
        synchronized(this) {
            cachedServerPub?.let { return it }
            val resId = context.resources.getIdentifier(
                "otl_server_pub", "raw", context.packageName,
            )
            require(resId != 0) { "otl_server_pub.bin missing in res/raw" }
            val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
            require(bytes.size == PUB_LEN) { "server pub must be 32 bytes, got ${bytes.size}" }
            cachedServerPub = bytes
            return bytes
        }
    }

    /**
     * Шифрует plaintext в envelope готовый для отправки на сервер.
     * Возвращает [Session] — содержит envelope bytes + derivedResponseKey
     * + ephemeralPub для последующей расшифровки ответа.
     */
    fun encryptRequest(plaintext: ByteArray, serverPub: ByteArray): Session {
        require(serverPub.size == PUB_LEN) { "serverPub must be 32 bytes" }

        val ephemeralPriv = X25519.generatePrivateKey()
        val ephemeralPub = X25519.publicFromPrivate(ephemeralPriv)
        val sharedSecret = X25519.computeSharedSecret(ephemeralPriv, serverPub)

        val derived = Hkdf.computeHkdf(
            /*macAlgorithm =*/ "HmacSha256",
            /*ikm =*/ sharedSecret,
            /*salt =*/ ByteArray(0),
            /*info =*/ INFO,
            /*size =*/ 64,
        )
        val requestKey = derived.copyOfRange(0, 32)
        val responseKey = derived.copyOfRange(32, 64)

        val aad = ByteArray(1 + PUB_LEN)
        aad[0] = VERSION
        System.arraycopy(ephemeralPub, 0, aad, 1, PUB_LEN)

        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(requestKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)

        val envelope = ByteArray(1 + PUB_LEN + NONCE_LEN + ciphertext.size)
        envelope[0] = VERSION
        System.arraycopy(ephemeralPub, 0, envelope, 1, PUB_LEN)
        System.arraycopy(nonce, 0, envelope, 1 + PUB_LEN, NONCE_LEN)
        System.arraycopy(ciphertext, 0, envelope, 1 + PUB_LEN + NONCE_LEN, ciphertext.size)

        return Session(envelope = envelope, responseKey = responseKey, ephemeralPub = ephemeralPub)
    }

    /** Расшифровывает server response envelope с сессионным responseKey. */
    fun decryptResponse(envelope: ByteArray, responseKey: ByteArray, ephemeralPub: ByteArray): ByteArray {
        require(envelope.size >= 1 + NONCE_LEN + 16) { "response envelope too short" }
        require(envelope[0] == VERSION) { "bad response version" }

        val nonce = envelope.copyOfRange(1, 1 + NONCE_LEN)
        val ciphertext = envelope.copyOfRange(1 + NONCE_LEN, envelope.size)

        val aad = ByteArray(1 + PUB_LEN)
        aad[0] = VERSION
        System.arraycopy(ephemeralPub, 0, aad, 1, PUB_LEN)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(responseKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    data class Session(
        val envelope: ByteArray,
        val responseKey: ByteArray,
        val ephemeralPub: ByteArray,
    )
}
