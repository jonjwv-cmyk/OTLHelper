package com.example.otlhelper.desktop.data.security

import com.example.otlhelper.desktop.data.network.HttpClientFactory
import com.example.otlhelper.shared.security.BlobCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * §TZ-DESKTOP-0.1.0 этап 5 + §TZ-DESKTOP-DIST 0.5.1 — disk-cache для медиа-вложений
 * с шифрованием at-rest.
 *
 * JavaFX Media / VLCJ принимают ТОЛЬКО URI (не bytes), поэтому расшифрованный
 * blob нужно положить во временный файл и отдать file:// URI. Раньше plain
 * bytes хранились в `media-cache/<sha1>.<ext>` — любой с доступом к диску
 * мог посмотреть фото/видео. Теперь:
 *
 *  - В `media-cache/<sha1>.enc` пишем bytes зашифрованные [LocalCrypto]
 *    (AES-256-GCM, master key привязан к login + device_id).
 *  - При [ensureLocal] расшифровываем в `~/.otldhelper/media-tmp/<sha1>.<ext>`
 *    и возвращаем этот файл — VLCJ/JavaFX держат его открытым на время
 *    воспроизведения.
 *  - На [wipeTempCache] (logout / app-exit) удаляем всю tmp-папку, plain
 *    bytes исчезают с диска.
 *
 * Cache всё ещё бесконечный (без TTL) — можно добавить в будущих итерациях.
 */
object AttachmentCache {

    private val baseDir: File = File(System.getProperty("user.home"), ".otldhelper").also { it.mkdirs() }
    private val encDir: File = File(baseDir, "media-cache").also { it.mkdirs() }
    private val tmpDir: File = File(baseDir, "media-tmp").also { it.mkdirs() }

    private val inflight = ConcurrentHashMap<String, Any>()

    /** Возвращает file:// URI для локальной копии (расшифрованной). */
    suspend fun ensureLocal(url: String, extensionHint: String = ""): File? = withContext(Dispatchers.IO) {
        val sha = sha1Hex(url)
        val ext = extensionHint.ensureDotOrEmpty()
        val encFile = File(encDir, "$sha.enc")
        val tmpFile = File(tmpDir, sha + ext)

        // tmp уже есть → готово, возвращаем
        if (tmpFile.exists() && tmpFile.length() > 0) return@withContext tmpFile

        // зашифрованный кэш есть → расшифровать в tmp
        if (encFile.exists() && encFile.length() > 0) {
            val plain = decryptCachedFile(encFile) ?: return@withContext null
            writeAtomic(tmpFile, plain)
            return@withContext tmpFile
        }

        // нет ни tmp ни enc → скачиваем
        val lock = inflight.computeIfAbsent(sha) { Any() }
        synchronized(lock) {
            if (encFile.exists() && encFile.length() > 0) {
                // другой поток успел скачать
                return@synchronized
            }
            runCatching {
                val baseUrl = BlobUrlComposer.stripFragment(url)
                val keys = BlobUrlComposer.parseKeys(url)
                // §TZ-DESKTOP-0.10.0 — на Win+corp-proxy переписываем host на sslip.io
                // (browser-trusted LE cert → нет Касперский interstitial → faster).
                // На Mac/direct → no-op, оригинальный URL остаётся.
                val resolvedUrl = com.example.otlhelper.desktop.core.network
                    .MediaUrlResolver.resolve(baseUrl)

                // §TZ-DESKTOP-0.10.1 — retry once on SSLHandshakeException.
                // Касперский MITM иногда подсовывает cert с chain'ом который
                // даже Windows-ROOT не валидирует на первом handshake. На retry
                // (через ~150ms) уже работает (cache prim'ed?).
                val resp = try {
                    HttpClientFactory.rest.newCall(
                        Request.Builder().url(resolvedUrl).build()
                    ).execute()
                } catch (ssl: javax.net.ssl.SSLHandshakeException) {
                    com.example.otlhelper.desktop.core.network.NetMetricsLogger.event(
                        "AttachmentCache: SSLHandshakeException on $resolvedUrl, retry once after 150ms"
                    )
                    try { Thread.sleep(150) } catch (_: InterruptedException) {}
                    HttpClientFactory.rest.newCall(
                        Request.Builder().url(resolvedUrl).build()
                    ).execute()
                }
                resp.use {
                    if (!it.isSuccessful) return@runCatching
                    val raw = it.body?.bytes() ?: return@runCatching
                    val plain = if (keys != null) {
                        BlobCrypto.decrypt(raw, keys.first, keys.second)
                    } else raw
                    val sealed = LocalCrypto.encrypt(plain)
                    writeAtomic(encFile, sealed)
                }
            }.onFailure {
                inflight.remove(sha)
            }
        }
        inflight.remove(sha)

        if (!encFile.exists() || encFile.length() == 0L) return@withContext null

        // распаковка enc → tmp для consumer'а (VLCJ/JavaFX)
        val plain = decryptCachedFile(encFile) ?: return@withContext null
        writeAtomic(tmpFile, plain)
        tmpFile
    }

    /**
     * Удалить ВСЕ расшифрованные temp-файлы. Вызывать при logout, app-exit,
     * Player.dispose() — чтобы не оставлять plain bytes на диске после того
     * как они больше не нужны.
     */
    fun wipeTempCache() {
        runCatching {
            tmpDir.listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * Полная очистка media-cache — и шифрованного, и временного. На
     * logout — после того как юзер сменился, ключи разные → старые `.enc`
     * не расшифруются. Лучше удалить.
     */
    fun wipeAll() {
        runCatching { encDir.listFiles()?.forEach { it.delete() } }
        runCatching { tmpDir.listFiles()?.forEach { it.delete() } }
    }

    private fun decryptCachedFile(encFile: File): ByteArray? = runCatching {
        val sealed = encFile.readBytes()
        LocalCrypto.decrypt(sealed)
    }.getOrNull()

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val partial = File(target.parentFile, target.name + ".part")
        partial.writeBytes(bytes)
        if (!partial.renameTo(target)) {
            // Windows: target может быть busy у другого процесса (VLCJ держит
            // открытым). Перезаписываем содержимое и удаляем partial.
            target.writeBytes(bytes)
            partial.delete()
        }
    }

    private fun sha1Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun String.ensureDotOrEmpty(): String =
        if (isBlank()) "" else if (startsWith(".")) this else ".$this"
}
