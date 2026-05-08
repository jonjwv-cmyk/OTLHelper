package com.example.otlhelper.desktop.data.cache

import com.example.otlhelper.desktop.data.security.LocalCrypto
import java.io.File

/**
 * §TZ-DESKTOP-0.1.0 этап 4c+ + §TZ-DESKTOP-DIST 0.5.1 — encrypted дисковый
 * кэш JSON-снимков repo state.
 *
 * Файлы лежат под `~/.otldhelper/cache/{key}.bin` — AES-256-GCM зашифрованные
 * через [LocalCrypto] (master key привязан к login + device_id). Никто с
 * доступом к диску НЕ прочитает что в ленте новостей или какие у юзера чаты.
 *
 * Доступ атомарный (пишем во временный → rename). Используется чтобы после
 * холодного старта показывать последние известные данные мгновенно, а сеть
 * обновляет в фоне — UX как у Android (мгновенный рендер).
 *
 * Кэшируется отдельно:
 *   • inbox.bin   — список последних диалогов (InboxRepository)
 *   • news.bin    — лента новостей + pinned (NewsRepository)
 *
 * Backward compat: legacy `.json` plain-text файлы при первом чтении удаляются
 * (они с прошлой версии без шифрования — мусор в новой схеме).
 */
object DiskCache {

    private val dir: File by lazy {
        val home = System.getProperty("user.home") ?: "."
        File(home, ".otldhelper/cache").also { it.mkdirs() }
    }

    fun read(key: String): String? {
        // Legacy plain-text cleanup — если остался от старой версии, удаляем.
        runCatching { File(dir, "$key.json").takeIf(File::exists)?.delete() }

        val f = File(dir, "$key.bin")
        if (!f.exists()) return null
        return try {
            val sealed = f.readBytes()
            val plain = LocalCrypto.decrypt(sealed) ?: return null
            String(plain, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun write(key: String, jsonText: String) {
        try {
            val sealed = LocalCrypto.encrypt(jsonText.toByteArray(Charsets.UTF_8))
            val tmp = File(dir, "$key.bin.tmp")
            tmp.writeBytes(sealed)
            val target = File(dir, "$key.bin")
            if (!tmp.renameTo(target)) {
                // Rename failed (Windows file busy) — перезапишем и удалим tmp.
                target.writeBytes(sealed)
                tmp.delete()
            }
        } catch (_: Exception) {
            // fail-soft: offline cache — не критично.
        }
    }

    fun clear() {
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) { /* ignore */ }
    }
}
