package com.example.otlhelper.core.security

import android.util.Log
import com.example.otlhelper.shared.security.BlobCrypto
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/**
 * §TZ-2.3.31/32/33/34 — Custom Coil 3 Fetcher для всех *.otlhelper.com URL'ов.
 *
 * Покрывает:
 *  - **Plain URL'ы** — используем наш OkHttpClient с DNS override на VPS
 *    (`imageClient()`). Без этого Coil's auto-detect NetworkFetcher использовал
 *    «чужой» OkHttpClient → silent fail на plain-аватарах/медиа.
 *  - **Encrypted URL'ы** (`#k=&n=` фрагмент) — после fetch'а AES-256-GCM decrypt.
 *
 * **Disk cache integration (§TZ-2.3.34):** Coil 3's EngineInterceptor пишет в
 * disk cache ТОЛЬКО если Fetcher возвращает `FileImageSource` с diskCacheKey.
 * Наш кастомный Fetcher раньше возвращал SourceImageSource(Buffer) → Coil не
 * кешировал → каждый recompose = новый fetch. Теперь пишем байты на файл
 * через `imageLoader.diskCache.openEditor`, возвращаем FileImageSource.
 * Повторные загрузки того же URL = disk cache hit, 0 network, instant.
 */
class EncBlobFetcher(
    private val url: String,
    private val okHttpClient: OkHttpClient,
    private val options: Options,
    private val imageLoader: ImageLoader,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val baseUrl = BlobUrlComposer.stripFragment(url)
        val keys = BlobUrlComposer.parseKeys(url)
        val diskCacheKey = url

        // ── 1. Disk cache hit? ──────────────────────────────────────
        val diskCache = imageLoader.diskCache
        if (diskCache != null) {
            val snapshot = diskCache.openSnapshot(diskCacheKey)
            if (snapshot != null) {
                return SourceFetchResult(
                    source = ImageSource(
                        file = snapshot.data,
                        fileSystem = diskCache.fileSystem,
                        diskCacheKey = diskCacheKey,
                        closeable = snapshot,
                    ),
                    mimeType = null,
                    dataSource = DataSource.DISK,
                )
            }
        }

        // ── 2. Fetch through network ─────────────────────────────────
        val response = okHttpClient.newCall(Request.Builder().url(baseUrl).build()).execute()

        response.body?.use { body ->
            if (!response.isSuccessful) {
                throw java.io.IOException("blob HTTP ${response.code}")
            }
            val raw = body.bytes()
            val plain = if (keys != null) {
                val (key, nonce) = keys
                try {
                    BlobCrypto.decrypt(raw, key, nonce)
                } catch (e: Throwable) {
                    // §TZ-2.3.36 log hygiene: не логируем URL (содержит opaque_id),
                    // только тип ошибки и размер — достаточно для forensics.
                    Log.e(TAG, "decrypt_fail len=${raw.size} err=${e.javaClass.simpleName}")
                    throw e
                }
            } else {
                raw
            }

            // ── 3. Write to disk cache ─────────────────────────────
            if (diskCache != null) {
                val editor = diskCache.openEditor(diskCacheKey)
                if (editor != null) {
                    try {
                        diskCache.fileSystem.write(editor.data) { write(plain) }
                        val snap = editor.commitAndOpenSnapshot()
                        if (snap != null) {
                            return SourceFetchResult(
                                source = ImageSource(
                                    file = snap.data,
                                    fileSystem = diskCache.fileSystem,
                                    diskCacheKey = diskCacheKey,
                                    closeable = snap,
                                ),
                                mimeType = response.header("Content-Type"),
                                dataSource = DataSource.NETWORK,
                            )
                        }
                    } catch (_: Throwable) {
                        try { editor.abort() } catch (_: Throwable) {}
                    }
                }
            }

            // ── 4. Fallback (no disk cache) — in-memory buffer ──────
            return SourceFetchResult(
                source = ImageSource(
                    source = Buffer().write(plain),
                    fileSystem = options.fileSystem,
                ),
                mimeType = response.header("Content-Type"),
                dataSource = DataSource.NETWORK,
            )
        } ?: throw java.io.IOException("empty body")
    }

    class Factory(
        private val okHttpClientProvider: () -> OkHttpClient,
    ) : Fetcher.Factory<Uri> {
        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            val asString = data.toString()
            if (!asString.startsWith("http://", ignoreCase = true) &&
                !asString.startsWith("https://", ignoreCase = true)
            ) return null
            if (!asString.contains("otlhelper.com", ignoreCase = true)) return null
            return EncBlobFetcher(asString, okHttpClientProvider(), options, imageLoader)
        }
    }

    private companion object {
        const val TAG = "EncBlob"
    }
}
