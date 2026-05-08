package com.example.otlhelper.data.network.api

import com.example.otlhelper.shared.api.ApiActions
import com.example.otlhelper.shared.api.ApiFields
import org.json.JSONObject

/**
 * §TZ-CLEANUP-2026-04-25 — push-token zone:
 *   • [registerPushToken] — upsert FCM token на сервере (PK по push_token);
 *   • [unregisterPushToken] — деактивировать token при logout/смене device.
 *
 * Реализация делегата вынесена в [PushCallsImpl].
 */
interface PushCalls {
    /**
     * Register this device's FCM push token with the backend.
     * Server action `register_push_token` upserts by `push_token` (PK).
     * Response shape: `{ ok: boolean, error?: string }`.
     */
    fun registerPushToken(
        pushToken: String,
        platform: String = ApiFields.PLATFORM_ANDROID,
        deviceId: String = "",
        appVersion: String = "",
        appScope: String = ApiFields.SCOPE_MAIN,
    ): JSONObject

    /**
     * Deactivate a specific FCM push token on the server.
     * Call before clearing the session on logout so the server stops
     * targeting this device.
     */
    fun unregisterPushToken(pushToken: String): JSONObject
}

internal class PushCallsImpl(private val gateway: ApiGateway) : PushCalls {

    override fun registerPushToken(
        pushToken: String,
        platform: String,
        deviceId: String,
        appVersion: String,
        appScope: String,
    ): JSONObject = gateway.request(ApiActions.REGISTER_PUSH_TOKEN) {
        put(ApiFields.PUSH_TOKEN, pushToken)
        put(ApiFields.PLATFORM, platform)
        if (deviceId.isNotBlank()) put(ApiFields.DEVICE_ID, deviceId)
        if (appVersion.isNotBlank()) put(ApiFields.APP_VERSION, appVersion)
        put(ApiFields.APP_SCOPE, appScope)
    }

    override fun unregisterPushToken(pushToken: String): JSONObject =
        gateway.request(ApiActions.UNREGISTER_PUSH_TOKEN) {
            put(ApiFields.PUSH_TOKEN, pushToken)
        }
}
