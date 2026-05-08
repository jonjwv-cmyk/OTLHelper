package com.example.otlhelper.desktop.core.network

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * §TZ-DESKTOP-0.9.3 — Window SSPI authenticator (auto-NTLM/Kerberos через WAFFLE-JNA).
 *
 * **Эволюция от 0.9.1/0.9.2**:
 *   - **Per-route attempt counter** (не через priorResponse, который OkHttp
 *     не сохраняет между CONNECT-ами — отсюда был infinite loop в 0.9.2)
 *   - **Auto-switch Negotiate → NTLM** после 1 failed Negotiate без Type-2
 *     challenge (что мы видели в логе у юзера). NTLM — то что Chrome/Edge
 *     обычно используют когда Kerberos для SPN не зарегистрирован в AD.
 *   - **Marks "exhausted"** когда обе схемы не справились — следующий
 *     authenticator в chain ([JcifsNtlmAuthenticator]) подхватывает
 *     manual-creds path
 *
 * **Возвращает null** если все SSPI варианты исчерпаны → OkHttp передаёт
 * managment следующему authenticator в [CompositeProxyAuthenticator].
 */
class WindowsSspiAuthenticator(
    private val proxyHost: String,
    private val spnCandidates: List<String> = listOf(proxyHost),
) : Authenticator {

    /** Per-route state: какая схема активна + сколько попыток + контекст. */
    private data class State(
        var scheme: String = "Negotiate",
        var attempts: Int = 0,
        var ctx: waffle.windows.auth.IWindowsSecurityContext? = null,
        var exhausted: Boolean = false,
        var spnUsed: String? = null,
    )

    private val states = ConcurrentHashMap<String, State>()

    /** Проверка: исчерпались ли все SSPI варианты для этого route? */
    fun isExhausted(route: Route?): Boolean =
        states[routeKey(route)]?.exhausted == true

    /** Сброс — например после смены creds или ручного retry. */
    fun resetAll() {
        states.values.forEach { it.ctx?.runCatching { dispose() } }
        states.clear()
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (!CorporateProxy.isWindows) {
            SspiLogger.log("WindowsSspi: skipping — not Windows OS")
            return null
        }

        val routeKey = routeKey(route)
        val state = states.computeIfAbsent(routeKey) { State() }

        // §TZ-DESKTOP-0.9.9 — Reset per-request: OkHttp вызывает authenticator
        // на каждом 407. Если response.priorResponse == null — это первый 407
        // в новом request'е (новый CONNECT-tunnel для нового HTTP-запроса).
        // Сбрасываем state чтобы каждый новый запрос начинал auth-flow с нуля
        // (Kerberos token надо генерировать заново для каждого CONNECT).
        // Без этого 0.9.8 после первого successful auth начинал считать
        // attempts > 1 на втором request'е → ложно объявлял exhausted →
        // показывал manual creds dialog → блокировал UI.
        if (response.priorResponse == null && (state.attempts > 0 || state.exhausted)) {
            SspiLogger.log("WindowsSspi: new HTTP request detected (priorResponse=null), resetting state")
            state.ctx?.runCatching { dispose() }
            state.ctx = null
            state.attempts = 0
            state.exhausted = false
            state.scheme = "Negotiate"
            state.spnUsed = null
        }

        // Если уже исчерпали в текущем flow — отдаём null чтобы Composite вызвал JcifsNtlm
        if (state.exhausted) {
            SspiLogger.log("WindowsSspi: state.exhausted=true, deferring to next authenticator")
            return null
        }

        val proxyAuthHeaders = response.headers("Proxy-Authenticate")
        if (proxyAuthHeaders.isEmpty()) {
            SspiLogger.log("WindowsSspi: no Proxy-Authenticate, abort")
            return null
        }

        // Сначала проверяем что прокси advertises поддерживаемую схему.
        val advertisesNegotiate = proxyAuthHeaders.any { it.trim().startsWith("Negotiate", ignoreCase = true) }
        val advertisesNtlm = proxyAuthHeaders.any { it.trim().startsWith("NTLM", ignoreCase = true) }
        if (!advertisesNegotiate && !advertisesNtlm) {
            SspiLogger.log("WindowsSspi: no supported scheme in $proxyAuthHeaders, abort")
            return null
        }

        // Извлекаем challenge для текущей схемы (если есть).
        val schemeHeader = proxyAuthHeaders.firstOrNull { it.trim().startsWith(state.scheme, ignoreCase = true) }
        val challenge = schemeHeader?.let { extractToken(it) }
        state.attempts++

        SspiLogger.log("WindowsSspi: route=$routeKey, scheme=${state.scheme}, attempt=${state.attempts}, challenge=${challenge?.size ?: "null"} bytes")

        // Эвристика переключения схемы:
        // Negotiate failed — token sent, proxy returned bare "Negotiate" again
        // (challenge = null после первой attempt-1) ⇒ Kerberos для SPN не работает,
        // переключаемся на NTLM.
        if (state.scheme == "Negotiate" && state.attempts > 1 && challenge == null && advertisesNtlm) {
            SspiLogger.log("WindowsSspi: Negotiate not progressing — switching to NTLM scheme")
            state.ctx?.runCatching { dispose() }
            state.ctx = null
            state.scheme = "NTLM"
            state.attempts = 1
            // Continue with NTLM scheme below.
        } else if (state.scheme == "Negotiate" && state.attempts > 1 && challenge == null && !advertisesNtlm) {
            // Negotiate doesn't progress AND no NTLM advertised → exhausted.
            SspiLogger.log("WindowsSspi: Negotiate stuck and NTLM not advertised — exhausted")
            state.exhausted = true
            state.ctx?.runCatching { dispose() }
            state.ctx = null
            return null
        }

        // Hard limit on total attempts (защита от любых infinite-loop edge cases).
        if (state.attempts > 4) {
            SspiLogger.log("WindowsSspi: max attempts reached for ${state.scheme} — marking exhausted")
            state.exhausted = true
            state.ctx?.runCatching { dispose() }
            state.ctx = null
            return null
        }

        return try {
            val ctx = state.ctx
            val newCtx = if (challenge == null || ctx == null) {
                // §TZ-DESKTOP-0.9.7 — Initial step. Перебираем SPN-кандидаты,
                // ищем тот что даёт **настоящий** SPNEGO с Kerberos AP-REQ
                // (token > 100 байт, начинается с 0x60 = ASN.1 APPLICATION 0).
                // Если все candidates degenerate (40b raw NTLM) — берём первый.
                ctx?.runCatching { dispose() }
                pickBestContext(state.scheme, state).also {
                    SspiLogger.log("WindowsSspi: best context picked, spn=${state.spnUsed}, token=${it.token?.size ?: 0}b, isContinue=${it.isContinue}")
                }
            } else {
                // Continuation step.
                SspiLogger.log("WindowsSspi: continuing existing context with Type-2 (${challenge.size}b)")
                val secBuf = com.sun.jna.platform.win32.SspiUtil.ManagedSecBufferDesc(
                    com.sun.jna.platform.win32.Sspi.SECBUFFER_TOKEN,
                    challenge,
                )
                // Используем тот SPN под который контекст создан изначально.
                val spn = state.spnUsed ?: "HTTP/$proxyHost"
                ctx.initialize(ctx.handle, secBuf, spn)
                SspiLogger.log("WindowsSspi: continuation token=${ctx.token?.size ?: 0}b, isContinue=${ctx.isContinue}")
                ctx
            }

            val token = newCtx.token
            if (token == null || token.isEmpty()) {
                SspiLogger.log("WindowsSspi: empty token — marking exhausted")
                newCtx.runCatching { dispose() }
                state.ctx = null
                state.exhausted = true
                return null
            }

            // §TZ-DESKTOP-0.9.6 — hex dump для diagnostic. Если token > ~200 байт
            // и начинается с 0x60 (ASN.1 APPLICATION 0) — это валидный SPNEGO с
            // Kerberos AP-REQ или NTLM Type-1 внутри. Если 40 байт — degenerate.
            SspiLogger.log("WindowsSspi: token first 16 bytes hex = ${token.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }}")

            state.ctx = newCtx

            // Send under whatever scheme proxy advertises (Squid обычно accepts
            // raw NTLM under Negotiate header tag).
            val sendScheme = if (advertisesNegotiate) "Negotiate" else state.scheme
            val tokenB64 = Base64.getEncoder().encodeToString(token)
            SspiLogger.log("WindowsSspi: sending Proxy-Authorization: $sendScheme <${token.size}b>")
            response.request.newBuilder()
                .header("Proxy-Authorization", "$sendScheme $tokenB64")
                .build()
        } catch (t: Throwable) {
            SspiLogger.log("WindowsSspi: EXCEPTION ${t.javaClass.name}: ${t.message}")
            t.stackTrace.take(6).forEach { SspiLogger.log("  at $it") }
            state.ctx?.runCatching { dispose() }
            state.ctx = null
            // Если на NTLM упало — exhausted; на Negotiate — попробуем ещё switch.
            if (state.scheme == "NTLM") {
                state.exhausted = true
            }
            null
        }
    }

    private fun routeKey(route: Route?): String =
        route?.proxy?.toString() ?: "default"

    private fun extractToken(authHeader: String): ByteArray? {
        val parts = authHeader.trim().split(" ", limit = 2)
        if (parts.size < 2) return null
        return runCatching { Base64.getDecoder().decode(parts[1].trim()) }.getOrNull()
    }

    /**
     * §TZ-DESKTOP-0.9.7 — Перебирает [spnCandidates], для каждого создаёт
     * SSPI-context и проверяет каков получился token. Возвращает context с
     * "лучшим" токеном:
     *   - Идеал: token >100b, начинается с 0x60 (SPNEGO+Kerberos AP-REQ) →
     *     это значит SSPI нашёл Kerberos-ticket в кэше для этого SPN.
     *   - OK: token >100b, начинается с 0x4e ('N' от NTLMSSP) — SPNEGO с
     *     NTLM submech (Kerberos не нашёл, fallback NTLM).
     *   - Плохо: token=40b — degenerate; пробуем следующий candidate.
     *
     * Если все плохие — возвращаем первый (хоть что-то).
     */
    private fun pickBestContext(scheme: String, state: State): waffle.windows.auth.IWindowsSecurityContext {
        val candidates = spnCandidates.distinct().ifEmpty { listOf(proxyHost) }
        var best: waffle.windows.auth.IWindowsSecurityContext? = null
        var bestSpn: String? = null
        var bestScore = -1
        for (host in candidates) {
            val spn = "HTTP/$host"
            val ctx = try {
                waffle.windows.auth.impl.WindowsSecurityContextImpl.getCurrent(scheme, spn)
            } catch (t: Throwable) {
                SspiLogger.log("WindowsSspi: pickBestContext($spn) exception: ${t.message}")
                continue
            }
            val token = ctx.token ?: ByteArray(0)
            val firstByte = token.firstOrNull()?.toInt()?.and(0xff) ?: 0
            // Score: SPNEGO+Kerberos (0x60, big) > SPNEGO/NTLM (any big) > raw NTLM > tiny degenerate
            val score = when {
                token.size > 100 && firstByte == 0x60 -> 100
                token.size > 100 -> 50
                token.size > 40 -> 20
                else -> 0
            }
            val hex = token.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            SspiLogger.log("WindowsSspi: pickBestContext spn=$spn token=${token.size}b first16=$hex score=$score")
            if (score > bestScore) {
                best?.runCatching { dispose() }
                best = ctx
                bestSpn = spn
                bestScore = score
            } else {
                ctx.runCatching { dispose() }
            }
        }
        state.spnUsed = bestSpn
        SspiLogger.log("WindowsSspi: pickBestContext WINNER spn=$bestSpn score=$bestScore")
        return best ?: waffle.windows.auth.impl.WindowsSecurityContextImpl.getCurrent(scheme, "HTTP/$proxyHost")
    }
}
