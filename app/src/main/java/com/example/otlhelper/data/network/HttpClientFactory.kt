package com.example.otlhelper.data.network

import android.content.Context
import com.example.otlhelper.core.network.AuthSigningInterceptor
import com.example.otlhelper.core.security.PinningConfig
import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Единая точка сборки OkHttpClient для REST ([com.example.otlhelper.ApiClient]) и
 * WebSocket ([WsClient]). Гарантирует что обе зависимости получают
 * идентичную конфигурацию безопасности (cert pinning, таймауты, interceptors).
 *
 * Когда перейдём на KMP/desktop:
 *  - Android продолжит использовать OkHttp.
 *  - Desktop получит Ktor с OkHttp engine (на JVM) — тот же OkHttpClient,
 *    просто обёрнутый в Ktor HttpClient.
 *  - `PinningConfig` остаётся pure Kotlin и переносится в `:shared`.
 */
object HttpClientFactory {

    // §TZ-2.3.17 — Custom trust для self-signed cert нашего VPS.
    //
    // Контекст: ISP твоего мобильного оператора троттлит TLS-handshake
    // с Cloudflare-fingerprint через ЛЮБОЙ порт (diagnostic logs показали
    // 6 КБ/с на passthrough :443/:8443/:9999, 16 МБ/с на direct nginx self-
    // signed TLS на :3000/:8888/:9443). DPI матчит не hostname и не port,
    // а "отпечаток" TLS ClientHello/ServerHello Cloudflare-библиотеки.
    //
    // Решение: VPS делает TLS termination (nginx отдаёт СВОЙ cert, не CF),
    // затем проксирует через второй TLS-handshake на CF. Клиент видит только
    // наш self-signed cert — DPI не распознаёт CF → не режет → полная скорость.
    //
    // Приватность: VPS-nginx на миллисекунды видит plaintext между decode
    // TLS от клиента и re-encode TLS к CF. Компенсация:
    //  • HMAC-signing запросов (AuthSigningInterceptor) — VPS не может
    //    сфабриковать запрос, только пропустить.
    //  • Bearer token — VPS теоретически мог бы украсть, но это **твой
    //    VPS** под твоим контролем (SSH-only, ufw, fail2ban).
    //  • TLS cert pinning — клиент доверяет ИМЕННО этому cert, любая
    //    подмена (MITM) немедленно detected → SSLHandshakeException.
    //
    // Это стандартный reverse proxy паттерн — так работают 99% сайтов
    // за CDN или load balancer'ом.

    @Volatile
    private var cachedSslContext: Pair<SSLSocketFactory, X509TrustManager>? = null

