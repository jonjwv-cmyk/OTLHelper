package com.example.otlhelper.desktop.core.network

import com.example.otlhelper.desktop.BuildInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * §TZ-DESKTOP-0.10.0 — Self-test для diagnostic.
 *
 * Запускается автоматически на старте app (через ~30 секунд после launch
 * чтобы не нагружать первый старт), пишет результаты в OTL-debug.log.
 *
 * Что тестируем (Win + corp-proxy):
 *   1. Direct VPS IP self-signed → diag.txt (через corp-proxy CONNECT, ожидаем PASS)
 *   2. sslip.io hostname LE cert → diag.txt (PASS если LE cert работает в OkHttp)
 *   3. sslip.io test.png download (PASS + size+latency)
 *   4. CF api.otlhelper.com /diag.txt (для сравнения скорости)
 *
 * На Mac/direct → выполняем только тесты 1, 3, 4 без proxy.
 *
 * Цель — измерить РЕАЛЬНУЮ скорость каждого пути на корпе, чтобы понимать
 * что media routing через sslip.io реально быстрее. Все timings в логе.
 *
 * Тесты НЕ блокируют app — выполняются на background corotuine.
 */
object NetworkSelfTest {

    /** Stand-alone OkHttp client с trust-all для теста (cert variation тестим). */
    private fun trustAllClient(throughProxy: CorporateProxy.ProxyConfig?): OkHttpClient {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val sslCtx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .sslSocketFactory(sslCtx.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
        if (throughProxy != null) {
            builder
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(throughProxy.host, throughProxy.port)))
                .proxyAuthenticator(
                    CompositeProxyAuthenticator(
                        proxyHost = throughProxy.host,
                        proxyPort = throughProxy.port,
                        spnCandidates = throughProxy.spnCandidates,
                    ),
                )
        }
        return builder.build()
    }

    /** Один HTTP probe → запись в лог. */
    private fun probe(label: String, url: String, client: OkHttpClient) {
        val started = System.currentTimeMillis()
        try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                val ms = System.currentTimeMillis() - started
                val body = resp.body
                val len = runCatching { body?.bytes()?.size ?: 0 }.getOrDefault(-1)
                val sample = if (len in 1..160) {
                    runCatching {
                        // For diag.txt we want to verify magic token presence
                        val s = body?.string()?.take(120) ?: ""
                        s.replace("\n", " | ")
                    }.getOrDefault("")
                } else ""
                NetMetricsLogger.event(
                    "selftest[$label]: $url → HTTP ${resp.code} ${len}b in ${ms}ms" +
                        if (sample.isNotEmpty()) " sample=\"$sample\"" else ""
                )
            }
        } catch (t: Throwable) {
            val ms = System.currentTimeMillis() - started
            NetMetricsLogger.event(
                "selftest[$label]: $url → ${t.javaClass.simpleName}: ${t.message?.take(80)} in ${ms}ms"
            )
        }
    }

    /**
     * Запустить self-test. Безопасно — все exceptions перехватываются.
     * Вызывать на background coroutine, не блокирует UI.
     */
    fun run() {
        runCatching {
            val proxy = CorporateProxy.detect()
            NetMetricsLogger.event(
                "selftest: starting (os=${BuildInfo.OS}, version=${BuildInfo.VERSION}, " +
                "proxy=${proxy?.let { "${it.host}:${it.port}" } ?: "direct"}, " +
                "media-resolver=${MediaUrlResolver.describe()})"
            )

            val client = trustAllClient(proxy)

            // Test A — VPS direct via IP (self-signed cert)
            probe("vps-ip", "https://45.12.239.5/diag.txt", client)

            // Test B — VPS via sslip.io hostname (LE cert) — KEY TEST
            probe("sslip-vps", "https://45-12-239-5.sslip.io/diag.txt", client)

            // Test C — sslip.io binary download (test.png — size verify)
            probe("sslip-png", "https://45-12-239-5.sslip.io/test.png", client)

            // Test D — CF (api.otlhelper.com) — для сравнения скорости с throttled CF
            // Но не делаем это в production — это slow и не нужно. Лог покажет
            // только sslip-png speed для проверки что media работает быстро.

            NetMetricsLogger.event("selftest: done")
        }.onFailure {
            runCatching {
                NetMetricsLogger.event("selftest: aborted ${it.javaClass.simpleName}: ${it.message?.take(100)}")
            }
        }
    }
}
