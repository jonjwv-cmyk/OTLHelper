package com.example.otlhelper.desktop.data.users

import com.example.otlhelper.desktop.data.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * §TZ-DESKTOP-0.1.0 — репозиторий списка пользователей для UserManagementSheet (admin+).
 * Load по запросу (при открытии sheet'а), без polling'а. После create/change/delete —
 * forceRefresh.
 */
class UsersRepository(private val scope: CoroutineScope) {

    data class User(
        val login: String,
        val fullName: String,
        val role: String,
        val presence: String,
        val lastSeenAt: String,
        val avatarUrl: String,
        val isActive: Boolean,
        val mustChangePassword: Boolean,
    )

    data class State(
        val users: List<User> = emptyList(),
        val isLoading: Boolean = false,
        val lastError: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * §0.11.0 — targeted presence update. Вызывается из App.kt при WS event
     * `presence_change`. Обновляет только presence/lastSeenAt у конкретного
     * user'а в local list — без full refresh с сервера. Мгновенный UI update.
     */
    fun updatePresence(login: String, status: String, lastSeenAt: String) {
        if (login.isBlank()) return
        val current = _state.value
        val updated = current.users.map { u ->
            if (u.login == login) u.copy(
                presence = status,
                lastSeenAt = lastSeenAt.ifBlank { u.lastSeenAt },
            ) else u
        }
        _state.value = current.copy(users = updated)
    }

    suspend fun refresh() {
        _state.value = _state.value.copy(isLoading = true, lastError = "")
        try {
            val resp = ApiClient.getUsers()
            if (!resp.optBoolean("ok", false)) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    lastError = resp.optString("error", "unknown"),
                )
                return
            }
            val arr = resp.optJSONArray("data") ?: return
            val users = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                User(
                    login = o.optString("login"),
                    fullName = o.optString("full_name").ifBlank { o.optString("login") },
                    role = o.optString("role", "user"),
                    presence = o.optString("presence_status", "offline"),
                    lastSeenAt = o.optString("last_seen_at", ""),
                    avatarUrl = com.example.otlhelper.desktop.data.security.blobAwareUrl(o, "avatar_url"),
                    isActive = o.optInt("is_active", 1) == 1,
                    mustChangePassword = o.optInt("must_change_password", 0) == 1,
                )
            }
            _state.value = State(users = users, isLoading = false, lastError = "")
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isLoading = false,
                lastError = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    suspend fun createUser(
        login: String,
        fullName: String,
        password: String,
        role: String,
        mustChangePassword: Boolean,
    ): Boolean {
        return try {
            val resp = ApiClient.createUser(login, fullName, password, role, mustChangePassword)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) { false }
    }

    suspend fun changeRole(targetLogin: String, newRole: String): Boolean {
        return try {
            val resp = ApiClient.changeUserRole(targetLogin, newRole)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) { false }
    }

    suspend fun resetPassword(targetLogin: String, newPassword: String = ""): Boolean {
        return try {
            val resp = ApiClient.resetPassword(targetLogin, newPassword)
            resp.optBoolean("ok", false)
        } catch (_: Exception) { false }
    }

    /** §TZ-0.10.6 — Сброс лимита парольных входов на этой неделе (developer/superadmin). */
    suspend fun resetPasswordLoginCounter(targetLogin: String): Boolean {
        return try {
            val resp = ApiClient.resetPasswordLoginCounter(targetLogin)
            resp.optBoolean("ok", false)
        } catch (_: Exception) { false }
    }

    suspend fun changeLogin(targetLogin: String, newLogin: String): Boolean {
        return try {
            val resp = ApiClient.changeUserLogin(targetLogin, newLogin)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) { false }
    }

    suspend fun deleteUser(targetLogin: String): Boolean {
        return try {
            val resp = ApiClient.deleteUser(targetLogin)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) { false }
    }

    /** §TZ-DESKTOP-0.10.2 — Переименовать (full_name) пользователя. */
    suspend fun renameUser(targetLogin: String, fullName: String): Boolean {
        return try {
            val resp = ApiClient.renameUser(targetLogin, fullName)
            val ok = resp.optBoolean("ok", false)
            if (ok) refresh()
            ok
        } catch (_: Exception) { false }
    }
}
