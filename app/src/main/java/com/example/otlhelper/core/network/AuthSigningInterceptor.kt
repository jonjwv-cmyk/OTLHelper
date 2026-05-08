package com.example.otlhelper.core.network

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * §TZ-2.3.31 Phase 3a — token confidentiality on TLS termination.
 *
 * Вместо `Authorization: Bearer <token>` header (VPS видит в plaintext при TLS
 * termination) мы внедряем `_auth_token: "Bearer <token>"` в body JSON.
 * E2EInterceptor затем шифрует весь body, включая токен. VPS видит только
 * ephemeral_pub + ciphertext — токен не утекает.
 *
 * HMAC-подпись остаётся в header'ах (`X-Request-Ts`, `X-Request-Sig`), но
 * считается теперь на модифицированном body (с _auth_token внутри) — сервер
 * после decrypt'а получит тот же body и верифицирует подпись.
 *
 * Canonical string format (matches the Cloudflare Worker server verifier):
 *     ts\nACTION\nsha256Hex(body-with-_auth_token)
 *
 * `action` is taken from the JSON body's top-level `action` field.
 *
 * ## Clock-skew recovery (§ SF-2026 reliability)
 *
 * Server tolerates ±300s drift between client and server clocks. On Android
 * devices with factory-reset default time, dual-SIM clock shifts, or
 * accidentally-manual clock, client ts can drift arbitrarily far — every
 * signed call returns `request_expired` and the app silently "doesn't work".
 *
 * We detect this and self-heal:
 *  - On a `401 request_expired` response, parse the server's `Date` response
 *    header → compute `serverNow - clientNow = offset` and cache it.
 *  - Retry the SAME request once with the corrected timestamp.
 *  - Subsequent requests use the cached offset until the app restarts.
 *
 * [clockOffsetSeconds] is process-wide (AtomicLong) so every intercept call
 * benefits from the learned offset — we only "pay" for the failed call
 * once per session, not once per user action.
 */
class AuthSigningInterceptor(
    private val tokenProvider: () -> String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = tokenProvider()
        if (token.isBlank()) return chain.proceed(original)

        val bodyString = original.readBodyUtf8()
        val action = runCatching { JSONObject(bodyString).optString("action", "") }
            .getOrDefault("")
        // §TZ-2.3.31 Phase 3a — embed token into body for signed requests.
        // For GETs (empty body) bodyWithToken == bodyString (no-op).
        val bodyWithToken = embedAuthToken(bodyString, token)

        val firstAttempt = signedCopy(original, token, bodyWithToken, action, clockOffsetSeconds.get())
        val response = chain.proceed(firstAttempt)

        // 401 request_expired → learn offset from server Date header + retry once.
        // Only triggers when token is valid but ts drifted → server already
        // returned our body, safe to close and re-issue the same call.
        if (response.code != 401) return response

        val newOffset = learnOffsetFromResponse(response) ?: return response
        // Only retry if the new offset materially differs from what we already had
        // — prevents infinite retry loop if server Date header is bogus.
        val prev = clockOffsetSeconds.getAndSet(newOffset)
        if (kotlin.math.abs(newOffset - prev) < 5) return response

        response.close()
        val retry = signedCopy(original, token, bodyWithToken, action, newOffset)
        return chain.proceed(retry)
    }

    /**
     * Возвращает bodyString с добавленным `_auth_token` полем. Если body не
     * JSON или пустой — возвращает как есть. Токен хранится в зашифрованной
     * полезной нагрузке (E2EInterceptor ловит его позже по цепочке).
     */
    private fun embedAuthToken(bodyString: String, token: String): String {
        if (bodyString.isBlank()) return bodyString
        return runCatching {
            JSONObject(bodyString).put("_auth_token", "Bearer $token").toString()
        }.getOrDefault(bodyString)
    }

    private fun signedCopy(
        original: Request,
        token: String,
        bodyString: String,
        action: String,
        offsetSeconds: Long,
    ): Request {
        val ts = (System.currentTimeMillis() / 1000 + offsetSeconds).toString()
        val bodyHash = sha256Hex(bodyString)
        val canonical = "$ts\n$action\n$bodyHash"
        val signature = hmacSha256Hex(token, canonical)
        val builder = original.newBuilder()
            // §TZ-2.3.31 Phase 3a — НЕ шлём Authorization header. Токен уже в
            // body._auth_token (будет encrypted E2EInterceptor'ом).
            .removeHeader("Authorization")
            .header("X-Request-Ts", ts)
            .header("X-Request-Sig", signature)
        // Если body отличается от оригинала (добавили _auth_token) — подменяем.
        // Для GET и пустых body bodyString == original (embedAuthToken no-op).
        val origBodyString = original.readBodyUtf8()
        if (bodyString != origBodyString && original.method.equals("POST", ignoreCase = true)) {
            val mt = original.body?.contentType()
                ?: "application/json; charset=utf-8".toMediaTypeOrNull()
            builder.post(bodyString.toRequestBody(mt))
        }
        return builder.build()
    }

    private fun Request.readBodyUtf8(): String {
        // Peek the body as UTF-8 without consuming it (OkHttp lets us re-send).
        return body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        } ?: ""
    }

    /**
     * Attempts to read the response's `Date` header (always present on CF
     * Workers) and returns the learned offset in seconds, or null if the
     * header is missing / unparseable. Also peeks the body JSON to check that
     * the 401 is specifically `request_expired` — we don't want to change
     * clock offset on unrelated 401 errors (expired token, auth failure).
     */
    private fun learnOffsetFromResponse(response: Response): Long? {
        // Check error code — only react to request_expired / invalid_request_signature.
        val peekedBody = response.peekBody(256).string()
        val looksLikeClockSkew = peekedBody.contains("request_expired") ||
            peekedBody.contains("invalid_request_signature")
        if (!looksLikeClockSkew) return null

        val serverDateHeader = response.header("Date") ?: return null
        val serverInstant = runCatching {
            ZonedDateTime.parse(serverDateHeader, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
        }.getOrNull() ?: return null
        val clientSeconds = System.currentTimeMillis() / 1000
        return serverInstant - clientSeconds
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256Hex(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * Process-wide clock offset (server - client, in seconds). Learned
         * lazily from the first failed signed request after app start.
         * `AtomicLong` because OkHttp interceptors run on the network
         * dispatcher pool and may read/write concurrently.
         */
        private val clockOffsetSeconds = AtomicLong(0)

        /** Reset on logout — new session may have different server. */
        fun resetClockOffset() {
            clockOffsetSeconds.set(0)
        }
    }
}
