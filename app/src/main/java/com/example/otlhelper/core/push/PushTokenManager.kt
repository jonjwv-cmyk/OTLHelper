package com.example.otlhelper.core.push

import android.content.Context
import android.util.Log
import com.example.otlhelper.ApiClient
import com.example.otlhelper.BuildConfig
import com.example.otlhelper.SessionManager
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over FirebaseMessaging + server registration.
 *
 * - [syncToken] fetches the current FCM token and registers it with the backend.
 *   Safe to call on every app launch — the server upserts by token.
 * - [unregisterCurrentToken] lets the server forget the current device on logout.
 *
 * We stash the last-registered token in SharedPreferences so consecutive launches
 * with an unchanged token skip the network round-trip.
 */
@Singleton
class PushTokenManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SessionManager
) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // Singleton-scoped supervisor — живёт столько же сколько Application. Не
    // использует GlobalScope (у того нет структурированной обработки ошибок
    // и его нельзя отменить). Fire-and-forget запросы регистрации токена
    // переживают пересоздание Activity, но заканчиваются вместе с процессом.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called whenever we want to make sure the server knows this device's FCM
     * token under the current login. Silently returns if the session isn't
     * authenticated — FCM token will be re-synced after login.
     *
     * Fire-and-forget на собственном application-scope supervisor'е — переживает
     * пересоздание Activity, но корректно отменяется при завершении процесса.
     */
    fun syncToken() {
        if (!session.hasToken()) {
            Log.d(TAG, "syncToken: skipped — no session")
            return
        }
        Log.d(TAG, "syncToken: start — login=${session.getLogin()}")
        scope.launch {
            try {
                val fcmToken = FirebaseMessaging.getInstance().token.await()
                if (fcmToken.isBlank()) {
                    Log.w(TAG, "syncToken: FCM returned blank token — Google Play services missing?")
                    return@launch
                }
                Log.d(TAG, "syncToken: got FCM token (len=${fcmToken.length}, prefix=${fcmToken.take(16)}...)")

                val userLogin = session.getLogin()
                val lastKey = "${KEY_LAST_REGISTERED_PREFIX}$userLogin"
                if (prefs.getString(lastKey, null) == fcmToken) {
                    Log.d(TAG, "syncToken: already registered for $userLogin — skipping round-trip")
                    return@launch
                }

                val deviceId = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ).orEmpty()

                // Make sure ApiClient has the current auth token (the Activity
                // might not have wired it yet on very early start-up).
                ApiClient.setAuth(session.getToken(), deviceId)
                Log.d(TAG, "syncToken: calling register_push_token for $userLogin, app=${BuildConfig.VERSION_NAME}")

                val response = ApiClient.registerPushToken(
                    pushToken = fcmToken,
                    platform = "android",
                    deviceId = deviceId,
                    appVersion = BuildConfig.VERSION_NAME
                )
                if (response.optBoolean("ok", false)) {
                    prefs.edit().putString(lastKey, fcmToken).apply()
                    Log.i(TAG, "FCM token registered for $userLogin")
                } else {
                    Log.w(TAG, "FCM register failed: ${response.optString("error")} (full=${response})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "FCM token sync error", e)
            }
        }
    }

    /** Server-side deactivate for the current token. Call before clearing auth. */
    suspend fun unregisterCurrentToken() {
        try {
            val fcmToken = withContext(Dispatchers.IO) {
                FirebaseMessaging.getInstance().token.await()
            }
            if (fcmToken.isNotBlank()) {
                withContext(Dispatchers.IO) { ApiClient.unregisterPushToken(fcmToken) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FCM token unregister error", e)
        } finally {
            // Clear the cached key so a later re-login re-registers fresh.
            prefs.edit().clear().apply()
        }
    }

    /** Called from [OtlFirebaseMessagingService] when FCM hands us a new token. */
    fun onNewToken(token: String) {
        // Wipe caches — a brand-new token needs fresh registration for every user.
        prefs.edit().clear().apply()
        syncToken()
    }

    companion object {
        private const val TAG = "PushTokenManager"
        private const val PREFS = "otl_fcm_prefs"
        private const val KEY_LAST_REGISTERED_PREFIX = "last_registered_"
    }
}
