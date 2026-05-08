package com.example.otlhelper.desktop.core.network

import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Secur32
import com.sun.jna.platform.win32.Sspi
import com.sun.jna.platform.win32.SspiUtil
import com.sun.jna.platform.win32.W32Errors
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.ptr.IntByReference
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import waffle.windows.auth.IWindowsCredentialsHandle
import waffle.windows.auth.impl.WindowsSecurityContextImpl
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * §TZ-DESKTOP-0.9.4 — SPNEGO/Negotiate authenticator с **explicit** Windows
 * credentials (user-provided через [ProxyCredsStore]).
 *
 * **Зачем не WAFFLE и не jcifs-ng**:
 *   - WAFFLE.getCurrent() — использует current Windows logon session, у нас
 *     Kerberos для SPN HTTP/proxy не работает → degenerate 40-byte SPNEGO.
 *   - jcifs-ng — генерит **raw NTLM**, не обёрнутый в SPNEGO. Squid с
 *     `Proxy-Authenticate: Negotiate` принимает только SPNEGO-формат.
 *
 * **Что делает этот класс**:
 *   Через JNA Sspi напрямую вызывает `AcquireCredentialsHandle` с
 *   `SEC_WINNT_AUTH_IDENTITY` (User+Domain+Password) → получает credHandle
 *   привязанный к explicit creds. Затем `WindowsSecurityContextImpl` (через
 *   `setCredentialsHandle(IWindowsCredentialsHandle)`) генерит **SPNEGO-
 *   обёрнутый NTLM Type-1** с правильной NTLM-подписью для НАШИХ creds (а
 *   не current logon).
 *
 * Это **точно тот же путь** который браузер использует когда юзер вводит
 * пароль в proxy auth-prompt.
 */
