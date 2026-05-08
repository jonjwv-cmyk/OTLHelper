package com.example.otlhelper.data.network.api

import org.json.JSONObject

/**
 * §TZ-0.10.5 — Android-side зона PC-session lifecycle:
 *   - redeem QR challenge (создать PC сессию для текущего юзера)
 *   - list активных PC-сессий (для UI ActivePcSessionsScreen)
 *   - revoke (прервать с phone'а)
 *   - reset_password_login_counter (developer/superadmin only)
 */
interface PcSessionCalls {
    /** Redeem challenge — создаёт PC-сессию на сервере для текущего auth юзера. */
    fun redeemQrToken(challenge: String): JSONObject

    /** Список активных PC-сессий (own или target_login для admin). */
    fun listPcSessions(targetLogin: String? = null): JSONObject

    /** Прервать конкретную PC-сессию. */
    fun revokePcSession(sessionId: String): JSONObject

    /** Сбросить лимит парольных входов (developer/superadmin only). */
    fun resetPasswordLoginCounter(targetLogin: String): JSONObject
}

internal class PcSessionCallsImpl(private val gateway: ApiGateway) : PcSessionCalls {
    override fun redeemQrToken(challenge: String): JSONObject = gateway.request("redeem_qr_token") {
        put("challenge", challenge)
    }

    override fun listPcSessions(targetLogin: String?): JSONObject = gateway.request("list_pc_sessions") {
        if (!targetLogin.isNullOrBlank()) put("target_login", targetLogin)
    }

    override fun revokePcSession(sessionId: String): JSONObject = gateway.request("revoke_pc_session") {
        put("session_id", sessionId)
    }

    override fun resetPasswordLoginCounter(targetLogin: String): JSONObject =
        gateway.request("reset_password_login_counter") {
            put("target_login", targetLogin)
        }
}
