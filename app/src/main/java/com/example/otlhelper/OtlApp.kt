package com.example.otlhelper

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.example.otlhelper.core.metrics.NetworkMetricsBuffer
import com.example.otlhelper.core.push.NotificationChannels
import com.example.otlhelper.core.push.PushTokenManager
import com.example.otlhelper.core.security.RootDetector
import com.example.otlhelper.core.telemetry.CrashHandler
import com.example.otlhelper.core.telemetry.Telemetry
import com.example.otlhelper.data.sync.BaseSyncManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltAndroidApp
class OtlApp : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var pushTokenManager: PushTokenManager

    @Inject
    lateinit var telemetry: Telemetry

    @Inject
    lateinit var networkMetricsBuffer: NetworkMetricsBuffer

    @Inject
    lateinit var baseSyncManager: BaseSyncManager

    @Inject
    lateinit var appSettings: com.example.otlhelper.core.settings.AppSettings

    @Inject
    lateinit var cacheRetentionRunner: com.example.otlhelper.core.cache.CacheRetentionRunner

    override fun onCreate() {
        super.onCreate()
        // SF-2026 §3.14 — устанавливаем глобальный обработчик крэшей ПЕРВЫМ
        // делом, до любого другого кода который может бросить исключение.
        CrashHandler.install(telemetry)

        // §0.11.x — global TelemetryHook для модулей вне Hilt scope
        // (например ExoPlayer DataSource'ы создаются ExoPlayer factory без DI).
        // EncBlobDataSource / VideoCache могут вызывать TelemetryHook.event(...)
        // чтобы фиксировать download/decrypt timings, ExoPlayer errors,
        // playback start латенси. Все события идут в user_activity через
        // PendingAction → server batch flush на heartbeat'е.
        com.example.otlhelper.core.telemetry.TelemetryHook.emitter = { eventType, payload ->
            telemetry.event(eventType, payload)
        }

        // §TZ-2.3.31 Phase 4c — APK hardening. В release убивает процесс,
        // если APK debuggable, подключён отладчик, или cert не совпадает.
        com.example.otlhelper.core.security.IntegrityGuard.enforceOrDie(this)

        // §TZ-2.3.17 — инициализация custom SSL truststore (VPS self-signed cert)
        // ДО любых OkHttpClient-ов. HttpClientFactory кеширует SSLContext на
        // первом обращении; appContext нужен чтобы прочитать res/raw/otl_vps_cert.pem.
        // §TZ-2.3.25 — appSettings прокидываем сразу, чтобы E2EInterceptor
        // видел флаг e2eeEnabled на первом REST-запросе.
        com.example.otlhelper.data.network.HttpClientFactory.init(this, appSettings)

        // §TZ-2.4.0 — pre-flight TCP ping primary + (optional) backup VPS.
        // Async, не блокирует startup. RouteState ставит здоровый IP первым
        // в DNS lookup → первый запрос идёт сразу на работающий узел.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.example.otlhelper.core.network.RouteState.warmRoute() }
        }

        // Per-request latency → (1) Telemetry.timing для юзер-видимых > 2с
        // slow_action событий; (2) NetworkMetricsBuffer — ВСЕ запросы в
        // batch для аналитики (p50/avg/error_rate per action). Сервер
        // агрегирует в `network_metrics` таблице, retention 30 дней.
        networkMetricsBuffer.start()
        com.example.otlhelper.ApiClient.onActionLatency = { action, durationMs, ok, httpStatus, errCode ->
            telemetry.timing(action, durationMs, ok)
            networkMetricsBuffer.record(
                action = action,
                durationMs = durationMs,
                httpStatus = httpStatus,
                ok = ok,
                errorCode = errCode,
            )
        }

        // SF-2026 §3.15.a.Б — создаём 3 канала уведомлений (normal/important/urgent).
        // Идемпотентно — NotificationManager игнорирует повторные create_channel.
        NotificationChannels.ensureCreated(this)

        // Fire-and-forget — if the user is already logged in, make sure the
        // backend has an up-to-date FCM token for this device. No-op when
        // there is no session yet.
        pushTokenManager.syncToken()

        // §RELIABLE-BASE — 3-слойная надёжность обновления справочника МОЛ:
        //  1. FCM push от сервера (`type=base_changed` → force) — мгновенно
        //  2. Heartbeat piggyback `base_version` check — ~25с когда app open
        //  3. PeriodicWork 15min failsafe — эта регистрация; ловит случай
        //     когда FCM не доставился и app закрыт (Huawei без GMS, doze,
        //     пользователь выключил FCM в настройках). KEEP — идемпотентно.
        baseSyncManager.enqueuePeriodic()

        // §TZ-2.3.36 — local cache retention. Fire-and-forget, работает раз
        // в сутки, удаляет cached_feed_items старше retentionDays (default 30).
        cacheRetentionRunner.runIfDue()

        // §TZ-2.3.40 — Если юзер только что установился на скачанную версию,
        // APK-файл в externalFiles/Downloads уже не нужен. Удаляем + чистим
        // prefs чтобы не занимать место.
        com.example.otlhelper.core.update.AppUpdate.clearStaleAfterUpdate(this)

        // Security posture signal (раз в сессию, не per-activity). Не
        // блокируем работу даже если устройство рутовано/эмулятор — только
        // логируем для статистики в user_activity, чтобы знать кто из юзеров
        // сидит в рисковом окружении.
        val rooted = RootDetector.likelyRooted()
        val emulator = RootDetector.isEmulator()
        if (rooted || emulator) {
            telemetry.event("security_posture", mapOf(
                "rooted" to rooted,
                "emulator" to emulator,
            ))
        }
    }

    // §TZ-2.3.12 — Coil 3 ImageLoader с полноценным memory + disk cache.
    //
    // Раньше Coil не имел ни memory, ни disk cache (в Coil 3 они opt-in).
    // Эффект: каждая прокрутка feed → все аватары и media перекачивались
    // из сети. Аватарка 200КБ × 20 сообщений в feed = 4МБ траффика **при
    // каждом** re-compose. Именно это юзер ощущал как «медленно принимает
    // кусками».
    //
    // Теперь:
    //  • Memory cache 25% доступной RAM — одна прокрутка = все вложения
    //    из RAM, мгновенно, 0 network.
    //  • Disk cache 256 МБ в cacheDir — переживает рестарт приложения.
    //    Аватарки и media сохраняются **физически на диске**. Заход в
    //    приложение после 2 дней → всё из disk cache, 0 network.
    //  • respectCacheHeaders=true (default) — Coil уважает `Cache-Control`
    //    от сервера (у нас 1ч для avatars, 1 год immutable для media).
    //  • При смене аватара URL содержит `?v={storage_key_timestamp}`
    //    → новый URL → новый cache entry, старый умрёт по TTL.
    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        val diskCacheDir = java.io.File(context.cacheDir, "coil_image_cache")
        return ImageLoader.Builder(context)
            .crossfade(true)
            .memoryCache {
                coil3.memory.MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(with(okio.Path.Companion) { diskCacheDir.toOkioPath() })
                    .maxSizeBytes(256L * 1024 * 1024) // 256 MB
                    .build()
            }
            .components {
                // §TZ-2.3.28 — кастомный Fetcher для encrypted-blob URL'ов
                // (с fragment `#k=...&n=...`). Стоит ПЕРЕД OkHttpFetcher:
                // если URL не зашифрован — Factory возвращает null, Coil
                // fallback'ается на стандартный HTTP fetcher.
                add(com.example.otlhelper.core.security.EncBlobFetcher.Factory {
                    com.example.otlhelper.data.network.HttpClientFactory.imageClient()
                })

                // §TZ-2.3.14 — Coil использует наш OkHttpClient с DNS override
                // *.otlhelper.com → VPS 45.12.239.5. Аватары и media скачиваются
                // через VPS-мостик (обход ISP-троттлинга CF IP).
                add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                    callFactory = { com.example.otlhelper.data.network.HttpClientFactory.imageClient() }
                ))
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }
}
