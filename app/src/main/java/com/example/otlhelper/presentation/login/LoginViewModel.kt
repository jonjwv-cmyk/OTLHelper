package com.example.otlhelper.presentation.login

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otlhelper.ApiClient
import com.example.otlhelper.BuildConfig
import com.example.otlhelper.SessionManager
import com.example.otlhelper.core.push.PushTokenManager
import com.example.otlhelper.core.security.IntegrityChecker
import com.example.otlhelper.domain.model.Role
import com.example.otlhelper.domain.model.wireName
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String = "",
    val navigateTo: LoginNavTarget = LoginNavTarget.None,
    /** Заполнено сервером при ответе `app_version_too_old` — URL нового APK + min. */
    val mandatoryUpdateUrl: String = "",
    val mandatoryUpdateMinVersion: String = "",
)

enum class LoginNavTarget { None, Home, ChangePassword }

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val session: SessionManager,
    private val pushTokenManager: PushTokenManager,
    private val integrityChecker: IntegrityChecker,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // Проактивная сверка версии с сервером сразу при открытии экрана
        // логина — если юзер пришёл по push'у «Обновление доступно» или
        // ранее выходил и теперь вернулся после mandatory-релиза, мы сразу
        // покажем кнопку «Скачать», не дожидаясь попытки войти с неверной
        // версией. `app_status` публичный — токен не нужен.
        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.appStatus(com.example.otlhelper.BuildConfig.VERSION_NAME)
                }
                val versionOk = response.optBoolean("version_ok", true)
                if (!versionOk) {
                    val updateUrl = response.optString("update_url", "")
                    val currentVersion = response.optString("current_version", "")
                    val minVersion = response.optString("min_version", currentVersion)
                    if (updateUrl.isNotBlank()) {
                        _uiState.value = _uiState.value.copy(
                            mandatoryUpdateUrl = updateUrl,
                            mandatoryUpdateMinVersion = minVersion,
                        )
                    }
                }
            } catch (e: Exception) {
                // Не бьём юзера экраном ошибки — кнопка «Обновить» всё равно
                // появится при попытке логина. Но НЕ молчим: пишем в logcat и
                // телеметрию для диагностики (app_status публичный, если
                // падает — это серверный инцидент, надо видеть).
                android.util.Log.w("LoginVM", "app_status pre-check failed", e)
            }
        }
    }

    fun login(login: String, password: String) {
        if (login.isBlank()) { setError("Введите логин"); return }
        if (password.isBlank()) { setError("Введите пароль"); return }

        _uiState.value = _uiState.value.copy(isLoading = true, error = "")

        viewModelScope.launch {
            try {
                val deviceId = withContext(Dispatchers.Main) {
                    android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    ) ?: ""
                }

                // SF-2026 security hardening: Play Integrity attestation перед login.
                // Nonce формируется из login+timestamp+deviceId — anti-replay гарантия.
                // Если Google Play Services недоступны или API отказал — integrity=null,
                // сервер сам решит по feature-flag'у принимать или нет.
                val integrityInput = "$login|${System.currentTimeMillis()}|$deviceId"
                val integrity = integrityChecker.requestToken(integrityInput)

                val response = withContext(Dispatchers.IO) {
                    ApiClient.login(
                        login = login,
                        password = password,
                        deviceId = deviceId,
                        appVersion = BuildConfig.VERSION_NAME,
                        integrityToken = integrity?.token.orEmpty(),
                        integrityNonce = integrity?.nonce.orEmpty(),
                    )
                }

                val ok = response.optBoolean("ok", false)
                val error = response.optString("error", "")

                if (ok) {
                    val token = response.optString("token", "")
                    val expiresAt = response.optString("expires_at", "")
                    // Server wraps user fields in "user" object; fall back to root for compat
                    val userObj = response.optJSONObject("user")
                    val fullName = userObj?.optString("full_name", "") ?: response.optString("full_name", "")
                    val role = userObj?.optString("role", Role.User.wireName()) ?: response.optString("role", Role.User.wireName())
                    val mustChange = ((userObj?.optInt("must_change_password", 0)
                        ?: response.optInt("must_change_password", 0)) != 0)

                    session.saveUser(login = login, fullName = fullName, role = role)
                    session.saveToken(token = token, expiresAt = expiresAt)
                    session.setMustChangePassword(mustChange)
                    // Серверные feature-flags кэшируются в сессии для оффлайн-работы (§3.15.a.Е).
                    val featuresJson = userObj?.optJSONObject("features") ?: response.optJSONObject("features")
                    session.saveFeatures(featuresJson)
                    ApiClient.setAuth(token, deviceId)

                    // Register FCM token with the backend under the new login
                    pushTokenManager.syncToken()

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        navigateTo = if (mustChange) LoginNavTarget.ChangePassword else LoginNavTarget.Home
                    )
                } else {
                    val errorMsg = when (error) {
                        "user_not_found" -> "Пользователь не найден"
                        "wrong_password" -> "Неверный пароль"
                        "user_inactive" -> "Аккаунт деактивирован"
                        "user_suspended" -> "Аккаунт заблокирован"
                        "app_version_too_old" -> "Обновите приложение до последней версии"
                        "app_blocked" -> "Приложение заблокировано администратором"
                        else -> error.ifBlank { "Ошибка входа" }
                    }
                    // Специальная обработка: если сервер вернул `app_version_too_old`,
                    // он обязан приложить `update_url` + `min_version`. Показываем
                    // пользователю кнопку «Обновить», а не просто текст без действия.
                    if (error == "app_version_too_old") {
                        val updateUrl = response.optString("update_url", "")
                        val minVersion = response.optString("min_version", "")
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = errorMsg,
                            mandatoryUpdateUrl = updateUrl,
                            mandatoryUpdateMinVersion = minVersion,
                        )
                    } else {
                        setError(errorMsg)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("LoginVM", "login failed with exception", e)
                setError("Нет соединения с сервером")
            }
        }
    }

    fun clearNavTarget() {
        _uiState.value = _uiState.value.copy(navigateTo = LoginNavTarget.None)
    }

    private fun setError(msg: String) {
        _uiState.value = _uiState.value.copy(isLoading = false, error = msg)
    }
}
