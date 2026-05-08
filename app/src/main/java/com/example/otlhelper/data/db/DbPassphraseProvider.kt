package com.example.otlhelper.data.db

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Generates a random 32-byte passphrase for SQLCipher and protects it using
 * Android Keystore (AES-256-GCM). The raw passphrase bytes are never stored
 * directly — only the AES-GCM ciphertext + IV are persisted in SharedPreferences.
 *
 * Why not key.encoded: Keystore-backed SecretKey instances are opaque; getEncoded()
 * always returns null, which would cause a NullPointerException.
 */
object DbPassphraseProvider {

    private const val KEY_ALIAS   = "otl_db_key_v2"
    private const val PREFS_NAME  = "otl_db_prefs"
    private const val PREF_ENC    = "db_pass_enc"
    private const val PREF_IV     = "db_pass_iv"

    fun getPassphrase(context: Context): CharArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encB64 = prefs.getString(PREF_ENC, null)
        val ivB64  = prefs.getString(PREF_IV,  null)

        if (encB64 != null && ivB64 != null) {
            val encBytes = Base64.decode(encB64, Base64.NO_WRAP)
            val iv       = Base64.decode(ivB64,  Base64.NO_WRAP)
            val plainBytes = decrypt(encBytes, iv)
            return Base64.encodeToString(plainBytes, Base64.NO_WRAP).toCharArray()
        }

        // First launch: generate a random 32-byte passphrase and encrypt it
        val rawPassphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val (cipherBytes, iv) = encrypt(rawPassphrase)

        prefs.edit()
            .putString(PREF_ENC, Base64.encodeToString(cipherBytes, Base64.NO_WRAP))
            .putString(PREF_IV,  Base64.encodeToString(iv,          Base64.NO_WRAP))
            .apply()

        return Base64.encodeToString(rawPassphrase, Base64.NO_WRAP).toCharArray()
    }

    // ── Keystore helpers ────────────────────────────────────────────────────

    private fun encrypt(data: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.doFinal(data) to cipher.iv
    }

    private fun decrypt(data: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            // Existing installs: возвращаем уже созданный ключ как есть —
            // смена spec не применяется к существующим aliases. Backward-compat.
            return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        // §TZ-2.3.36 — StrongBox где доступен (Pixel 3+, Samsung flagships).
        // Если устройство не поддерживает StrongBox (эмулятор, старые Pixel,
        // бюджетные OEM) — keyGen.generateKey() бросает StrongBoxUnavailableException
        // (или оборачивает в ProviderException). Ловим ЛЮБОЙ throwable на
        // StrongBox-пути и делаем чистый TEE fallback.
        fun specWithoutStrongBox() = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val specStrongBox = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setIsStrongBoxBacked(true)
                    .build()
                keyGen.init(specStrongBox)
                return keyGen.generateKey()
            } catch (_: Throwable) {
                // Любая ошибка StrongBox — fallback на TEE.
            }
        }
        keyGen.init(specWithoutStrongBox())
        return keyGen.generateKey()
    }
}
