package com.example.otlhelper

import android.content.Context
import com.example.otlhelper.domain.features.Features
import com.example.otlhelper.domain.features.FeaturesParser
import com.example.otlhelper.domain.model.Role
import org.json.JSONObject

class SessionManager(context: Context) {

    private companion object {
        const val PREFS_NAME = "otl_session"
        const val KEY_IS_LOGGED_IN = "is_logged_in"
        const val KEY_LOGIN = "login"
        const val KEY_FULL_NAME = "full_name"
        const val KEY_ROLE = "role"
        // §TZ-2.3.36 — KEY_PASSWORD + KEY_PASSWORD_ENC УДАЛЕНЫ. Пароль
        // больше не персистится на устройстве: хранится только в RAM на
        // время login-вызова. Уменьшает blast radius при компрометации
        // /data/data/, removes long-lived secret surface.
        const val KEY_TOKEN = "token"
        const val KEY_TOKEN_ENC = "token_enc"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_MUST_CHANGE_PASSWORD = "must_change_password"
        const val KEY_AVATAR_URL = "avatar_url"
        const val KEY_FEATURES_JSON = "features_json"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * §TZ-2.3.35 Phase 4b — Android Keystore-backed encrypt для токена и пароля.
     * Wire: prefs хранят KEY_TOKEN_ENC (encrypted via hardware key). При
     * миграции со старых версий: если читается KEY_TOKEN (plain) — re-encrypt
     * и удаляем plain. Если Keystore отваливается — fallback на plain
     * (не блокируем работу юзера).
     */
    private fun writeSecret(keyEnc: String, keyPlain: String, value: String) {
        val enc = com.example.otlhelper.core.security.KeystoreVault.encrypt(value)
        val editor = prefs.edit()
        if (enc != null) {
            editor.putString(keyEnc, enc)
            editor.remove(keyPlain)
        } else {
            // Keystore недоступен — сохраняем plain (backward compat).
            editor.putString(keyPlain, value)
            editor.remove(keyEnc)
        }
        editor.apply()
    }

    private fun readSecret(keyEnc: String, keyPlain: String): String {
        // 1. Пробуем зашифрованный.
        val enc = prefs.getString(keyEnc, "") ?: ""
        if (enc.isNotEmpty()) {
            com.example.otlhelper.core.security.KeystoreVault.decrypt(enc)?.let { return it }
        }
        // 2. Legacy plain — мигрируем на лету.
        val plain = prefs.getString(keyPlain, "") ?: ""
        if (plain.isNotEmpty()) {
            val newEnc = com.example.otlhelper.core.security.KeystoreVault.encrypt(plain)
            if (newEnc != null) {
                prefs.edit().putString(keyEnc, newEnc).remove(keyPlain).apply()
            }
            return plain
        }
        return ""
    }

    fun saveUser(
        login: String,
        fullName: String,
        role: String,
    ) {
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_LOGIN, login)
            .putString(KEY_FULL_NAME, fullName)
            .putString(KEY_ROLE, role)
            .apply()
    }

    fun isLoggedIn(): Boolean {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false)
    }

    fun getLogin(): String {
        return prefs.getString(KEY_LOGIN, "") ?: ""
    }

    fun getFullName(): String {
        return prefs.getString(KEY_FULL_NAME, "") ?: ""
    }

    fun getRole(): String {
        return prefs.getString(KEY_ROLE, "") ?: ""
    }

    /**
     * Возвращает [Role] enum — канонизированная роль для использования с [com.example.otlhelper.domain.permissions.Permissions].
     * Строку `superadmin` со стороны сервера парсит в [Role.Developer] (backward compat).
     */
    fun getRoleEnum(): Role = Role.fromString(getRole())

    /**
     * §TZ-2.3.36 — пароль НЕ персистится. Сохранён для backward-compat API
     * (в коде могут быть старые вызовы). Всегда возвращает "" — вызывающий
     * должен получать пароль от пользователя через UI login-flow.
     */
    @Deprecated("Password is no longer persisted. Prompt user via login UI.")
    fun getPassword(): String {
        // Migration: очищаем legacy plain + encrypted password если остались
        // с прежних версий.
        if (prefs.contains("password") || prefs.contains("password_enc")) {
            prefs.edit().remove("password").remove("password_enc").apply()
        }
        return ""
    }

    fun clearSession() {
        prefs.edit().clear().apply()
        com.example.otlhelper.core.security.KeystoreVault.clear()
    }

    fun logout() {
        clearSession()
    }

    fun saveToken(token: String, expiresAt: String = "") {
        prefs.edit().putString(KEY_EXPIRES_AT, expiresAt).apply()
        writeSecret(KEY_TOKEN_ENC, KEY_TOKEN, token)
    }

    fun getToken(): String = readSecret(KEY_TOKEN_ENC, KEY_TOKEN)

    fun hasToken(): Boolean = getToken().isNotBlank()

    fun setMustChangePassword(value: Boolean) {
        prefs.edit().putBoolean(KEY_MUST_CHANGE_PASSWORD, value).apply()
    }

    fun mustChangePassword(): Boolean = prefs.getBoolean(KEY_MUST_CHANGE_PASSWORD, false)

    fun getAvatarUrl(): String = prefs.getString(KEY_AVATAR_URL, "") ?: ""

    fun saveAvatarUrl(url: String) {
        prefs.edit().putString(KEY_AVATAR_URL, url).apply()
    }

    // ── Feature flags (§3.15.a.Е) ────────────────────────────────────────────
    /**
     * Текущие feature-flags. Обновляются при каждом login/me; между сеансами —
     * закэшированы в SharedPreferences чтобы UI работал и в оффлайне по
     * последнему известному снимку (§3.15.a.Е «кэшируются локально на случай оффлайна»).
     */
    fun getFeatures(): Features {
        val raw = prefs.getString(KEY_FEATURES_JSON, "") ?: ""
        if (raw.isBlank()) return Features.DEFAULT
        return try {
            FeaturesParser.parse(JSONObject(raw))
        } catch (e: Exception) {
            // Кеш features повреждён — feature-флаги откатятся в DEFAULT
            // и фичи могут отвалиться молча (ws off, и т.д.). Логируем,
            // чтобы видеть если это массово.
            android.util.Log.w("Session", "features cache corrupted, falling back to DEFAULT", e)
            Features.DEFAULT
        }
    }

    fun saveFeatures(json: JSONObject?) {
        val features = FeaturesParser.parse(json)
        prefs.edit()
            .putString(KEY_FEATURES_JSON, FeaturesParser.serialize(features).toString())
            .apply()
    }
}