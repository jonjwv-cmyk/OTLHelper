package com.example.otlhelper.desktop.data.session

import com.example.otlhelper.desktop.data.security.LocalCrypto
import com.example.otlhelper.desktop.model.Role
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet

/**
 * §TZ-DESKTOP-0.1.0 этап 3 + §TZ-DESKTOP-DIST 0.5.1 — encrypted session
 * metadata в файле под user home.
 *
 * Сам token хранится отдельно через [DesktopTokenStore] — Mac Keychain /
 * Windows DPAPI. В session.bin лежит ВСЕ ОСТАЛЬНОЕ (login, role, expires_at,
 * full_name, avatar_url, device_id ref) — зашифрованное AES-256-GCM ключом
 * привязанным к device_id (PBKDF2). Никто с доступом к диску не разберёт
 * что за юзер логинится в этом приложении.
 *
 * Backward compat: legacy `session.json` plain-text при первом чтении
 * мигрирует в `session.bin` и удаляется.
 *
 * Права 600 (только владелец) на Unix-подобных — минимальная защита для
 * dev-режима (файл в принципе не sensitive т.к. encrypted).
 */
object SessionStore {

    private val baseDir: File by lazy {
        val home = System.getProperty("user.home") ?: "."
        File(home, ".otldhelper").also { it.mkdirs() }
    }
    private val legacyFile: File = File(baseDir, "session.json")
    private val file: File = File(baseDir, "session.bin")

    data class Session(
        val login: String,
        val role: Role,
        val token: String,
        val deviceId: String,
        val expiresAt: String,
        val os: String,
        val fullName: String = "",
        val avatarUrl: String = "",
    )

    fun save(session: Session) {
        val json = JSONObject().apply {
            put("login", session.login)
            put("role", session.role.wireName)
            put("device_id", session.deviceId)
            put("expires_at", session.expiresAt)
            put("os", session.os)
            put("full_name", session.fullName)
            put("avatar_url", session.avatarUrl)
            put("token_store", "os")
        }
        DesktopTokenStore.save(baseDir, session.login, session.token)
        writeEncrypted(json)
    }

    fun load(): Session? {
        // Migration legacy plain-text → encrypted (one-shot).
        if (legacyFile.exists() && !file.exists()) {
            runCatching {
                val json = JSONObject(legacyFile.readText())
                writeEncrypted(json)
                legacyFile.delete()
            }
        }
        if (!file.exists()) return null
        return try {
            val sealed = file.readBytes()
            val plain = LocalCrypto.decryptWith(LocalCrypto.deviceOnly(), sealed)
                ?: return null
            val json = JSONObject(String(plain, Charsets.UTF_8))
            val login = json.optString("login")
            val token = DesktopTokenStore.load(baseDir, login)
                .ifBlank { json.optString("token") }
            if (json.has("token") && token.isNotBlank()) {
                DesktopTokenStore.save(baseDir, login, token)
                json.remove("token")
                json.put("token_store", "os")
                writeEncrypted(json)
            }
            Session(
                login = login,
                role = Role.fromString(json.optString("role")),
                token = token,
                deviceId = json.optString("device_id"),
                expiresAt = json.optString("expires_at"),
                os = json.optString("os"),
                fullName = json.optString("full_name"),
                avatarUrl = json.optString("avatar_url"),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        load()?.let { DesktopTokenStore.clear(baseDir, it.login) }
        runCatching { file.delete() }
        runCatching { legacyFile.delete() }
        runCatching { com.example.otlhelper.desktop.data.cache.DiskCache.clear() }
        // §TZ-DESKTOP-DIST 0.5.1 — на смене юзера новый master key (login+deviceId)
        // не расшифрует старые .enc файлы → нужно их wipe. Иначе мусор копится.
        runCatching { com.example.otlhelper.desktop.data.security.AttachmentCache.wipeAll() }
        // §TZ-DESKTOP-DIST 0.5.1 — invalidate cached master key.
        runCatching { com.example.otlhelper.desktop.data.security.LocalCrypto.invalidate() }
    }

    private fun writeEncrypted(json: JSONObject) {
        val sealed = LocalCrypto.encryptWith(
            LocalCrypto.deviceOnly(),
            json.toString().toByteArray(Charsets.UTF_8),
        )
        file.writeBytes(sealed)
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    /** Стабильный device_id для этого install. Хранится рядом с session. */
    fun ensureDeviceId(): String {
        val f = File(baseDir, "device_id")
        if (f.exists()) {
            val v = f.readText().trim()
            if (v.isNotEmpty()) return v
        }
        val newId = java.util.UUID.randomUUID().toString()
        f.writeText(newId)
        return newId
    }
}
