package com.example.otlhelper.presentation.home.internal

import com.example.otlhelper.ApiClient
import com.example.otlhelper.core.settings.AppSettings
import com.example.otlhelper.presentation.home.HomeUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * §TZ-CLEANUP-2026-04-26 — admin slice of HomeViewModel.
 *
 * Owns:
 *  - users management CRUD (load list / rename / change login / change role /
 *    create / toggle active / reset password / delete)
 *  - system state (superadmin pause/unpause overlay)
 *  - getUsers raw json fetch (legacy callers expecting the response object)
 *
 * Mutates [uiState] under: usersList, usersLoading.
 *
 * Extracted from AppController so admin operations can evolve independently
 * (mute/dnd/audit log готовится). Все public сигнатуры сохранены 1:1 —
 * HomeViewModel just routes to this controller instead of AppController.
 */
internal class AdminController(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<HomeUiState>,
    private val appSettings: AppSettings,
) {

    // ── Users management (admin / developer) ─────────────────────────────────
    fun loadUsersList(onResult: (List<JSONObject>) -> Unit = {}) {
        scope.launch {
            uiState.update { it.copy(usersLoading = true) }
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.getUsers() }
                val ok = response.optBoolean("ok", false)
                if (ok) {
                    val data = response.optJSONArray("data") ?: JSONArray()
                    // Server occasionally returns duplicated rows from the
                    // users/presence JOIN — dedupe by login so the admin UI
                    // doesn't have to guard against it.
                    val users = (0 until data.length())
                        .map { data.getJSONObject(it) }
                        .distinctBy { it.optString("login") }
                    uiState.update { it.copy(usersList = users, usersLoading = false) }
                    // Write-through cache: next cold launch hydrates from this
                    // blob before the network even replies.
                    runCatching {
                        val arr = JSONArray()
                        for (u in users) arr.put(u)
                        appSettings.cachedUsersListJson = arr.toString()
                    }
                    onResult(users)
                } else {
                    uiState.update { it.copy(usersLoading = false) }
                    onResult(emptyList())
                }
            } catch (_: Exception) {
                uiState.update { it.copy(usersLoading = false) }
                onResult(emptyList())
            }
        }
    }

    fun renameUser(targetLogin: String, fullName: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.renameUser(targetLogin, fullName) }
                onResult(response.optBoolean("ok", false))
            } catch (_: Exception) { onResult(false) }
        }
    }

    fun changeUserLogin(targetLogin: String, newLogin: String, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.changeUserLogin(targetLogin, newLogin) }
                val ok = response.optBoolean("ok", false)
                val err = response.optString("error", "")
                val msg = when (err) {
                    "login_already_exists" -> "Логин уже занят"
                    "new_login_empty" -> "Пустой логин"
                    else -> err
                }
                onResult(ok, if (ok) "" else msg)
            } catch (_: Exception) { onResult(false, "Ошибка сети") }
        }
    }

    fun changeUserRole(targetLogin: String, newRole: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.changeUserRole(targetLogin, newRole) }
                onResult(response.optBoolean("ok", false))
            } catch (_: Exception) { onResult(false) }
        }
    }

    fun createUser(newLogin: String, fullName: String, password: String, role: String, mustChange: Boolean, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.createUser(newLogin, fullName, password, role, mustChange)
                }
                val ok = response.optBoolean("ok", false)
                val err = response.optString("error", "")
                onResult(ok, if (ok) "" else err.ifBlank { "Ошибка создания" })
            } catch (_: Exception) { onResult(false, "Ошибка сети") }
        }
    }

    fun toggleUser(targetLogin: String, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.toggleUser(targetLogin) }
                val ok = response.optBoolean("ok", false)
                val err = response.optString("error", "")
                onResult(ok, err.ifBlank { if (ok) "" else "Ошибка" })
            } catch (_: Exception) { onResult(false, "Нет соединения") }
        }
    }

    fun toggleUserSilent(targetLogin: String, onResult: (Boolean) -> Unit) {
        toggleUser(targetLogin) { ok, _ -> onResult(ok) }
    }

    fun resetPassword(targetLogin: String, newPassword: String, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.resetPassword(targetLogin, newPassword) }
                val ok = response.optBoolean("ok", false)
                val err = response.optString("error", "")
                onResult(ok, err.ifBlank { if (ok) "" else "Ошибка" })
            } catch (_: Exception) { onResult(false, "Нет соединения") }
        }
    }

    fun resetPasswordSilent(targetLogin: String, newPassword: String, onResult: (Boolean) -> Unit) {
        resetPassword(targetLogin, newPassword) { ok, _ -> onResult(ok) }
    }

    fun deleteUser(targetLogin: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.deleteUser(targetLogin) }
                onResult(response.optBoolean("ok", false))
            } catch (_: Exception) { onResult(false) }
        }
    }

    /** Raw users response — для legacy callers. */
    fun getUsers(onResult: (JSONObject?) -> Unit) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.getUsers() }
                onResult(response)
            } catch (_: Exception) { onResult(null) }
        }
    }

    // ── System state (superadmin) ────────────────────────────────────────────
    fun getSystemState(onResult: (JSONObject?) -> Unit) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.getSystemState() }
                onResult(response)
            } catch (_: Exception) { onResult(null) }
        }
    }

    fun setAppPause(title: String, message: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.setAppPause(title = title, message = message) }
                onResult(response.optBoolean("ok", false))
            } catch (_: Exception) { onResult(false) }
        }
    }

    fun clearAppPause(onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.clearAppPause() }
                onResult(response.optBoolean("ok", false))
            } catch (_: Exception) { onResult(false) }
        }
    }
}
