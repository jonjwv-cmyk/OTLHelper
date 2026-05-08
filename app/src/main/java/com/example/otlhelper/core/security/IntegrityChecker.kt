package com.example.otlhelper.core.security

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SF-2026 security hardening: Play Integrity API (Google's APK/device attestation).
 *
 * Перед `login` клиент запрашивает у Google подписанный JWT-токен, удостоверяющий:
 *  - Это **подлинный APK** (не patched, не repackaged, не modified).
 *  - Это **настоящий Android-девайс** (не эмулятор, не rooted с SafetyNet fail).
 *  - Это **приложение из Play Store** (или sideload — сервер решает строгость).
 *
 * Токен привязан к одноразовому nonce (anti-replay): клиент генерит nonce = `SHA-256(login|timestamp)`,
 * сервер потом проверяет что nonce в расшифрованном токене совпадает с тем,
 * что прислал клиент.
 *
 * Если токен не получен (нет Google Play Services, офлайн, API quota) — возвращает
 * null. Сервер по feature-flag'у `play_integrity_enforced` решает: принять login
 * без токена (пермиссивный режим) или отклонить (строгий).
 *
 * **KMP future:** Android-only. На desktop будет другая схема:
 *  - macOS: `SecCodeCheckValidity` + codesign notarization
 *  - Windows: Authenticode signature verification
 *  - или просто доверие к управляемому environment'у (корпоративная политика)
 */
@Singleton
class IntegrityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Запрашивает Integrity token у Google Play. Возвращает (token, nonce)
     * или null если что-то пошло не так.
     *
     * Nonce — base64url SHA-256 хеш от [input] — передаётся одновременно в токен
     * (Google его включит в signed payload) и на сервер в поле `integrity_nonce`,
     * чтобы сервер мог верифицировать что токен действительно для этого запроса.
     */
    suspend fun requestToken(input: String): Result? = try {
        val nonce = computeNonce(input)
        val manager = IntegrityManagerFactory.create(context)
        val request = IntegrityTokenRequest.builder()
            .setNonce(nonce)
            // Не указываем cloudProjectNumber — используется Google Play API v1
            // (работает для app'ов из Play Console без отдельного Google Cloud setup).
            // Для v2 (более строгая валидация) — раскомментировать:
            // .setCloudProjectNumber(FIREBASE_PROJECT_NUMBER)
            .build()

        suspendCancellableCoroutine { cont ->
            manager.requestIntegrityToken(request)
                .addOnSuccessListener { response ->
                    val token = response.token()
                    if (token.isNullOrBlank()) cont.resume(null)
                    else cont.resume(Result(token, nonce))
                }
                .addOnFailureListener { err ->
                    Log.w(TAG, "Integrity token failed: ${err.message}")
                    cont.resume(null)
                }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Integrity exception: ${e.message}")
        null
    }

    /** nonce = base64url SHA-256(input). Длина 43 символа — проходит Google-требования 16-500. */
    private fun computeNonce(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return android.util.Base64.encodeToString(
            digest,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
    }

    data class Result(val token: String, val nonce: String)

    companion object {
        private const val TAG = "IntegrityChecker"
    }
}
