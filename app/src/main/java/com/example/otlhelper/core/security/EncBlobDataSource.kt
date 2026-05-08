package com.example.otlhelper.core.security

import com.example.otlhelper.shared.security.BlobCrypto
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * §TZ-2.3.28 — ExoPlayer DataSource для encrypted blobs (видео/media).
 *
 * Если URL содержит blob fragment (`#k=...&n=...`) — скачиваем ciphertext
 * через OkHttp (наш VPS DNS override), расшифровываем AES-256-GCM целиком
 * в RAM, stream'им расшифрованные bytes в ExoPlayer через [ByteArrayDataSource].
 *
 * Если URL БЕЗ fragment — делегируем upstream (например `OkHttpDataSource`
 * из Media3) — legacy plain video.
 *
 * # Trade-offs
 *
 * Full-download-then-decrypt вместо streaming-decrypt:
 *  - Для 10МБ видео ~500мс pre-play latency (download + decrypt), потом мгновенно.
 *  - Плюс: AES-GCM integrity tag защищает весь файл целиком (tamper detect).
 *  - Минус: нет seek до загрузки целого.
 *
 * Для больших видео (100МБ+) мигрируем на AES-256-CTR + HMAC chunked в
 * будущей итерации (Phase 2C).
 *
 * # Кеш
 *
 * Подставляется в `CacheDataSource` upstream chain → first play decrypts,
 * последующие plays читают **plain bytes** из SimpleCache (уже расшифрованы).
 * Decrypt CPU cost — only first time per video.
 */
@OptIn(UnstableApi::class)
class EncBlobDataSource(
    private val upstream: DataSource,
    private val httpClient: OkHttpClient,
) : DataSource {

    private var delegate: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        val keys = BlobUrlComposer.parseKeys(url)
        if (keys == null) {
            // Plain URL — delegate upstream, передаём как есть.
            delegate = upstream
            return upstream.open(dataSpec)
        }
        val (key, nonce) = keys
        val baseUrl = BlobUrlComposer.stripFragment(url)
        val response = httpClient.newCall(
            Request.Builder().url(baseUrl).build()
        ).execute()
        response.body?.use { body ->
            if (!response.isSuccessful) {
                throw java.io.IOException("enc video HTTP ${response.code} for $baseUrl")
            }
            val encrypted = body.bytes()
            val plain = BlobCrypto.decrypt(encrypted, key, nonce)
            val ba = ByteArrayDataSource(plain)
            delegate = ba
            // Передаём оригинальный URI в spec чтобы кеш-ключ был тот же.
            return ba.open(DataSpec.Builder().setUri(dataSpec.uri).build())
        } ?: throw java.io.IOException("empty body")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return delegate?.read(buffer, offset, length) ?: -1
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun getUri() = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        try { delegate?.close() } catch (_: Throwable) {}
        delegate = null
    }
}

@OptIn(UnstableApi::class)
class EncBlobDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val httpClient: OkHttpClient,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        EncBlobDataSource(upstreamFactory.createDataSource(), httpClient)
}
