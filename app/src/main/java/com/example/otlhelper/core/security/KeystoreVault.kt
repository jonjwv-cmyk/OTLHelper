package com.example.otlhelper.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * §TZ-2.3.35 Phase 4b — Android Keystore-backed AES-256-GCM vault.
 *
 * Ключ генерируется один раз и хранится в Android Keystore (non-exportable).
 * На устройствах с StrongBox (API 28+, Pixel 3+/Samsung S10+) — hardware-backed;
 * если StrongBox недоступен — TEE-backed (всё равно защищён от root extraction).
 *
 * Используется для шифрования чувствительных значений в SharedPreferences
 * (bearer-токен, пароль). Даже если атакующий снимет образ `/data/data/<pkg>/` —
 * prefs содержат encrypted-base64, ключ из Keystore не доступен.
 *
 * Wire format для [encrypt] / [decrypt]:
 *   base64( [12-byte IV] || AES-GCM(plaintext) || [16-byte tag] )
 *
 * Ключ лениво создаётся при первом обращении. Если Keystore недоступен
 * (старая ROM / повреждённый KeyStore) — throwable ловится вызывающим,
 * fallback на raw-prefs (backward compat с уже залогиненными юзерами).
 */
object KeystoreVault {

    private const val KEY_ALIAS = "otl_session_aes_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        fun buildSpec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                b.setIsStrongBoxBacked(true)
            }
            return b.build()
        }

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                kg.init(buildSpec(strongBox = true))
                return kg.generateKey()
            } catch (_: Throwable) {
                // StrongBox недоступен (эмулятор, бюджетный OEM) — fallback на TEE.
            }
        }
        kg.init(buildSpec(strongBox = false))
        return kg.generateKey()
    }

    /** Возвращает base64(IV||ciphertext||tag). null при ошибке (fallback на plain). */
    fun encrypt(plaintext: String): String? {
        if (plaintext.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(ct, 0, out, iv.size, ct.size)
            Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (_: Throwable) { null }
    }

    /** Расшифровывает base64-encrypted. null если ключ потерян или данные повреждены. */
    fun decrypt(encoded: String): String? {
        if (encoded.isEmpty()) return ""
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > IV_LEN)
            val iv = bytes.copyOfRange(0, IV_LEN)
            val ct = bytes.copyOfRange(IV_LEN, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Throwable) { null }
    }

    /** Удаляет ключ (например, при смене пользователя или debug-reset). */
    fun clear() {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            ks.deleteEntry(KEY_ALIAS)
        } catch (_: Throwable) {}
    }
}
