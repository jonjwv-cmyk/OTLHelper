package com.example.otlhelper.desktop.core.network

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * §TZ-DESKTOP-0.9.3 — Orchestrator который пробует authenticator'ы по очереди.
 *
 *   1. [WindowsSspiAuthenticator] — auto-NTLM/Kerberos через Windows SSPI
 *      (browser-style, без UI). 99% случаев должно сработать на доменной машине.
 *   2. [JcifsNtlmAuthenticator] — explicit NTLM с user-provided creds через
 *      [ProxyCredsStore] (показывает диалог с domain\login + password).
 *      Срабатывает когда SSPI исчерпан.
 *
 * OkHttp вызывает authenticate() на каждом 407 — мы делегируем в active
 * authenticator пока он не вернёт not-null или не пометится exhausted.
 *
 * Преимущество цепочки: если у юзера сломан Kerberos, но он знает свой
 * Windows-пароль — введёт в диалог и всё заработает. Если домен-юзер с
 * рабочей SSPI — авто, без UI.
 */
class CompositeProxyAuthenticator(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val spnCandidates: List<String> = listOf(proxyHost),
) : Authenticator {

    private val sspi = WindowsSspiAuthenticator(proxyHost = proxyHost, spnCandidates = spnCandidates)
    private val explicitNeg = ExplicitNegotiateAuthenticator(proxyHost = proxyHost)
    private val jcifs = JcifsNtlmAuthenticator(proxyHost = proxyHost, proxyPort = proxyPort)

    override fun authenticate(route: Route?, response: Response): Request? {
        // 1. Try auto SSPI (Negotiate/NTLM с current Windows logon).
        if (!sspi.isExhausted(route)) {
            val req = sspi.authenticate(route, response)
            if (req != null) {
                SspiLogger.log("Composite: SSPI (auto) handled the challenge")
                return req
            }
        }

        // 2. SSPI auto не справился → пробуем SPNEGO с explicit creds (диалог).
        // Это правильный путь когда Squid требует Negotiate (а не NTLM) с
        // user-provided creds. Браузерный путь "юзер ввёл пароль в prompt".
        SspiLogger.log("Composite: SSPI auto failed, trying ExplicitNegotiate (SPNEGO + manual creds)")
        val req = explicitNeg.authenticate(route, response)
        if (req != null) {
            SspiLogger.log("Composite: ExplicitNegotiate handled the challenge")
            return req
        }

        // 3. Last resort — raw NTLM через jcifs-ng (для прокси advertise NTLM напрямую).
        SspiLogger.log("Composite: ExplicitNegotiate failed, last-resort JcifsNtlm")
        return jcifs.authenticate(route, response)
    }
}
