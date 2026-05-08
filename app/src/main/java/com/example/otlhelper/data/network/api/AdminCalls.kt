package com.example.otlhelper.data.network.api

import com.example.otlhelper.shared.api.ApiActions
import com.example.otlhelper.shared.api.ApiFields
import org.json.JSONObject

/**
 * §TZ-CLEANUP-2026-04-25 — admin/system zone.
 *
 * Включает всё что обычно требует роль `admin` / `developer`:
 *
 *   • Users CRUD: createUser, getUsers, toggleUser, resetPassword,
 *                 renameUser, changeUserLogin, changeUserRole, deleteUser
 *   • Audit log:  getAuditLog (Phase 12g, requires superadmin/developer)
 *   • Mute/DND:   muteContact, unmuteContact, getMutedContacts, setDndSchedule
 *                 (Phase 12d — пользовательские, но логично рядом с admin'скими)
 *   • System pause: getSystemState, setAppPause, clearAppPause
 *
 * Реализация — в [AdminCallsImpl].
 */
interface AdminCalls {
    // ── Users CRUD ─────────────────────────────────────────────────────

    fun createUser(
        newLogin: String,
        fullName: String,
        password: String,
        role: String,
        mustChangePassword: Boolean = false,
    ): JSONObject

    fun getUsers(): JSONObject

    fun toggleUser(targetLogin: String): JSONObject

    fun resetPassword(targetLogin: String, newPassword: String = ""): JSONObject

    fun renameUser(targetLogin: String, fullName: String): JSONObject

    fun changeUserLogin(targetLogin: String, newLogin: String): JSONObject

    fun changeUserRole(targetLogin: String, newRole: String): JSONObject

    fun deleteUser(targetLogin: String): JSONObject

    // ── Audit log (Phase 12g) ──────────────────────────────────────────

    /**
     * SF-2026 §3.15.a.Ж, Phase 12g: пагинированный лог admin-действий.
     * Все параметры опциональные. Требует роль superadmin/developer на сервере.
     */
    fun getAuditLog(
        actorLogin: String = "",
        action: String = "",
        targetType: String = "",
        fromDate: String = "",
        toDate: String = "",
        limit: Int = 100,
        offset: Int = 0,
    ): JSONObject

    // ── Mute/DND (Phase 12d) ───────────────────────────────────────────

    fun muteContact(targetLogin: String): JSONObject

    fun unmuteContact(targetLogin: String): JSONObject

    fun getMutedContacts(): JSONObject

    /** Сохранить DND-интервал в 'HH:MM' формате. Пусто → выключено. */
    fun setDndSchedule(dndStart: String, dndEnd: String): JSONObject

    // ── System pause / state ───────────────────────────────────────────

    fun getSystemState(appScope: String = ApiFields.SCOPE_MAIN): JSONObject

    fun setAppPause(
        appScope: String = ApiFields.SCOPE_MAIN,
        title: String = "Техническая пауза",
        message: String = "Приложение временно недоступно",
        requireConfirmation: Boolean = false,
        autoWipeAfterHours: Int = 24,
        state: String = ApiFields.APP_STATE_PAUSED,
    ): JSONObject

    fun clearAppPause(appScope: String = ApiFields.SCOPE_MAIN): JSONObject
}

internal class AdminCallsImpl(private val gateway: ApiGateway) : AdminCalls {

    // ── Users CRUD ─────────────────────────────────────────────────────

    override fun createUser(
        newLogin: String,
        fullName: String,
        password: String,
        role: String,
        mustChangePassword: Boolean,
    ): JSONObject = gateway.request(ApiActions.CREATE_USER) {
        put(ApiFields.NEW_LOGIN, newLogin)
        put(ApiFields.FULL_NAME, fullName)
        put(ApiFields.PASSWORD, password)
        put(ApiFields.ROLE, role)
        put(ApiFields.MUST_CHANGE_PASSWORD, mustChangePassword)
    }

    override fun getUsers(): JSONObject = gateway.request(ApiActions.GET_USERS)

    override fun toggleUser(targetLogin: String): JSONObject =
        gateway.request(ApiActions.TOGGLE_USER) { put(ApiFields.TARGET_LOGIN, targetLogin) }