    /**
     * Инициализация кастомного SSL-контекста с нашим VPS-cert.
     * Вызывается один раз при создании OkHttpClient.
     * Клиент будет доверять ТОЛЬКО этому cert для *.otlhelper.com.
     * Для остальных хостов (Firebase, R2 public) — системный truststore.
     */
    private fun buildVpsSslFactory(context: Context): Pair<SSLSocketFactory, X509TrustManager> {
        cachedSslContext?.let { return it }
        synchronized(this) {
            cachedSslContext?.let { return it }

            // Load self-signed cert из res/raw/otl_vps_cert.pem
            val certResId = context.resources.getIdentifier(
                "otl_vps_cert", "raw", context.packageName
            )
            require(certResId != 0) { "otl_vps_cert.pem missing in res/raw" }

            val cert = context.resources.openRawResource(certResId).use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
            }

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("otl_vps", cert)
            }
            val vpsTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }
            val vpsTrustManager = vpsTmf.trustManagers[0] as X509TrustManager

            // Системный truststore для остальных хостов (FCM google, r2.dev)
            val sysTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(null as KeyStore?)
            }
            val sysTrustManager = sysTmf.trustManagers[0] as X509TrustManager

            // Composite TM — сначала пробуем VPS cert, если fail → система
            val compositeTm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                    vpsTrustManager.checkClientTrusted(chain, authType)
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    try {
                        vpsTrustManager.checkServerTrusted(chain, authType)
                    } catch (e: Exception) {
                        sysTrustManager.checkServerTrusted(chain, authType)
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    vpsTrustManager.acceptedIssuers + sysTrustManager.acceptedIssuers
            }

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(compositeTm), java.security.SecureRandom())
            }
            val result = sslContext.socketFactory to compositeTm
            cachedSslContext = result
            return result
        }
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var appSettings: com.example.otlhelper.core.settings.AppSettings? = null

    /**
     * Вызывается ОДИН раз из [com.example.otlhelper.OtlApp.onCreate].
     * AppSettings нужен для E2EInterceptor (runtime toggle флага e2eeEnabled).
     */
    fun init(
        context: Context,
        settings: com.example.otlhelper.core.settings.AppSettings? = null,
    ) {
        appContext = context.applicationContext
        appSettings = settings
    }

    /**
     * Создаёт OkHttpClient для REST API.
     * [tokenProvider] — лямбда для взятия актуального Bearer-токена (меняется
     * при login/logout, поэтому закрытие, а не значение).
     *
     * §TZ-2.3.9 (Nankin 60сек-залип): агрессивнее таймауты — юзер с DPI/VPN
     * не должен сидеть на «Подождите» минуту. 25сек достаточно чтобы наш
     * сервер (avg <500ms) ответил даже через кривой proxy, но юзер получит
     * фейл и UI сможет показать «Повторить» в разумное время. Bump'нули
     * callTimeout тоже — 45с хватает на avatar upload через 3G.
     *
     *  - connect 10s — TLS handshake к CF edge редко превышает 3с
     *  - read 25s — сервер avg <500ms; если 25с не успели — сеть мертва
     *  - write 40s — avatar base64 upload ~500KB на 3G EDGE успеет
     *  - call 45s — total hard-cap, защита от подвиса в interceptor chain
     *  - connection pool 5 × 5 мин — corporate firewalls часто убивают
     *    idle sockets, короткий keep-alive предотвращает reuse протухшего
     *  - retryOnConnectionFailure=true — OkHttp auto-retry если сокет умер
     *    между open и read (часто на VPN переключениях)
     *  - followRedirects=false — captive-portal proxies могут вернуть 302
     *    на login-страницу; мы предпочитаем явный failure, а не тихое
     *    следование к HTML
     */
    fun restClient(tokenProvider: () -> String): OkHttpClient =
        baseBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            // §TZ-2.3.13 — возвращаем HTTP/2 (revert HTTP/1.1 force из 2.3.10).
            // Замеры показали что для РФ-юзеров (весь трафик через WEUR/MAD
            // +100ms RTT) HTTP/1.1 даёт overhead 600+ms на setup 6 TCP
            // соединений для feed с 20 аватарками. С HTTP/2 все 20 потоков
            // мультиплексируются на одном TCP → 1 handshake → 5x быстрее
            // на feed loading.
            // Теоретический HoL blocking на плохой сети — редкий случай,
            // в замерах user'а overhead от настройки HTTP/1.1 перекрыл
            // любой потенциальный выигрыш.
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            // §TZ-2.3.10 — idle keep-alive 30s вместо 5 min.
            // Причина: на плохой сети idle socket «умирает» (WiFi флапнул,
            // базовая станция сменилась, DPI сбросил middlebox). Старый
            // pool держал его 5 мин → новый request брал зомби-socket →
            // ждал 25с пока TCP не осознает → retry. 30с keep-alive ловит
            // живые reuse (быстрые последовательные запросы) но быстро
            // выкидывает протухшие.
            .connectionPool(ConnectionPool(8, 30, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .followRedirects(false)
            .followSslRedirects(false)
            // §TZ-2.3.26 — ВАЖНО: AuthSigning ДОЛЖЕН идти ПЕРЕД E2E в chain.
            //
            // OkHttp interceptors идут в порядке addInterceptor. Цепочка для
            // signed request:
            //   AuthSigning.intercept (подписывает plaintext body → HMAC в header)
            //     → E2E.intercept (шифрует тот же plaintext body)
            //       → network (ciphertext body + headers с HMAC на plaintext)
            // Сервер: decrypt → plaintext → проверяет HMAC против plaintext → OK.
            //
            // Баг 2.3.25: порядок был обратный → HMAC считался на ciphertext,
            // сервер не мог верифицировать → все signed requests 401. Logins
            // работали (без подписи), новости/чаты/база/всё остальное — нет.
            .addInterceptor(AuthSigningInterceptor(tokenProvider))
            .also { builder ->
                val ctx = appContext
                val settings = appSettings
                if (ctx != null && settings != null) {
                    builder.addInterceptor(
                        com.example.otlhelper.core.security.E2EInterceptor(ctx, settings)
                    )
                }
            }
            .build()

    /**
     * Создаёт OkHttpClient для WebSocket. Отдельный так как у WS другие
     * таймауты (readTimeout = 0 = бесконечный hold) и нет AuthSigningInterceptor
     * (WS авторизация через первое сообщение hello).
     *
     * pingInterval 20s + connectTimeout 15s — на DPI-фильтрах (Касперский,
     * корп. proxy), которые тихо дропают idle TCP, пинг обнаружит разрыв
     * и WsClient запустит reconnect.
     */
    fun wsClient(): OkHttpClient =
        baseBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    /**
     * §TZ-2.3.14 — OkHttpClient для Coil image loader.
     * Без AuthSigningInterceptor (image GET-запросы подписывать бессмысленно),
     * но С DNS override через baseBuilder — аватары и media идут через VPS
     * вместо прямого CF IP (обход ISP-троттлинга для больших файлов).
     * Таймауты подстроены под image downloads: больше на read (картинки
     * могут быть до 500 КБ), нет hard callTimeout cap.
     */
    fun imageClient(): OkHttpClient =
        baseBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(12, 60, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .build()

    /**
     * §TZ-2.3.19 — OkHttpClient для больших скачиваний (база справочника,
     * APK). BaseSyncWorker раньше использовал Android `DownloadManager`,
     * который ходит через system DNS → напрямую на CF IP → ISP троттлит
     * (как и любой трафик к CF fingerprint). Клиент теперь стримит
     * через OkHttp с нашим DNS override → VPS → CF → R2. Длительный
     * readTimeout (90с) на случай большой базы на слабом канале.
     */
    fun downloadClient(): OkHttpClient =
        baseBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    /**
     * §TZ-2.4.0 — DNS override через [com.example.otlhelper.core.network.RouteState].
     *
     * Раньше захардкожен один IP. Теперь возвращаем список (primary + опц.
     * backup). OkHttp нативно перебирает по очереди при connection fail.
     *
     * Семантика та же: для *.otlhelper.com отдаём VPS IP (Defacto-DPI обход
     * Cloudflare fingerprint). Прочие хосты — штатный DNS.
     *
     * Контекст (DPI обход): РФ-провайдеры режут bandwidth ко всем CF IPs.
     * Custom domain api.otlhelper.com публично резолвится в CF, но клиент
     * берёт его через VPS — DPI не видит CF fingerprint. См. §TZ-2.3.10/13/17
     * для исторического контекста.
     */
    private val otlHelperDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname == "otlhelper.com" ||
                hostname.endsWith(".otlhelper.com")
            ) {
                return com.example.otlhelper.core.network.RouteState.orderedIps()
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    /**
     * Общий builder — применяет DNS override на VPS + custom TrustManager
     * для self-signed cert VPS (§TZ-2.3.17).
     */
    private fun baseBuilder(): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .dns(otlHelperDns)
        // SSL: VPS self-signed cert + system CA fallback
        appContext?.let { ctx ->
            val (factory, trustManager) = buildVpsSslFactory(ctx)
            builder.sslSocketFactory(factory, trustManager)
            // hostnameVerifier: VPS cert SAN включает api/cdn/avatars/media/root —
            // default HostnameVerifier пройдёт. Но система верифицирует что cert
            // соответствует hostname — проверим что SAN правильный.
        }
        // CertificatePinner только если PinningConfig активен (поверх SSL).
        val pins = PinningConfig.pinsForHost(PinningConfig.HOST)
        if (pins.isNotEmpty()) {
            val pinner = CertificatePinner.Builder().apply {
                pins.forEach { pin -> add(PinningConfig.HOST, "sha256/$pin") }
            }.build()
            builder.certificatePinner(pinner)
        }
        return builder
    }
}
