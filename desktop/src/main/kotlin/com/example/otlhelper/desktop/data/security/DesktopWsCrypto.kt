package com.example.otlhelper.desktop.data.security

import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object DesktopWsCrypto {
    private const val VERSION: Byte = 0x01
    private const val KEY_LEN = 32
    private const val NONCE_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val DIR_C2S = 1
    private const val DIR_S2C = 2
    private val INFO = "otl-ws-v1".toByteArray(Charsets.US_ASCII)

    // Same server X25519 public key as Android res/raw/otl_server_pub.bin.
    private const val SERVER_PUBLIC_KEY_B64 = "xFMr7ZEeCpyPWsBNALdzx16EGZR8GIGa0ttmjwVUL1w="

    fun newSession(): Session {
        val keyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val serverPublic = KeyFactory.getInstance("XDH").generatePublic(
            XECPublicKeySpec(
                NamedParameterSpec.X25519,
                littleEndianToBigInteger(java.util.Base64.getDecoder().decode(SERVER_PUBLIC_KEY_B64)),
            ),
        )

        val agreement = KeyAgreement.getInstance("X25519")
        agreement.init(keyPair.private)
        agreement.doPhase(serverPublic, true)
        val shared = agreement.generateSecret()
        val derived = hkdfSha256(shared, ByteArray(0), INFO, KEY_LEN * 2)

        val publicRaw = bigIntegerToLittleEndian((keyPair.public as XECPublicKey).u, KEY_LEN)
        return Session(
            ephemeralPubBase64 = java.util.Base64.getEncoder().encodeToString(publicRaw),
            c2sKey = derived.copyOfRange(0, KEY_LEN),
            s2cKey = derived.copyOfRange(KEY_LEN, KEY_LEN * 2),
        )
    }

    fun encryptClientFrame(session: Session, plaintext: String): ByteString {
        val counter = session.counterOutNext()
        val nonce = makeNonce(DIR_C2S, counter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(session.c2sKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(byteArrayOf(VERSION))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val frame = ByteArray(1 + NONCE_LEN + ciphertext.size)
        frame[0] = VERSION
        System.arraycopy(nonce, 0, frame, 1, NONCE_LEN)
        System.arraycopy(ciphertext, 0, frame, 1 + NONCE_LEN, ciphertext.size)
        return frame.toByteString()
    }

    fun decryptServerFrame(session: Session, frame: ByteString): String {
        val bytes = frame.toByteArray()
        require(bytes.size >= 1 + NONCE_LEN + 16) { "ws_frame_too_short" }
        require(bytes[0] == VERSION) { "ws_bad_version" }
        val nonce = bytes.copyOfRange(1, 1 + NONCE_LEN)
        require(nonce[0] == 0.toByte() && nonce[1] == 0.toByte() && nonce[2] == 0.toByte() && nonce[3] == DIR_S2C.toByte()) {
            "ws_wrong_direction"
        }
        val counter = readCounter(nonce)
        val expected = session.counterInNext()
        require(counter == expected) { "ws_counter_mismatch exp=$expected got=$counter" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(session.s2cKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(byteArrayOf(VERSION))
        val plain = cipher.doFinal(bytes.copyOfRange(1 + NONCE_LEN, bytes.size))
        return String(plain, Charsets.UTF_8)
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val out = ByteArray(size)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < size) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val copy = minOf(previous.size, size - offset)
            System.arraycopy(previous, 0, out, offset, copy)
            offset += copy
            counter++
        }
        return out
    }

    private fun makeNonce(direction: Int, counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_LEN)
        nonce[3] = direction.toByte()
        nonce[4] = ((counter ushr 56) and 0xff).toByte()
        nonce[5] = ((counter ushr 48) and 0xff).toByte()
        nonce[6] = ((counter ushr 40) and 0xff).toByte()
        nonce[7] = ((counter ushr 32) and 0xff).toByte()
        nonce[8] = ((counter ushr 24) and 0xff).toByte()
        nonce[9] = ((counter ushr 16) and 0xff).toByte()
        nonce[10] = ((counter ushr 8) and 0xff).toByte()
        nonce[11] = (counter and 0xff).toByte()
        return nonce
    }

    private fun readCounter(nonce: ByteArray): Long =
        ((nonce[4].toLong() and 0xff) shl 56) or
            ((nonce[5].toLong() and 0xff) shl 48) or
            ((nonce[6].toLong() and 0xff) shl 40) or
            ((nonce[7].toLong() and 0xff) shl 32) or
            ((nonce[8].toLong() and 0xff) shl 24) or
            ((nonce[9].toLong() and 0xff) shl 16) or
            ((nonce[10].toLong() and 0xff) shl 8) or
            (nonce[11].toLong() and 0xff)

    private fun littleEndianToBigInteger(raw: ByteArray): BigInteger =
        BigInteger(1, raw.reversedArray())

    private fun bigIntegerToLittleEndian(value: BigInteger, size: Int): ByteArray {
        val bigEndian = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        val out = ByteArray(size)
        val copy = minOf(size, bigEndian.size)
        for (i in 0 until copy) out[i] = bigEndian[bigEndian.size - 1 - i]
        return out
    }

    class Session(
        val ephemeralPubBase64: String,
        val c2sKey: ByteArray,
        val s2cKey: ByteArray,
    ) {
        @Volatile private var counterOut: Long = 0
        @Volatile private var counterIn: Long = 0

        @Synchronized fun counterOutNext(): Long = counterOut++
        @Synchronized fun counterInNext(): Long = counterIn++
    }
}
