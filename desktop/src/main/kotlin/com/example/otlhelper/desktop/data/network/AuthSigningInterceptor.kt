package com.example.otlhelper.desktop.data.network

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
 * §TZ-DESKTOP-0.1.0 этап 4b.2 — HMAC-signing запросов для desktop.
 *
 * Зеркало app/core/network/AuthSigningInterceptor.kt, без E2E-embeddings
 * (на desktop E2E не подключён — подключим в этапе 4c+). Шлёт:
 *   Authorization: Bearer <token>
 *   X-Request-Ts:  <unix_seconds + clockOffset>
 *   X-Request-Sig: hmac_sha256(token, "<ts>\n<action>\n<sha256Hex(body)>")
 *
 * Self-heal clock skew: если сервер вернёт 401 `request_expired` или
 * `invalid_request_signature` с header `Date` — вычисляем offset и
 * retry раз с исправленным ts.
 */
class AuthSigningInterceptor(
    private val tokenProvider: () -> String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = tokenProvider()
        if (token.isBlank()) return chain.proceed(original)

        val bodyString = original.readBodyUtf8()
        val action = runCatching { JSONObject(bodyString).optString("action", "") }
            .getOrDefault("")
        // §TZ-2.4.0 — embed token в body вместо Authorization header.
        // E2EInterceptor дальше шифрует body целиком — токен не утекает на
        // VPS даже при TLS termination.
        val bodyWithToken = embedAuthToken(bodyString, token)

        val signed = signedCopy(original, token, bodyWithToken, action, clockOffset.get())
        val response = chain.proceed(signed)

        if (response.code != 401) return response

        val newOffset = learnOffsetFromResponse(response) ?: return response
        val prev = clockOffset.getAndSet(newOffset)
        if (kotlin.math.abs(newOffset - prev) < 5) return response

        response.close()
        val retry = signedCopy(original, token, bodyWithToken, action, newOffset)
        return chain.proceed(retry)
    }

    /**
     * Adds `_auth_token: "Bearer <token>"` поле в body JSON. Сервер
     * (`server-modular/auth.js`) читает его приоритетно над Authorization
     * header. На non-JSON body (которого у нас нет) — no-op.
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
        offsetSec: Long,
    ): Request {
        val ts = (System.currentTimeMillis() / 1000 + offsetSec).toString()
        val bodyHash = sha256Hex(bodyString)
        val canonical = "$ts\n$action\n$bodyHash"
        val sig = hmacSha256Hex(token, canonical)
        val builder = original.newBuilder()
            // §TZ-2.4.0 — НЕ шлём Authorization header. Токен в body._auth_token,
            // E2EInterceptor шифрует целиком. removeHeader защитный шаг — на
            // случай если кто-то выше по chain'у поставил.
            .removeHeader("Authorization")
            .header("X-Request-Ts", ts)
            .header("X-Request-Sig", sig)
        // Если body поменялся (добавили _auth_token) — заменяем body запроса.
        val origBody = original.readBodyUtf8()
        if (bodyString != origBody && original.method.equals("POST", ignoreCase = true)) {
            val mt = original.body?.contentType()
                ?: "application/json; charset=utf-8".toMediaTypeOrNull()
            builder.post(bodyString.toRequestBody(mt))
        }
        return builder.build()
    }

    private fun Request.readBodyUtf8(): String {
        return body?.let { b ->
            val buf = Buffer()
            b.writeTo(buf)
            buf.readUtf8()
        } ?: ""
    }

    private fun learnOffsetFromResponse(response: Response): Long? {
        val peek = response.peekBody(256).string()
        val isClockIssue = peek.contains("request_expired") ||
            peek.contains("invalid_request_signature")
        if (!isClockIssue) return null
        val dateHeader = response.header("Date") ?: return null
        val serverEpoch = runCatching {
            ZonedDateTime.parse(dateHeader, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
        }.getOrNull() ?: return null
        val clientEpoch = System.currentTimeMillis() / 1000
        return serverEpoch - clientEpoch
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun hmacSha256Hex(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val clockOffset = AtomicLong(0)
        fun resetClockOffset() = clockOffset.set(0)
    }
}