class ExplicitNegotiateAuthenticator(
    private val proxyHost: String,
) : Authenticator {

    private data class State(
        var attempts: Int = 0,
        var credHandle: ExplicitCredentialsHandle? = null,
        var ctx: waffle.windows.auth.IWindowsSecurityContext? = null,
        var creds: ProxyCredentials? = null,
        var rejected: Boolean = false,
    )

    private val states = ConcurrentHashMap<String, State>()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (!CorporateProxy.isWindows) {
            SspiLogger.log("ExplicitNegotiate: skipping — not Windows OS")
            return null
        }

        val routeKey = route?.proxy?.toString() ?: "default"
        val state = states.computeIfAbsent(routeKey) { State() }

        if (state.rejected) {
            SspiLogger.log("ExplicitNegotiate: state.rejected=true (creds wrong); will re-prompt")
            // Очищаем кэш creds и просим заново
            state.dispose()
            states.remove(routeKey)
            ProxyCredsStore.clear()
            return null
        }

        val proxyAuthHeaders = response.headers("Proxy-Authenticate")
        if (proxyAuthHeaders.isEmpty()) {
            SspiLogger.log("ExplicitNegotiate: no Proxy-Authenticate, abort")
            return null
        }

        // Извлекаем challenge из Negotiate (если есть continuation Type-2)
        val negotiateHeader = proxyAuthHeaders.firstOrNull {
            it.trim().startsWith("Negotiate", ignoreCase = true)
        }
        if (negotiateHeader == null) {
            SspiLogger.log("ExplicitNegotiate: no Negotiate scheme in headers $proxyAuthHeaders")
            return null
        }
        val challenge = extractToken(negotiateHeader)
        state.attempts++

        SspiLogger.log("ExplicitNegotiate: attempt #${state.attempts}, challenge=${challenge?.size ?: "null"} bytes")

        // Если Type-1 уже отправлен и Type-2 пришёл — продолжаем context.
        // Если же Type-1 отправили, а в ответ снова "Negotiate" без challenge —
        // прокси отверг наши creds → mark rejected, юзер увидит prompt снова.
        if (state.attempts >= 2 && challenge == null) {
            SspiLogger.log("ExplicitNegotiate: proxy rejected our token (no Type-2 challenge after Type-1) — clearing creds")
            state.dispose()
            state.rejected = true
            // На следующем authenticate() вызове сработает rejected branch выше.
            // Здесь сразу возвращаем null чтобы Composite перешёл дальше или OkHttp отдал 407 наверх.
            states.remove(routeKey)
            ProxyCredsStore.clear()
            return null
        }

        // Hard limit от runaway loop
        if (state.attempts > 4) {
            SspiLogger.log("ExplicitNegotiate: max attempts ${state.attempts} — abort")
            state.dispose()
            states.remove(routeKey)
            return null
        }

        // На первой attempt получаем creds (показываем диалог если ещё нет)
        if (state.creds == null) {
            val c = ProxyCredsStore.requestCreds("$proxyHost:3128") ?: run {
                SspiLogger.log("ExplicitNegotiate: no creds (user cancelled)")
                return null
            }
            state.creds = c
        }
        val creds = state.creds!!
        val (domain, user) = creds.parsedDomainAndUser()
        SspiLogger.log("ExplicitNegotiate: using creds (domain=$domain, user=$user)")

        return try {
            val spn = "HTTP/$proxyHost"
            val ctx = state.ctx
            val newCtx = if (challenge == null || ctx == null) {
                // Initial step: acquire explicit creds + build SPNEGO Type-1.
                state.ctx?.runCatching { dispose() }
                state.credHandle?.runCatching { dispose() }

                val credHandle = ExplicitCredentialsHandle(
                    user = user,
                    domain = domain,
                    password = creds.password,
                    securityPackage = "Negotiate",
                )
                credHandle.initialize()
                state.credHandle = credHandle
                SspiLogger.log("ExplicitNegotiate: acquired credHandle for $domain\\$user")

                val sec = WindowsSecurityContextImpl()
                sec.setSecurityPackage("Negotiate")
                sec.setPrincipalName(spn)
                sec.setCredentialsHandle(credHandle)
                sec.initialize(null, null, spn)
                SspiLogger.log("ExplicitNegotiate: built initial context, token=${sec.token?.size ?: 0}b, isContinue=${sec.isContinue}")
                sec
            } else {
                // Continuation: feed Type-2 to context, get Type-3.
                SspiLogger.log("ExplicitNegotiate: continuing with Type-2 (${challenge.size}b)")
                val secBuf = SspiUtil.ManagedSecBufferDesc(Sspi.SECBUFFER_TOKEN, challenge)
                ctx.initialize(ctx.handle, secBuf, spn)
                SspiLogger.log("ExplicitNegotiate: continuation token=${ctx.token?.size ?: 0}b, isContinue=${ctx.isContinue}")
                ctx
            }

            val token = newCtx.token
            if (token == null || token.isEmpty()) {
                SspiLogger.log("ExplicitNegotiate: empty token — marking rejected")
                state.dispose()
                state.rejected = true
                return null
            }
            // §TZ-DESKTOP-0.9.6 — hex dump для diagnostic.
            SspiLogger.log("ExplicitNegotiate: token first 16 bytes hex = ${token.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }}")

            state.ctx = newCtx
            val tokenB64 = Base64.getEncoder().encodeToString(token)
            SspiLogger.log("ExplicitNegotiate: sending Proxy-Authorization: Negotiate <${token.size}b>")
            response.request.newBuilder()
                .header("Proxy-Authorization", "Negotiate $tokenB64")
                .build()
        } catch (t: Throwable) {
            SspiLogger.log("ExplicitNegotiate: EXCEPTION ${t.javaClass.name}: ${t.message}")
            t.stackTrace.take(6).forEach { SspiLogger.log("  at $it") }
            state.dispose()
            state.rejected = true
            null
        }
    }

    private fun State.dispose() {
        ctx?.runCatching { dispose() }
        ctx = null
        credHandle?.runCatching { dispose() }
        credHandle = null
    }

    private fun extractToken(authHeader: String): ByteArray? {
        val parts = authHeader.trim().split(" ", limit = 2)
        if (parts.size < 2) return null
        return runCatching { Base64.getDecoder().decode(parts[1].trim()) }.getOrNull()
    }
}

