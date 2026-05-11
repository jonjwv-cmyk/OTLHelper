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

    /**
     * §0.11.x — кэшируем расшифрованные plain bytes на инстанс. ExoPlayer
     * иногда вызывает open() повторно для seek (например на moov box mp4 в
     * конце файла). Раньше мы перекачивали + перешифровывали весь blob
     * каждый раз — это и тяжело, и формально работает. НО ProgressiveMediaPeriod
     * не любит когда после первого open мы получили все 1062286 bytes,
     * а на втором open с position=1050588 length=11698 вернули **снова все
     * bytes** (ByteArrayDataSource без position) → ExoPlayer видит length
     * mismatch → бросает DataSourceException (Source error / DATA_NOT_AVAILABLE).
     *
     * Фикс: один раз скачиваем+расшифровываем, потом передаём position/length
     * из dataSpec в ByteArrayDataSource.open чтобы он отдал правильный
     * диапазон bytes.
     */
    @Volatile private var cachedPlain: ByteArray? = null
    @Volatile private var cachedKey: String? = null

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        val keys = BlobUrlComposer.parseKeys(url)
        if (keys == null) {
            // §0.11.x — telemetry: URL без encryption fragment → upstream plain.
            // Эта ветка должна срабатывать только для legacy plain URLs.
            com.example.otlhelper.core.telemetry.TelemetryHook.event(
                "media_open_plain",
                mapOf(
                    "host" to (dataSpec.uri.host ?: "-"),
                    "position" to dataSpec.position,
                    "length" to dataSpec.length,
                ),
            )
            delegate = upstream
            return upstream.open(dataSpec)
        }
        val (key, nonce) = keys
        val baseUrl = BlobUrlComposer.stripFragment(url)

        // §0.11.x — повторный open для того же URL (seek): не скачиваем
        // заново, используем кэш plain bytes.
        val cacheKey = baseUrl
        val plain: ByteArray = if (cachedPlain != null && cachedKey == cacheKey) {
            // Telemetry: cache hit (seek после первого open) — fast path.
            com.example.otlhelper.core.telemetry.TelemetryHook.event(
                "media_open_cached",
                mapOf(
                    "host" to (dataSpec.uri.host ?: "-"),
                    "position" to dataSpec.position,
                    "length" to dataSpec.length,
                    "plain_size" to cachedPlain!!.size,
                ),
            )
            cachedPlain!!
        } else {
            val downloadT0 = System.currentTimeMillis()
            val response = try {
                httpClient.newCall(Request.Builder().url(baseUrl).build()).execute()
            } catch (t: Throwable) {
                com.example.otlhelper.core.telemetry.TelemetryHook.event(
                    "media_http_exception",
                    mapOf(
                        "host" to (dataSpec.uri.host ?: "-"),
                        "exc_class" to t.javaClass.simpleName,
                        "exc_msg" to (t.message?.take(140) ?: ""),
                        "elapsed_ms" to (System.currentTimeMillis() - downloadT0),
                    ),
                )
                throw t
            }
            response.body?.use { body ->
                val downloadMs = System.currentTimeMillis() - downloadT0
                if (!response.isSuccessful) {
                    com.example.otlhelper.core.telemetry.TelemetryHook.event(
                        "media_http_fail",
                        mapOf(
                            "host" to (dataSpec.uri.host ?: "-"),
                            "http" to response.code,
                            "download_ms" to downloadMs,
                        ),
                    )
                    throw java.io.IOException("enc video HTTP ${response.code} for $baseUrl")
                }
                val encrypted = body.bytes()
                val decryptT0 = System.currentTimeMillis()
                val decrypted = try {
                    BlobCrypto.decrypt(encrypted, key, nonce)
                } catch (t: Throwable) {
                    com.example.otlhelper.core.telemetry.TelemetryHook.event(
                        "media_decrypt_fail",
                        mapOf(
                            "host" to (dataSpec.uri.host ?: "-"),
                            "encrypted_size" to encrypted.size,
                            "exc_class" to t.javaClass.simpleName,
                            "exc_msg" to (t.message?.take(140) ?: ""),
                            "download_ms" to downloadMs,
                        ),
                    )
                    throw t
                }
                val decryptMs = System.currentTimeMillis() - decryptT0
                cachedPlain = decrypted
                cachedKey = cacheKey
                // Telemetry: успешный путь — даём server'у видеть как быстро
                // прошёл download + decrypt (можно строить p50/p95 percentiles).
                com.example.otlhelper.core.telemetry.TelemetryHook.event(
                    "media_open_ok",
                    mapOf(
                        "host" to (dataSpec.uri.host ?: "-"),
                        "encrypted_size" to encrypted.size,
                        "plain_size" to decrypted.size,
                        "download_ms" to downloadMs,
                        "decrypt_ms" to decryptMs,
                        "kbps" to (encrypted.size * 1000L / downloadMs.coerceAtLeast(1L) / 1024L),
                    ),
                )
                decrypted
            } ?: run {
                com.example.otlhelper.core.telemetry.TelemetryHook.event(
                    "media_http_empty_body",
                    mapOf("host" to (dataSpec.uri.host ?: "-")),
                )
                throw java.io.IOException("empty body")
            }
        }

        // §0.11.x КРИТИЧНО: передаём position + length из исходного dataSpec
        // чтобы ByteArrayDataSource отдал правильный диапазон.
        // Раньше создавался "пустой" DataSpec → ByteArrayDataSource всегда
        // возвращал length=plain.size → ExoPlayer на seek-open (position>0,
        // length<plain.size) получал mismatch → DataSourceException.
        val ba = ByteArrayDataSource(plain)
        delegate = ba
        return ba.open(
            DataSpec.Builder()
                .setUri(dataSpec.uri)
                .setPosition(dataSpec.position)
                .setLength(dataSpec.length)
                .build()
        )
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
