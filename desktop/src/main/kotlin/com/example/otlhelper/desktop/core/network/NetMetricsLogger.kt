package com.example.otlhelper.desktop.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * §TZ-DESKTOP-0.10.0 — File+memory metrics logger для каждого HTTP-запроса.
 *
 * Цель: чтобы юзер на корпе мог открыть `~/Desktop/OTL-debug.log` и понять
 * почему медленно/не качает. Plus в UI в Settings показываем последние строки.
 *
 * Работает как OkHttp interceptor — добавляется поверх существующих
 * (AuthSigning + E2E + ProxyAuth). Никогда не модифицирует request/response,
 * только наблюдает. Любой exception в логировании → swallowed, request
 * проходит как был.
 *
 * Метрики:
 *   - URL (без query — чтобы не светить tokens / signatures)
 *   - HTTP method
 *   - response code
 *   - bytes (response body length, если known)
 *   - latency (ms)
 *   - route (corp proxy host или direct)
 *   - error (если throw) — class name + message (без stack)
 */
object NetMetricsLogger {

    private const val MAX_RING = 200
    private val ring = ConcurrentLinkedQueue<String>()
    private val ringSize = AtomicInteger(0)

    /** Возвращает последние N (или меньше) строк metric-лога для UI. */
    fun lastLines(n: Int = 100): List<String> {
        val all = ring.toList()
        return if (all.size <= n) all else all.subList(all.size - n, all.size)
    }

    private fun pushRing(line: String) {
        ring.offer(line)
        if (ringSize.incrementAndGet() > MAX_RING) {
            ring.poll()
            ringSize.decrementAndGet()
        }
    }

    /**
     * Дополнительный OkHttp interceptor. Применять как `addInterceptor` —
     * поверх существующих, после AuthSigning/E2E (чтобы видеть финальный
     * URL после rewriting). Не сетевой, тонкий.
     */
    val interceptor: Interceptor = Interceptor { chain ->
        val req = chain.request()
        val urlNoQuery = runCatching {
            val u = req.url
            val q = u.query
            val short = u.scheme + "://" + u.host + u.encodedPath +
                if (q.isNullOrBlank()) "" else "?<${q.length}b>"
            short
        }.getOrDefault(req.url.toString())

        val method = req.method
        val proxyDesc = runCatching {
            CorporateProxy.detect()?.let { "${it.host}:${it.port}" } ?: "direct"
        }.getOrDefault("?")

        val started = System.currentTimeMillis()
        var resp: Response? = null
        try {
            resp = chain.proceed(req)
            val ms = System.currentTimeMillis() - started
            val len = resp.body?.contentLength() ?: -1L
            // Heuristic — если contentLength не известен, оценим по протоколу
            val lenStr = if (len >= 0) "$len" else "?"
            val line = "[net] $method $urlNoQuery → ${resp.code} ${lenStr}b in ${ms}ms via $proxyDesc"
            runCatching { SspiLogger.log(line) }
            pushRing(line)
            return@Interceptor resp
        } catch (t: Throwable) {
            val ms = System.currentTimeMillis() - started
            val line = "[net-ERR] $method $urlNoQuery → ${t.javaClass.simpleName}: ${t.message?.take(120)} in ${ms}ms via $proxyDesc"
            runCatching { SspiLogger.log(line) }
            pushRing(line)
            throw t
        }
    }

    /**
     * Записать кастомное событие в metric-лог (для self-test, для UI клика
     * "запустить диагностику", и т.п.).
     */
    fun event(line: String) {
        val full = "[evt] $line"
        runCatching { SspiLogger.log(full) }
        pushRing(full)
    }
}
