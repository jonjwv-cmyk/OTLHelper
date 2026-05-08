package com.example.otlhelper.data.network.api

import com.example.otlhelper.shared.api.ApiActions
import com.example.otlhelper.shared.api.ApiFields
import org.json.JSONObject

/**
 * §TZ-CLEANUP-2026-04-25 — auth/session zone: `login`, `logout`, `me`,
 * `change_password`, `heartbeat`, `app_status`.
 *
 * Реализация делегата вынесена в [AuthCallsImpl]. Public API `ApiClient`-а
 * наследует этот интерфейс (см. ApiClient.kt).
 *
 * Шаблон делегирования: каждая зона = `interface XxxCalls` + `internal class
 * XxxCallsImpl(gateway: ApiGateway) : XxxCalls`. Добавление новой зоны
 * (FeedCalls/AdminCalls/SystemCalls/...) — дублируется этот же паттерн.
 */
interface AuthCalls {
    /**
     * Вход в систему.
     * [integrityToken] / [integrityNonce] — необязательные Play Integrity
     * artefacts. Сервер по feature-flag'у `play_integrity_enforced` решает:
     * валидировать их строго или терпимо.
     */
    fun login(
        login: String,
        password: String,
        deviceId: String = "",
        appVersion: String = "",
        integrityToken: String = "",
        integrityNonce: String = "",
    ): JSONObject

    fun logout(): JSONObject

    fun me(): JSONObject

    fun changePassword(oldPassword: String, newPassword: String): JSONObject

    fun heartbeat(appState: String = "foreground"): JSONObject

    fun appStatus(appVersion: String): JSONObject
}

internal class AuthCallsImpl(private val gateway: ApiGateway) : AuthCalls {

    override fun login(
        login: String,
        password: String,
        deviceId: String,
        appVersion: String,
        integrityToken: String,
        integrityNonce: String,
    ): JSONObject = gateway.request(ApiActions.LOGIN) {
        put(ApiFields.LOGIN, login)
        put(ApiFields.PASSWORD, password)
        if (deviceId.isNotBlank()) put(ApiFields.DEVICE_ID, deviceId)
        if (appVersion.isNotBlank()) put(ApiFields.APP_VERSION, appVersion)
        if (integrityToken.isNotBlank()) put(ApiFields.INTEGRITY_TOKEN, integrityToken)
        if (integrityNonce.isNotBlank()) put(ApiFields.INTEGRITY_NONCE, integrityNonce)
    }

    override fun logout(): JSONObject = gateway.request(ApiActions.LOGOUT)

    override fun me(): JSONObject = gateway.request(ApiActions.ME)

    override fun changePassword(oldPassword: String, newPassword: String): JSONObject =
        gateway.request(ApiActions.CHANGE_PASSWORD) {
            put(ApiFields.OLD_PASSWORD, oldPassword)
            put(ApiFields.NEW_PASSWORD, newPassword)
        }

    override fun heartbeat(appState: String): JSONObject =
        gateway.request(ApiActions.HEARTBEAT) {
            put(ApiFields.APP_STATE, appState)
        }

    override fun appStatus(appVersion: String): JSONObject =
        gateway.request(ApiActions.APP_STATUS) {
            put(ApiFields.APP_SCOPE, ApiFields.SCOPE_MAIN)
            put(ApiFields.APP_VERSION, appVersion)
        }
}
