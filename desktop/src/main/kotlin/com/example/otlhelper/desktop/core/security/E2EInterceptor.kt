package com.example.otlhelper.desktop.core.security

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * §TZ-2.4.0 — desktop mirror of `app/.../core/security/E2EInterceptor.kt`.
 *
 * E2E enforced (no plaintext fallback): любой POST на root `/` шифруется
 * через [E2ECrypto], добавляется header `X-OTL-Crypto: v1`, body становится
 * binary envelope. Server отвечает encrypted; клиент расшифровывает обратно
 * в plaintext JSON.
 *
 * Если crypto fail — IOException (fail-closed), не утечка plaintext через
 * VPS.
 *
 * Должен быть подключён ПОСЛЕ AuthSigningInterceptor (тот подписывает
 * plaintext + embed token в body, потом E2E шифрует). См. порядок в
 * [HttpClientFactory.rest].
 */
class E2EInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()

        // Только action-based POST на root path. GET /avatar, /media и т.д.
        // — без encryption (это бинарные ассеты из R2).
        if (req.method != "POST" || req.url.encodedPath != "/api") {
            // §TZ-2.4.0 — desktop API path = "/api" (см. Secrets.BASE_URL+"/api"),
            // в отличие от Android (POST на "/"). Сохраняем семантику.
            return chain.proceed(req)
        }

        val plaintext = readBody(req)
            ?: throw java.io.IOException("e2e_no_body_to_encrypt")

        val session = try {
            E2ECrypto.encryptRequest(plaintext, E2ECrypto.serverPublicKey())
        } catch (e: Throwable) {
            throw java.io.IOException("e2e_encrypt_failed", e)
        }

        val encReq = req.newBuilder()
            .header("X-OTL-Crypto", "v1")
            .post(session.envelope.toRequestBody(CRYPTO_MEDIA))
            .build()

        val response = chain.proceed(encReq)

        // Server мог ответить plaintext error (HTTP 426 / 401 / 5xx) если
        // что-то пошло не так — пропускаем как есть.
        if (response.header("X-OTL-Crypto") != "v1") return response

        val encBytes = response.body?.bytes() ?: return response
        val plainBytes = try {
            E2ECrypto.decryptResponse(encBytes, session.responseKey, session.ephemeralPub)
        } catch (e: Throwable) {
            // Decryption failed — возвращаем как binary, upstream получит
            // parse-error и обработает как сетевую ошибку.
            return response.newBuilder()
                .body(encBytes.toResponseBody(CRYPTO_MEDIA))
                .build()
        }

        return response.newBuilder()
            .body(plainBytes.toResponseBody(JSON_MEDIA))
            .removeHeader("X-OTL-Crypto")
            .header("Content-Type", "application/json")
            .build()
    }

    private fun readBody(req: Request): ByteArray? {
        val body = req.body ?: return null
        return Buffer().use { buf ->
            body.writeTo(buf)
            buf.readByteArray()
        }
    }

    private companion object {
        val CRYPTO_MEDIA = "application/x-otl-crypto".toMediaType()
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
