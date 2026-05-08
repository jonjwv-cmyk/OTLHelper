package com.example.otlhelper.desktop.core.security

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * §TZ-2.4.0 — desktop mirror of `app/.../core/security/E2ECrypto.kt`.
 *
 * Cipher suite identical к Android: X25519 ECDH + HKDF-SHA256 + AES-256-GCM.
 * Wire format identical к `server-modular/crypto-envelope.js`. Clients
 * (Android + Desktop) и server обмениваются binary envelope:
 *
 *   [0]        version byte (0x01)
 *   [1..33)    ephemeral client X25519 public key (32 bytes)
 *   [33..45)   AES-GCM nonce (12 bytes)
 *   [45..end)  AES-GCM ciphertext || 16-byte GCM tag
 *
 * AAD = version byte || ephemeralPub.
 *
 * Public key загружается через classloader resource `otl_server_pub.bin`
 * (32 raw bytes). На Android аналогичный файл лежит в `res/raw/`. Один и
 * тот же файл — один key для двух клиентов.
 */
object E2ECrypto {

    private const val VERSION: Byte = 0x01
    private const val PUB_LEN = 32
    private const val NONCE_LEN = 12
    private const val GCM_TAG_BITS = 128
    private val INFO = "otl-e2e-v1".toByteArray(Charsets.US_ASCII)
    private const val RESOURCE_PATH = "otl_server_pub.bin"

    @Volatile
    private var cachedServerPub: ByteArray? = null

    /** Reads server's static X25519 public key from JAR resources. 32 bytes. */
    fun serverPublicKey(): ByteArray {
        cachedServerPub?.let { return it }
        synchronized(this) {
            cachedServerPub?.let { return it }
            val stream = javaClass.classLoader.getResourceAsStream(RESOURCE_PATH)
                ?: error("$RESOURCE_PATH missing in desktop classpath")
            val bytes = stream.use { it.readBytes() }
            require(bytes.size == PUB_LEN) {
                "server pub must be 32 bytes, got ${bytes.size}"
            }
            cachedServerPub = bytes
            return bytes
        }
    }

    /** Шифрует plaintext в envelope; возвращает Session с responseKey для decrypt'а ответа. */
    fun encryptRequest(plaintext: ByteArray, serverPub: ByteArray): Session {
        require(serverPub.size == PUB_LEN) { "serverPub must be 32 bytes" }

        val ephemeralPriv = X25519.generatePrivateKey()
        val ephemeralPub = X25519.publicFromPrivate(ephemeralPriv)
        val sharedSecret = X25519.computeSharedSecret(ephemeralPriv, serverPub)

        val derived = Hkdf.computeHkdf(
            "HmacSha256",
            sharedSecret,
            ByteArray(0),
            INFO,
            64,
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

    /** Расшифровывает server response с тем responseKey что был выведен при encrypt'е request'а. */
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