/**
 * §TZ-DESKTOP-0.9.4 — Кастомный [IWindowsCredentialsHandle] который
 * acquires SSPI credentials handle с **explicit** username/domain/password
 * через `SEC_WINNT_AUTH_IDENTITY` (вместо current Windows session).
 *
 * Подсовывается в `WindowsSecurityContextImpl.setCredentialsHandle` —
 * WAFFLE далее работает обычным образом, но на наших creds.
 */
class ExplicitCredentialsHandle(
    private val user: String,
    private val domain: String,
    private val password: String,
    private val securityPackage: String,
) : IWindowsCredentialsHandle {

    private val handle = Sspi.CredHandle()
    @Volatile
    private var initialized = false

    override fun initialize() {
        // §TZ-DESKTOP-0.9.5 — критический фикс. JNA `Sspi.SEC_WINNT_AUTH_IDENTITY`
        // имеет `String` поля, которые JNA сериализует **ANSI-кодировкой**.
        // Windows-API при флаге UNICODE=0x2 читает строки **UTF-16LE** —
        // несовпадение → SSPI получает мусор вместо username/password →
        // фоллбэк на анонимные creds → degenerate 40b SPNEGO-токен (мы это
        // увидели в 0.9.4 логе). Решается через [WideAuthIdentity] с
        // `WString` полями, которые JNA нативно конвертит в `wchar_t*`.
        val identity = WideAuthIdentity().apply {
            User = WString(user)
            UserLength = user.length
            Domain = WString(domain)
            DomainLength = domain.length
            Password = WString(password)
            PasswordLength = password.length
            Flags = SEC_WINNT_AUTH_IDENTITY_UNICODE
            write()
        }
        val expiry = Sspi.TimeStamp()
        val rc = Secur32.INSTANCE.AcquireCredentialsHandle(
            null,
            securityPackage,
            Sspi.SECPKG_CRED_OUTBOUND,
            null,
            identity.pointer,
            null,
            null,
            handle,
            expiry,
        )
        if (rc != W32Errors.SEC_E_OK) {
            throw Win32Exception(rc)
        }
        initialized = true
    }

    override fun getHandle(): Sspi.CredHandle = handle

    override fun dispose() {
        if (initialized) {
            runCatching { Secur32.INSTANCE.FreeCredentialsHandle(handle) }
            initialized = false
        }
    }

    companion object {
        /** Flag для SEC_WINNT_AUTH_IDENTITY: strings are Unicode (Windows native). */
        const val SEC_WINNT_AUTH_IDENTITY_UNICODE = 0x2
    }
}

/**
 * §TZ-DESKTOP-0.9.5 — Wide-char версия SEC_WINNT_AUTH_IDENTITY_W.
 *
 * Windows API `AcquireCredentialsHandleW` ожидает `LPWSTR` (wchar_t*) для
 * User/Domain/Password когда `Flags` имеет UNICODE-bit. JNA `WString`
 * автоматически конвертит Java String в native `wchar_t*` (UTF-16LE на
 * Windows) — то что нужно. JNA `Sspi.SEC_WINNT_AUTH_IDENTITY` использует
 * `String` (ANSI-кодировку), что даёт garbage при чтении как UTF-16.
 *
 * Lengths — в **символах** (не байтах) per Microsoft docs.
 */
class WideAuthIdentity : Structure() {
    @JvmField var User: WString? = null
    @JvmField var UserLength: Int = 0
    @JvmField var Domain: WString? = null
    @JvmField var DomainLength: Int = 0
    @JvmField var Password: WString? = null
    @JvmField var PasswordLength: Int = 0
    @JvmField var Flags: Int = 0

    override fun getFieldOrder(): List<String> = listOf(
        "User", "UserLength", "Domain", "DomainLength",
        "Password", "PasswordLength", "Flags",
    )
}
