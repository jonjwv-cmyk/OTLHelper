package com.example.otlhelper.core.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.example.otlhelper.data.network.HttpClientFactory
import java.io.File

/**
 * Process-wide ExoPlayer SimpleCache + OkHttp-backed HTTP source.
 *
 * # Disk cache
 *
 * Videos from R2 (chat / news attachments) cache to disk the first time they
 * play; subsequent views stream from local storage with zero network. 256 MB
 * LRU in `/files/video_cache`. Same cache is shared across all
 * InlineVideoPlayer instances in the process via this singleton, so
 * SimpleCache's single-writer requirement is honored.
 *
 * # HTTP source — §TZ-2.3.20
 *
 * Раньше использовали `DefaultHttpDataSource` — стандартный Media3 HTTP
 * stack с системным DNS. Трафик видео шёл напрямую на CF IP → ISP DPI
 * троттлил (тот же механизм что на аватарках и base sync). mp4 файлы
 * буферизировались по несколько секунд на каждом кадре.
 *
 * Теперь `OkHttpDataSource` с нашим `HttpClientFactory.imageClient()` —
 * тот же OkHttpClient что у Coil для аватаров. DNS override на
 * `*.otlhelper.com` → VPS `45.12.239.5` → TLS termination → CF. Скорость
 * как у картинок (10+ МБ/с на хорошей сети).
 */
@OptIn(UnstableApi::class)
object VideoCache {
    private const val MAX_BYTES = 256L * 1024 * 1024  // 256 MB
    private const val DIR_NAME = "video_cache"

    @Volatile private var cache: SimpleCache? = null
    @Volatile private var sourceFactory: MediaSource.Factory? = null

    /** Returns the shared SimpleCache, creating it on first access. */
    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.filesDir, DIR_NAME),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext)
        ).also { cache = it }
    }

    /**
     * Returns a MediaSource.Factory that reads through the cache — wires this
     * into ExoPlayer.Builder(context).setMediaSourceFactory(...) and every
     * subsequent video plays cache-first.
     */
    fun mediaSourceFactory(context: Context): MediaSource.Factory =
        sourceFactory ?: synchronized(this) {
            sourceFactory ?: run {
                // OkHttp вместо DefaultHttpDataSource — с нашим DNS override,
                // custom TrustManager (VPS self-signed cert) и connection pool.
                val http = OkHttpDataSource.Factory(HttpClientFactory.imageClient())
                val upstream = DefaultDataSource.Factory(context.applicationContext, http)
                // §TZ-2.3.28 — оборачиваем upstream в EncBlobDataSourceFactory.
                // Если URL c fragment `#k=...&n=...` — скачиваем ciphertext,
                // расшифровываем в RAM, stream'им plain bytes. Если без —
                // идёт стандартный OkHttp path. В кеше хранятся plain bytes.
                val encAware = com.example.otlhelper.core.security.EncBlobDataSourceFactory(
                    upstream, HttpClientFactory.imageClient(),
                )
                val cacheSource = CacheDataSource.Factory()
                    .setCache(get(context))
                    .setUpstreamDataSourceFactory(encAware)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                DefaultMediaSourceFactory(context.applicationContext)
                    .setDataSourceFactory(cacheSource)
                    .also { sourceFactory = it }
            }
        }
}
