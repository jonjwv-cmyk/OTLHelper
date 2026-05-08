package com.example.otlhelper.desktop.core.network

import com.example.otlhelper.desktop.BuildInfo

/**
 * §TZ-DESKTOP-0.10.0 — Маршрутизатор URL для media/installer downloads.
 *
 * Проблема: на корпоративной сети с Squid+ICAP OkHttp по дефолту ходит на
 * api.otlhelper.com (CF anycast). Корп Squid throttles CF connections (~500 B/s
 * на аватарках, EXE-installer блокируется по signature scan). VPS direct IP
 * не throttled — DPI на VPS IP пока не настроено — но self-signed cert ловит
 * корпоративный endpoint-protection local interstitial («Переход на домен
 * с недоверенным сертификатом») → юзер видит warning или браузер-process
 * не проходит.
 *
 * Решение: на Win + detected corp-proxy → переписываем URL media/installer
 * на `45-12-239-5.sslip.io` (wildcard DNS service, резолвит в 45.12.239.5).
 * VPS отдаёт Let's Encrypt cert на этот hostname → trusted chain → Касперский
 * не показывает interstitial. nginx proxy_pass'ит на CF Worker как было.
 *
 * **НЕ применяется** к `/api` и `/ws` endpoints. Action calls продолжают идти
 * на api.otlhelper.com (CF anycast) через pinned-cert path с E2E. Безопасность
 * не задета — переписываются ТОЛЬКО публичные media/installer URL'ы.
 *
 * Mac/Linux/direct-mode → no-op (там нет corp-AV проблем).
 *
 * **Kill-switch**: `-Dotl.media.sslip=false` отключает rewriting (для отладки
 * если sslip.io упадёт или LE cert проблемы).
 */
object MediaUrlResolver {

    /**
     * Hostname на VPS с LE-cert. Резолвится через sslip.io в 45.12.239.5.
     * При миграции на vps.otlhelper.com меняется одно значение здесь.
     */
    private const val SSLIP_HOST = "45-12-239-5.sslip.io"

    /**
     * Активна ли перепись URL.
     *
     * Условия:
     *  - Windows (Mac/Linux никогда)
     *  - corp-proxy detected (CorporateProxy.detect() != null)
     *  - kill-switch system property не выставлен в "false"
     *
     * lazy + cached — CorporateProxy.detect внутри тоже cached, повторных
     * PowerShell-запросов не делает.
     */
    val isActive: Boolean by lazy {
        if (!BuildInfo.IS_WINDOWS) {
            SspiLogger.log("MediaUrlResolver: NOT-Windows → inactive")
            return@lazy false
        }
        if (System.getProperty("otl.media.sslip", "true") == "false") {
            SspiLogger.log("MediaUrlResolver: kill-switch -Dotl.media.sslip=false → inactive")
            return@lazy false
        }
        val proxy = runCatching { CorporateProxy.detect() }.getOrNull()
        val active = proxy != null
        SspiLogger.log(
            if (active) "MediaUrlResolver: ACTIVE (proxy=${proxy?.host}:${proxy?.port}, sslip=$SSLIP_HOST)"
            else "MediaUrlResolver: no proxy → inactive (direct VPS path)"
        )
        active
    }

    /**
     * §TZ-DESKTOP-0.10.1 — Возвращает rewritten URL для ВСЕХ /api, /ws, media,
     * /desktop, /apk, /a, /avatar, /kcef-bundle endpoints на api.otlhelper.com
     * или 45.12.239.5 host (если active). Иначе оригинал.
     *
     * Когда active (Win+corp-proxy):
     *   `https://api.otlhelper.com/api` → `https://45-12-239-5.sslip.io/api`
     *   `wss://api.otlhelper.com/ws`    → `wss://45-12-239-5.sslip.io/ws`
     *   `https://api.otlhelper.com/avatar/login` → `https://45-12-239-5.sslip.io/avatar/login`
     *   и т.д.
     *
     * Безопасность:
     *  - Pinning живёт ТОЛЬКО в direct mode (HttpClientFactory.applyProxyOrDirect:
     *    in proxy mode no pinning). Поэтому переписать host безопасно.
     *  - E2E + AuthSigning работают на BODY — URL hostname на них не влияет.
     *    Сервер получает body через nginx proxy_pass с заменённым Host header.
     *  - VPS — слепая труба (видит ciphertext).
     *
     *  Любой exception → возвращаем оригинал (никогда не ломаем request).
     */
    fun resolve(originalUrl: String): String {
        if (!isActive) return originalUrl
        return runCatching {
            val u = java.net.URI.create(originalUrl)
            val host = u.host ?: return@runCatching originalUrl

            val isCfHost = host.equals("api.otlhelper.com", ignoreCase = true)
            val isVpsIp = host == "45.12.239.5"
            if (!isCfHost && !isVpsIp) return@runCatching originalUrl

            // Build new URI with sslip.io host, port=default
            val rewritten = java.net.URI(
                u.scheme,
                u.userInfo,
                SSLIP_HOST,
                if (u.port == 443 || u.port == -1) -1 else u.port,
                u.path,
                u.query,
                u.fragment,
            ).toString()

            SspiLogger.log("MediaUrlResolver: rewrite host=$host path=${u.path} → $SSLIP_HOST")
            rewritten
        }.getOrDefault(originalUrl)
    }

    /** Diagnostic: для UI/log-display. */
    fun describe(): String =
        if (isActive) "active (rewrite to $SSLIP_HOST)" else "inactive (direct URLs)"
}
