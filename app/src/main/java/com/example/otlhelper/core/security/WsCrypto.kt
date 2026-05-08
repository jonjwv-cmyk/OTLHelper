package com.example.otlhelper.core.security

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import okio.ByteString
import okio.ByteString.Companion.toByteString
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * §TZ-2.3.31 Phase 3b — WebSocket frame crypto (X25519+HKDF+AES-256-GCM).
 *
 * Flow:
 *  1. Client generates ephemeral X25519 keypair в [newSession].
 *  2. Client SEND TEXT frame `{"type":"crypto_init","v":1,"epk":"<base64>"}`.
 *  3. Оба стороны выводят 2 session keys через ECDH+HKDF (info="otl-ws-v1"):
 *     derived[0..32)  = c2sKey (client→server AES-256)
 *     derived[32..64) = s2cKey (server→client AES-256)
 *  4. Все последующие frames (binary) AES-GCM с per-direction counter nonce.
 *
 * Wire format per encrypted binary frame:
 *   [0]        version (0x01)
 *   [1..13)    nonce (12 bytes: 4-byte dir tag BE + 8-byte counter BE)
 *   [13..end)  AES-GCM ciphertext || 16-byte tag
 *
 * AAD = [version] — binds protocol version, prevents downgrade.
 */
object WsCrypto {

    private const val VERSION: Byte = 0x01
    private const val PUB_LEN = 32
    private const val NONCE_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val DIR_C2S = 1
    private const val DIR_S2C = 2
    private val INFO = "otl-ws-v1".toByteArray(Charsets.US_ASCII)

    /**
     * Генерит ephemeral X25519, выводит c2s/s2c keys через HKDF.
     * Возвращает [Session] с ephemeralPubBase64 для отправки в crypto_init.
     */
    fun newSession(serverStaticPub: ByteArray): Session {
        require(serverStaticPub.size == PUB_LEN) { "serverPub must be 32 bytes" }

        val ephPriv = X25519.generatePrivateKey()
        val ephPub = X25519.publicFromPrivate(ephPriv)
        val shared = X25519.computeSharedSecret(ephPriv, serverStaticPub)

        val derived = Hkdf.computeHkdf(
            /*macAlgorithm =*/ "HmacSha256",
            /*ikm =*/ shared,
            /*salt =*/ ByteArray(0),
            /*info =*/ INFO,
            /*size =*/ 64,
        )
        val c2s = derived.copyOfRange(0, 32)
        val s2c = derived.copyOfRange(32, 64)

        val epkB64 = android.util.Base64.encodeToString(
            ephPub,
            android.util.Base64.NO_WRAP,
        )
        return Session(
            ephemeralPubBase64 = epkB64,
            c2sKey = c2s,
            s2cKey = s2c,
        )
    }

    /** Шифрует plain JSON string → binary frame (ByteString для OkHttp WS). */
    fun encryptClientFrame(session: Session, plaintext: String): ByteString {
        val counter = session.counterOutNext()
        val nonce = makeNonce(DIR_C2S, counter)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(session.c2sKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(byteArrayOf(VERSION))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val frame = ByteArray(1 + NONCE_LEN + ciphertext.size)
        frame[0] = VERSION
        System.arraycopy(nonce, 0, frame, 1, NONCE_LEN)
        System.arraycopy(ciphertext, 0, frame, 1 + NONCE_LEN, ciphertext.size)
        return frame.toByteString()
    }

    /** Расшифровывает server→client binary frame → plain JSON string. */
    fun decryptServerFrame(session: Session, frame: ByteString): String {
        val bytes = frame.toByteArray()
        require(bytes.size >= 1 + NONCE_LEN + 16) { "ws_frame_too_short" }
        require(bytes[0] == VERSION) { "ws_bad_version" }

        val nonce = bytes.copyOfRange(1, 1 + NONCE_LEN)
        val ciphertext = bytes.copyOfRange(1 + NONCE_LEN, bytes.size)

        // Verify direction — must be server→client
        require(
            nonce[0] == 0.toByte() && nonce[1] == 0.toByte() &&
                nonce[2] == 0.toByte() && nonce[3] == DIR_S2C.toByte()
        ) { "ws_wrong_direction" }

        // Verify monotonic counter (replay protection)
        val counter = readCounter(nonce)
        val expected = session.counterInNext()
        require(counter == expected) { "ws_counter_mismatch exp=$expected got=$counter" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(session.s2cKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(byteArrayOf(VERSION))
        val plain = cipher.doFinal(ciphertext)
        return String(plain, Charsets.UTF_8)
    }

    private fun makeNonce(direction: Int, counter: Long): ByteArray {
        val n = ByteArray(NONCE_LEN)
        n[0] = 0; n[1] = 0; n[2] = 0; n[3] = (direction and 0xff).toByte()
        n[4] = ((counter ushr 56) and 0xff).toByte()
        n[5] = ((counter ushr 48) and 0xff).toByte()
        n[6] = ((counter ushr 40) and 0xff).toByte()
        n[7] = ((counter ushr 32) and 0xff).toByte()
        n[8] = ((counter ushr 24) and 0xff).toByte()
        n[9] = ((counter ushr 16) and 0xff).toByte()
        n[10] = ((counter ushr 8) and 0xff).toByte()
        n[11] = (counter and 0xff).toByte()
        return n
    }

    private fun readCounter(nonce: ByteArray): Long {
        return ((nonce[4].toLong() and 0xff) shl 56) or
            ((nonce[5].toLong() and 0xff) shl 48) or
            ((nonce[6].toLong() and 0xff) shl 40) or
            ((nonce[7].toLong() and 0xff) shl 32) or
            ((nonce[8].toLong() and 0xff) shl 24) or
            ((nonce[9].toLong() and 0xff) shl 16) or
            ((nonce[10].toLong() and 0xff) shl 8) or
            (nonce[11].toLong() and 0xff)
    }

    /**
     * Per-connection state. counter* — strict monotonic, инкрементятся только
     * при успешной операции. Взаимная защита от replay и reorder.
     */
    class Session(
        val ephemeralPubBase64: String,
        val c2sKey: ByteArray,
        val s2cKey: ByteArray,
    ) {
        @Volatile private var counterOut: Long = 0
        @Volatile private var counterIn: Long = 0

        @Synchronized
        fun counterOutNext(): Long = counterOut++

        @Synchronized
        fun counterInNext(): Long = counterIn++
    }
}
