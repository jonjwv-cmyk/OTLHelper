package com.example.otlhelper.desktop.core.network

import jcifs.ntlmssp.Type1Message
import jcifs.ntlmssp.Type2Message
import jcifs.ntlmssp.Type3Message
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * §TZ-DESKTOP-0.9.3 — Explicit NTLM auth с user-provided creds (через
 * [ProxyCredsStore]). Использует **jcifs-ng** для криптографически
 * правильной NTLMv2 Type-1/Type-2/Type-3 цепочки.
 *
 * Работает как fallback после [WindowsSspiAuthenticator]:
 *   1. WAFFLE-SSPI пытается auto-NTLM через current Windows session
 *   2. Если фейлит → этот authenticator вызывается
 *   3. Если нет cached creds — требует через [ProxyCredsStore.requestCreds]
 *      (блочит пока юзер не введёт в диалоге)
 *   4. NTLM 3-step handshake: Type-1 (initial), Type-2 (challenge), Type-3 (response)
 *
 * Per-route attempt counter — корректно обрабатывает infinite-loop случай
 * (когда Squid каждый раз возвращает голый `Negotiate` на Type-1 = invalid creds
 * или wrong scheme). После 3 попыток без Type-2 challenge — сдаёмся, чистим
 * кэш creds и вызываем dialog снова (юзер может ошибся).
 */
class JcifsNtlmAuthenticator(
    private val proxyHost: String,
    private val proxyPort: Int,
) : Authenticator {

    /** Per-route state: счётчик попыток + последний sent Type-1. */
    private data class RouteState(var attempts: Int = 0, var workstation: String = computeHostname())

    private val states = ConcurrentHashMap<String, RouteState>()

    override fun authenticate(route: Route?, response: Response): Request? {
        val routeKey = route?.proxy?.toString() ?: "default"
        val state = states.computeIfAbsent(routeKey) { RouteState() }
        state.attempts++

        SspiLogger.log("JcifsNtlm: attempt #${state.attempts} for ${response.request.url.host} via $proxyHost:$proxyPort")

        if (state.attempts > 4) {
            SspiLogger.log("JcifsNtlm: aborting — too many attempts (${state.attempts}). Clearing creds, will re-prompt.")
            states.remove(routeKey)
            ProxyCredsStore.clear()
            return null
        }

        val proxyAuthHeaders = response.headers("Proxy-Authenticate")
        if (proxyAuthHeaders.isEmpty()) {
            SspiLogger.log("JcifsNtlm: no Proxy-Authenticate, abort")
            return null
        }

        // Squid обычно advertises "Negotiate". jcifs шлёт raw NTLM Type-1/3 —
        // Squid принимает их как НТЛМ-в-Negotiate (стандартное поведение).
        // Если есть честный "NTLM" — используем его. Иначе fallback "Negotiate".
        val ntlmHeader = proxyAuthHeaders.firstOrNull { it.trim().startsWith("NTLM", ignoreCase = true) }
        val negotiateHeader = proxyAuthHeaders.firstOrNull { it.trim().startsWith("Negotiate", ignoreCase = true) }
        val authHeader = ntlmHeader ?: negotiateHeader ?: run {
            SspiLogger.log("JcifsNtlm: no NTLM/Negotiate scheme in $proxyAuthHeaders")
            return null
        }
        val responseScheme = authHeader.trim().substringBefore(' ').takeIf { it.isNotEmpty() } ?: "Negotiate"
        val challenge = extractToken(authHeader)
        SspiLogger.log("JcifsNtlm: scheme=$responseScheme, challenge=${challenge?.size ?: "null"} bytes")

        val creds = ProxyCredsStore.requestCreds("$proxyHost:$proxyPort") ?: run {
            SspiLogger.log("JcifsNtlm: no creds available (user cancelled or timeout)")
            return null
        }
        val (domain, user) = creds.parsedDomainAndUser()

        return try {
            val cifsCtx = jcifs.context.SingletonContext.getInstance()
            val tokenOut = if (challenge == null) {
                // Initial: send Type-1 message.
                SspiLogger.log("JcifsNtlm: sending Type-1 (workstation=${state.workstation}, domain=$domain)")
                val flags = Type1Message.getDefaultFlags(cifsCtx) or
                    jcifs.ntlmssp.NtlmFlags.NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY
                val type1 = Type1Message(
                    cifsCtx,
                    flags,
                    domain.takeIf { it.isNotEmpty() },
                    state.workstation,
                )
                type1.toByteArray()
            } else {
                // Continuation: parse Type-2 and emit Type-3 with computed response.
                SspiLogger.log("JcifsNtlm: parsing Type-2 challenge (${challenge.size} bytes)")
                val type2 = Type2Message(challenge)
                SspiLogger.log("JcifsNtlm: Type-2 parsed, target=${type2.target ?: "<none>"}")
                val type3 = Type3Message(
                    cifsCtx,
                    type2,
                    null, // targetName — null = use Type-2 target
                    creds.password,
                    domain,
                    user,
                    state.workstation,
                    type2.flags,
                )
                type3.toByteArray()
            }

            val tokenB64 = Base64.getEncoder().encodeToString(tokenOut)
            SspiLogger.log("JcifsNtlm: sending Proxy-Authorization: $responseScheme <${tokenOut.size}b>")
            response.request.newBuilder()
                .header("Proxy-Authorization", "$responseScheme $tokenB64")
                .build()
        } catch (t: Throwable) {
            SspiLogger.log("JcifsNtlm: EXCEPTION ${t.javaClass.name}: ${t.message}")
            t.stackTrace.take(5).forEach { SspiLogger.log("  at $it") }
            null
        }
    }

    private fun extractToken(authHeader: String): ByteArray? {
        val parts = authHeader.trim().split(" ", limit = 2)
        if (parts.size < 2) return null
        return runCatching { Base64.getDecoder().decode(parts[1].trim()) }.getOrNull()
    }

    companion object {
        private fun computeHostname(): String = runCatching {
            java.net.InetAddress.getLocalHost().hostName
        }.getOrElse { "WORKSTATION" }
    }
}
