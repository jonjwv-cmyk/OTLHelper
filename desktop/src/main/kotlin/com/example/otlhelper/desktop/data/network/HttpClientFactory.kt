package com.example.otlhelper.desktop.data.network

import com.example.otlhelper.desktop.core.network.CompositeProxyAuthenticator
import com.example.otlhelper.desktop.core.network.CorporateProxy
import com.example.otlhelper.desktop.core.security.PinningConfig
import com.example.otlhelper.desktop.data.security.Secrets
import okhttp3.CertificatePinner
import okhttp3.Dns
import okhttp3.OkHttpClient
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
 * §TZ-DESKTOP-0.1.0 этап 3 — HTTP-стек для desktop, зеркалит
 * `app/data/network/HttpClientFactory.kt` по структуре, но без Android-specific
 * кусков (LocalContext, AuthSigningInterceptor, E2EInterceptor).
 *
 * Архитектура:
 *   Desktop OkHttp
 *     → Dns override *.otlhelper.com → VPS 45.12.239.5
 *     → TLS pinning через custom TrustManager (VPS self-signed cert)
 *     → POST /api (plain JSON body)
 *   VPS nginx reverse-proxy → CF Worker
 *
 * Без HMAC-signing и E2E — пока минимум для login-flow. Signing/E2E
 * подключим в этапе 4 когда подтягиваем чаты/новости (там сервер по
 * feature-flag'у может enforce).
 */
object HttpClientFactory {

    // §TZ-DESKTOP-DIST — VPS IP, base URL и cert filename вынесены в
    // [Secrets] (XOR-обфусцированный ByteArray). После ProGuard'а в EXE
    // нет string-literal'ов "45.12.239.5" / "otlhelper.com" — `strings`
    // на бинаре их не покажет. Не криптография, лишь cosmetic barrier.

    // §TZ-2.4.0 — DNS lookup через RouteState (primary + опционально backup).
    // OkHttp нативно перебирает по списку при connection fail.
    // §TZ-DESKTOP-0.1.0 — DNS override локален для нашего OkHttp instance,
    // не трогает системный resolver (браузер и пр. работают как раньше).
    private val vpsDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return if (hostname == Secrets.HOST_BASE || hostname.endsWith(Secrets.HOST_DOT_SUFFIX)) {
                com.example.otlhelper.desktop.core.network.RouteState.orderedIps()
            } else {
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    @Volatile
    private var cachedSsl: Pair<SSLSocketFactory, X509TrustManager>? = null

    /**
     * Composite TrustManager: сначала пытается наш VPS self-signed cert
     * (из resources/otl_vps_cert.pem), если fail → падает на системный
     * truststore. Нужно чтобы:
     *   • *.otlhelper.com (через VPS) — через VPS cert
     *   • другие хосты (Firebase — не используется на desktop; в будущем Google
     *     OAuth для Sheets) — через системный truststore.
     */
    private fun sslContext(): Pair<SSLSocketFactory, X509TrustManager> {
        cachedSsl?.let { return it }
        synchronized(this) {
            cachedSsl?.let { return it }
            val certStream = javaClass.classLoader
                .getResourceAsStream(Secrets.CERT_PATH)
                ?: error("vps cert resource missing")

            val cert = certStream.use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }

            val vpsStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("otl_vps", cert)
            }
            val vpsTmf = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(vpsStore) }
            val vpsTm = vpsTmf.trustManagers[0] as X509TrustManager

            val sysTmf = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
            val sysTm = sysTmf.trustManagers[0] as X509TrustManager

            val compositeTm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                    vpsTm.checkClientTrusted(chain, authType)
                }
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    try {
                        vpsTm.checkServerTrusted(chain, authType)
                    } catch (_: Exception) {
                        sysTm.checkServerTrusted(chain, authType)
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    vpsTm.acceptedIssuers + sysTm.acceptedIssuers
            }

            val ctx = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(compositeTm), java.security.SecureRandom())
            }
            val result = ctx.socketFactory to compositeTm
            cachedSsl = result
            return result
        }
    }

    /**
     * §TZ-DESKTOP-0.10.1 — Trust manager для proxy-mode, объединяющий:
     *   1. Java cacerts (стандартный JVM trust store — все public CA)
     *   2. **Windows-ROOT** (system trust store — корп-CA от Касперский TLS-MITM)
     *
     * Зачем: на корп-машинах Касперский Endpoint делает TLS-interception, подсовывает
     * свой cert (signed by enterprise CA, который установлен в Windows trust store
     * через GPO). JVM cacerts эту enterprise CA не содержит → PKIX path building
     * fails → media через OkHttp падает с SSLHandshakeException.
     *
     * Выход: composite TM пробует валидацию в обоих store'ах; если хоть один
     * прошёл — chain valid. Так Java OkHttp видит то же что browser/Edge.
     *
     * Безопасность: trust расширяем только в proxy-mode. В direct-mode (дома)
     * остаётся pinned VPS-only chain. На Mac fallback на cacerts (Windows-ROOT
     * не существует — provider возвращает null).
     */
    @Volatile
    private var cachedProxySsl: Pair<SSLSocketFactory, X509TrustManager>? = null

    private fun proxySslContext(): Pair<SSLSocketFactory, X509TrustManager> {
        cachedProxySsl?.let { return it }
        synchronized(this) {
            cachedProxySsl?.let { return it }

            // 1. Java cacerts (default)
            val sysTmf = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
            val sysTm = sysTmf.trustManagers[0] as X509TrustManager

            // 2. Windows-ROOT (only on Windows; gracefully null elsewhere)
            val winTm: X509TrustManager? = runCatching {
                if (!System.getProperty("os.name", "").lowercase().contains("win")) {
                    return@runCatching null
                }
                val ks = KeyStore.getInstance("Windows-ROOT")
                ks.load(null, null)
                val tmf = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    .apply { init(ks) }
                val tm = tmf.trustManagers[0] as X509TrustManager
                com.example.otlhelper.desktop.core.network.SspiLogger.log(
                    "HttpClientFactory: Windows-ROOT trust store loaded, ${tm.acceptedIssuers.size} issuers"
                )
                tm
            }.onFailure {
                com.example.otlhelper.desktop.core.network.SspiLogger.log(
                    "HttpClientFactory: Windows-ROOT load failed: ${it.javaClass.simpleName}: ${it.message}"
                )
            }.getOrNull()

            val composite = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                    sysTm.checkClientTrusted(chain, authType)
                }
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    // 1. Try Java cacerts (LE, ISRG, Cloudflare etc.)
                    try {
                        sysTm.checkServerTrusted(chain, authType)
                        return
                    } catch (eSys: Exception) {
                        // 2. Fallback to Windows-ROOT (Kaspersky enterprise CA etc.)
                        if (winTm != null) {
                            try {
                                winTm.checkServerTrusted(chain, authType)
                                return
                            } catch (_: Exception) { /* fall through */ }
                        }
                        throw eSys
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    val all = ArrayList<X509Certificate>()
                    all.addAll(sysTm.acceptedIssuers)
                    if (winTm != null) all.addAll(winTm.acceptedIssuers)
                    return all.toTypedArray()
                }
            }

            val ctx = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(composite), java.security.SecureRandom())
            }
            val result = ctx.socketFactory to composite
            cachedProxySsl = result
            return result
        }
    }

    /**
     * §TZ-DESKTOP-0.9.1 — Detected corporate proxy (lazy, кэшируется).
     * null = direct (no proxy), используем обычный VPS-маршрут.
     */
    private val proxyConfig: CorporateProxy.ProxyConfig? by lazy { CorporateProxy.detect() }

    /**
     * Применяет proxy-mode к OkHttp builder когда найден corporate proxy:
     *  - выключает DNS-override (прокси сам резолвит, наш VPS-IP override
     *    мешает: corp firewall блочит TCP к 45.12.239.5);
     *  - выключает CertificatePinner (cert presented by CF / by corp TLS-MITM
     *    не совпадает с VPS self-signed pin; payload защищён E2E-слоем выше
     *    через [E2EInterceptor]);
     *  - заменяет TrustManager на системный (corp CA доверена в Windows store);
     *  - ставит OkHttp proxy + Windows SSPI authenticator на 407 challenge.
     *
     * Возвращает true если применили proxy-mode, false если direct.
     */
    private fun applyProxyOrDirect(builder: OkHttpClient.Builder): Boolean {
        val pc = proxyConfig
        if (pc == null) {
            // ── DIRECT: VPS DNS-override + custom TrustManager + pinning ─────
            com.example.otlhelper.desktop.core.network.SspiLogger.log(
                "HttpClientFactory: configuring DIRECT mode (VPS ${Secrets.VPS_HOST_IP}, pinning enabled)"
            )
            val (factory, tm) = sslContext()
            builder
                .dns(vpsDns)
                .sslSocketFactory(factory, tm)
            val pins = PinningConfig.pinsForHost(PinningConfig.HOST)
            if (pins.isNotEmpty()) {
                val pinner = CertificatePinner.Builder().apply {
                    pins.forEach { add(PinningConfig.HOST, "sha256/$it") }
                }.build()
                builder.certificatePinner(pinner)
            }
            return false
        }
        // ── PROXY: corporate firewall route ─────────────────────────────────
        // System DNS — прокси сам резолвит назначение.
        // §TZ-DESKTOP-0.10.1 — Composite TrustManager (cacerts + Windows-ROOT)
        // explicitly set so Kaspersky Endpoint TLS-MITM cert chains validate.
        // Без этого первые запросы на sslip.io падали с PKIX
        // (cacerts JVM не содержит enterprise corp-CA).
        // No pinning — TLS-MITM возможен, payload защищён E2E.
        // No interceptor change — AuthSigningInterceptor + E2EInterceptor
        // продолжают работать поверх прокси-туннеля.
        com.example.otlhelper.desktop.core.network.SspiLogger.log(
            "HttpClientFactory: configuring PROXY mode through ${pc.host}:${pc.port} [${pc.source}]"
        )
        val (proxyFactory, proxyTm) = proxySslContext()
        // §TZ-DESKTOP-0.9.6+0.9.7 — для SPN передаём ВСЕ candidate FQDN
        // (Windows ping-based reverse-DNS, original, Java canonical) —
        // Authenticator пробует все и выбирает тот что даёт настоящий
        // SPNEGO+Kerberos token. Java InetAddress.canonicalHostName на
        // multi-A IPs может вернуть не тот alias (наблюдали в проде).
        builder
            .dns(Dns.SYSTEM)
            .sslSocketFactory(proxyFactory, proxyTm)
            .proxy(pc.toJavaProxy())
            .proxyAuthenticator(
                CompositeProxyAuthenticator(
                    proxyHost = pc.host,
                    proxyPort = pc.port,
                    spnCandidates = pc.spnCandidates,
                ),
            )
        return true
    }

    /** REST-клиент (action-based POST /api). Таймауты как в Android REST. */
    val rest: OkHttpClient by lazy {
        // §TZ-DESKTOP-0.10.1 — proxy mode → больше read/call timeout, потому что
        // hop через VPS добавляет ~150-300ms, а CF Worker subrequest budget на
        // некоторых actions может быть >25s.
        val isProxy = proxyConfig != null
        val builder = OkHttpClient.Builder()
            .connectTimeout(if (isProxy) 15 else 10, TimeUnit.SECONDS)
            .readTimeout(if (isProxy) 60 else 25, TimeUnit.SECONDS)
            .writeTimeout(if (isProxy) 60 else 25, TimeUnit.SECONDS)
            .callTimeout(if (isProxy) 90 else 45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // §TZ-2.4.0 — порядок interceptors критичен:
            //   AuthSigning первым → подписывает plaintext body + embed _auth_token
            //   E2E вторым       → шифрует тот же body целиком
            // VPS / прокси видят только X-Request-Sig + X-OTL-Crypto + ciphertext.
            // Token, action, payload — внутри encrypted envelope.
            .addInterceptor(AuthSigningInterceptor { ApiClient.currentToken() })
            .addInterceptor(com.example.otlhelper.desktop.core.security.E2EInterceptor())
            // §TZ-DESKTOP-0.10.0 — Last interceptor: net metrics (URL/latency/bytes
            // → ~/Desktop/OTL-debug.log + in-memory ring для UI). Никогда не
            // модифицирует request/response, любой exception swallowed.
            .addInterceptor(com.example.otlhelper.desktop.core.network.NetMetricsLogger.interceptor)
        applyProxyOrDirect(builder)
        builder.build()
    }

    /**
     * §TZ-DESKTOP-DIST — клиент для скачивания installer'ов (DMG/EXE).
     *
     * Тот же VPS-канал что и REST (DNS-override + self-signed cert) — значит
     * провайдер видит только TLS-трафик к 45.12.239.5, а не путь до CF/R2.
     * Без AuthSigningInterceptor: GET /desktop endpoints публичные, токена не
     * требует. Большие таймауты — installer 100+ MB на медленном канале
     * легко берёт минуту-две.
     */
    val download: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .callTimeout(15, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .addInterceptor(com.example.otlhelper.desktop.core.network.NetMetricsLogger.interceptor)
        applyProxyOrDirect(builder)
        builder.build()
    }

    /**
     * §TZ-DESKTOP-0.9.1 — diagnostic info для UI / settings / логов.
     * Возвращает строку вида "direct" или "proxy <host>:<port> [PowerShell ...]".
     */
    fun routeDescription(): String {
        val pc = proxyConfig ?: return "direct (VPS ${Secrets.VPS_HOST_IP})"
        return "proxy ${pc.host}:${pc.port} [${pc.source}]"
    }

    /**
     * §TZ-DESKTOP-0.10.1 — URL action-based endpoint, dynamic.
     *
     * - direct mode → `https://api.otlhelper.com/api` (через VPS DNS-override
     *   на 45.12.239.5, pinning self-signed cert)
     * - proxy mode → `https://45-12-239-5.sslip.io/api` (через корп Squid
     *   на VPS direct → nginx proxy_pass на CF Worker)
     *
     * Зачем переключение в proxy mode: на корп-сети api.otlhelper.com
     * (CF anycast) DPI-throttled и 25s timeout-ит на /api. VPS sslip.io
     * не трогается, идёт быстро.
     *
     * E2E + AuthSigning живут на body — независимы от hostname. Сервер
     * получает идентичный request (nginx меняет Host header на
     * api.otlhelper.com при proxy_pass на CF).
     */
    val API_URL: String by lazy {
        com.example.otlhelper.desktop.core.network.MediaUrlResolver.resolve(
            Secrets.BASE_URL + "/api"
        )
    }

    /**
     * §TZ-DESKTOP-0.10.1 — WebSocket URL, dynamic.
     * proxy mode → wss://45-12-239-5.sslip.io/ws
     * direct mode → wss://api.otlhelper.com/ws
     */
    val WS_URL: String by lazy {
        com.example.otlhelper.desktop.core.network.MediaUrlResolver.resolve(Secrets.WS_URL)
    }
}