    override fun resetPassword(targetLogin: String, newPassword: String): JSONObject =
        gateway.request(ApiActions.RESET_PASSWORD) {
            put(ApiFields.TARGET_LOGIN, targetLogin)
            if (newPassword.isNotBlank()) put(ApiFields.NEW_PASSWORD, newPassword)
        }

    override fun renameUser(targetLogin: String, fullName: String): JSONObject =
        gateway.request(ApiActions.RENAME_USER) {
            put(ApiFields.TARGET_LOGIN, targetLogin)
            put(ApiFields.FULL_NAME, fullName)
        }

    override fun changeUserLogin(targetLogin: String, newLogin: String): JSONObject =
        gateway.request(ApiActions.CHANGE_LOGIN) {
            put(ApiFields.TARGET_LOGIN, targetLogin)
            put(ApiFields.NEW_LOGIN, newLogin)
        }

    override fun changeUserRole(targetLogin: String, newRole: String): JSONObject =
        gateway.request(ApiActions.CHANGE_ROLE) {
            put(ApiFields.TARGET_LOGIN, targetLogin)
            put(ApiFields.NEW_ROLE, newRole)
        }

    override fun deleteUser(targetLogin: String): JSONObject =
        gateway.request(ApiActions.DELETE_USER) { put(ApiFields.TARGET_LOGIN, targetLogin) }

    // ── Audit log ──────────────────────────────────────────────────────

    override fun getAuditLog(
        actorLogin: String,
        action: String,
        targetType: String,
        fromDate: String,
        toDate: String,
        limit: Int,
        offset: Int,
    ): JSONObject = gateway.request(ApiActions.GET_AUDIT_LOG) {
        if (actorLogin.isNotBlank()) put(ApiFields.ACTOR_LOGIN, actorLogin)
        // Send under `action_filter` — the top-level `action` field is the
        // dispatcher key on the server, sending it twice would clobber the
        // filter key. Server reads `action_filter` (and `action_verb` as a
        // fallback for legacy callers).
        if (action.isNotBlank()) put(ApiFields.ACTION_FILTER, action)
        if (targetType.isNotBlank()) put(ApiFields.TARGET_TYPE, targetType)
        if (fromDate.isNotBlank()) put(ApiFields.FROM_DATE, fromDate)
        if (toDate.isNotBlank()) put(ApiFields.TO_DATE, toDate)
        put(ApiFields.LIMIT, limit)
        put(ApiFields.OFFSET, offset)
    }

    // ── Mute/DND ───────────────────────────────────────────────────────

    override fun muteContact(targetLogin: String): JSONObject =
        gateway.request(ApiActions.MUTE_CONTACT) { put(ApiFields.TARGET_LOGIN, targetLogin) }

    override fun unmuteContact(targetLogin: String): JSONObject =
        gateway.request(ApiActions.UNMUTE_CONTACT) { put(ApiFields.TARGET_LOGIN, targetLogin) }

    override fun getMutedContacts(): JSONObject =
        gateway.request(ApiActions.GET_MUTED_CONTACTS)

    override fun setDndSchedule(dndStart: String, dndEnd: String): JSONObject =
        gateway.request(ApiActions.SET_DND_SCHEDULE) {
            put(ApiFields.DND_START, dndStart)
            put(ApiFields.DND_END, dndEnd)
        }

    // ── System pause / state ───────────────────────────────────────────

    override fun getSystemState(appScope: String): JSONObject =
        gateway.request(ApiActions.GET_SYSTEM_STATE) { put(ApiFields.APP_SCOPE, appScope) }

    override fun setAppPause(
        appScope: String,
        title: String,
        message: String,
        requireConfirmation: Boolean,
        autoWipeAfterHours: Int,
        state: String,
    ): JSONObject = gateway.request(ApiActions.SET_APP_PAUSE) {
        put(ApiFields.APP_SCOPE, appScope)
        put(ApiFields.TITLE, title)
        put(ApiFields.MESSAGE, message)
        put(ApiFields.REQUIRE_CONFIRMATION, requireConfirmation)
        put(ApiFields.AUTO_WIPE_AFTER_HOURS, autoWipeAfterHours)
        put(ApiFields.STATE, state)
    }

    override fun clearAppPause(appScope: String): JSONObject =
        gateway.request(ApiActions.CLEAR_APP_PAUSE) { put(ApiFields.APP_SCOPE, appScope) }
}
