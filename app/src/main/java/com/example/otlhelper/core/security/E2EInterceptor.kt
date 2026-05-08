package com.example.otlhelper.core.security

import android.content.Context
import android.util.Log
import com.example.otlhelper.core.settings.AppSettings
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * §TZ-2.3.25 — E2E encryption interceptor для REST API.
 *
 * Перехватывает **только** POST'ы на корневой path `/` (наш `action`-based
 * API). Аватары (`/avatar/`), медиа (`/media/`) идут как есть — они
 * байты из R2, шифровать E2E = 2× overhead на download.
 *
 * Когда `settings.e2eeEnabled = true`:
 *  1. Читает original plaintext body (JSON).
 *  2. Шифрует через [E2ECrypto.encryptRequest] → binary envelope.
 *  3. Подменяет body и добавляет header `X-OTL-Crypto: v1`.
 *  4. Шлёт запрос. Сервер расшифровывает, обрабатывает, шифрует ответ.
 *  5. Получает response с header `X-OTL-Crypto: v1` + encrypted body.
 *  6. Расшифровывает через [E2ECrypto.decryptResponse] → plaintext JSON.
 *  7. Возвращает подменённый Response с plaintext body и `application/json`.
 *
 * Downstream code (ApiClient) видит обычный JSON — encryption прозрачна.
 *
 * Если decrypt response fails — возвращает оригинальный Response как есть
 * (сервер мог вернуть plaintext error, напр. crypto_decrypt_failed).
 */
class E2EInterceptor(
    private val context: Context,
    private val settings: AppSettings,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()

        // Пропускаем только не-API запросы:
        //  - GET (avatar/, media/, kcef-bundle/, apk/, desktop/)
        //  - POST не на root path (нет таких в коде, но защитная логика)
        // §TZ-2.4.0 — feature flag убран, E2E enforced для каждого POST `/`.
        if (req.method != "POST" || req.url.encodedPath != "/") {
            return chain.proceed(req)
        }

        val plaintext = readBody(req)
            ?: throw java.io.IOException("e2e_no_body_to_encrypt")

        // §TZ-2.4.0 — fail-closed: если crypto не работает (assets corrupt,
        // OOM, etc) — НЕ отправлять plaintext. Лучше IOException → клиент
        // покажет "нет сети" чем утечка payload через VPS.
        val session = try {
            E2ECrypto.encryptRequest(plaintext, E2ECrypto.serverPublicKey(context))
        } catch (e: Throwable) {
            Log.e(TAG, "encryptRequest failed (fail-closed)", e)
            throw java.io.IOException("e2e_encrypt_failed", e)
        }

        val encBody = session.envelope.toRequestBody(CRYPTO_MEDIA)
        val encReq = req.newBuilder()
            .header("X-OTL-Crypto", "v1")
            .post(encBody)
            .build()

        val response = chain.proceed(encReq)

        // Если сервер ответил НЕ crypto (plaintext error, напр. 400
        // crypto_decrypt_failed или 401) — пропускаем как есть.
        if (response.header("X-OTL-Crypto") != "v1") return response

        val encRespBytes = response.body?.bytes() ?: return response
        val plainResp = try {
            E2ECrypto.decryptResponse(encRespBytes, session.responseKey, session.ephemeralPub)
        } catch (e: Throwable) {
            Log.e(TAG, "decryptResponse failed", e)
            // Возвращаем как binary — upstream (ApiClient) получит parse-error,
            // обработает как network error.
            return response.newBuilder()
                .body(encRespBytes.toResponseBody(CRYPTO_MEDIA))
                .build()
        }

        return response.newBuilder()
            .body(plainResp.toResponseBody(JSON_MEDIA))
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
        const val TAG = "E2EInterceptor"
        val CRYPTO_MEDIA = "application/x-otl-crypto".toMediaType()
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
