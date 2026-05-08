package com.example.otlhelper.presentation.changepassword

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.otlhelper.ApiClient
import com.example.otlhelper.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ChangePasswordUiState(
    val isLoading: Boolean = false,
    val error: String = "",
    val success: Boolean = false
)

@HiltViewModel
class ChangePasswordViewModel @Inject constructor(
    private val session: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChangePasswordUiState())
    val uiState: StateFlow<ChangePasswordUiState> = _uiState.asStateFlow()

    fun changePassword(old: String, new1: String, new2: String) {
        if (old.isBlank()) { setError("Введите текущий пароль"); return }
        if (new1.isBlank()) { setError("Введите новый пароль"); return }
        if (new1.length < 6) { setError("Новый пароль должен содержать не менее 6 символов"); return }
        if (new1 != new2) { setError("Пароли не совпадают"); return }

        _uiState.value = _uiState.value.copy(isLoading = true, error = "")

        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.changePassword(old, new1)
                }
                val ok = response.optBoolean("ok", false)
                val error = response.optString("error", "")
                if (ok) {
                    session.setMustChangePassword(false)
                    _uiState.value = _uiState.value.copy(isLoading = false, success = true)
                } else {
                    val msg = when (error) {
                        "wrong_password" -> "Неверный текущий пароль"
                        "same_password" -> "Новый пароль должен отличаться от текущего"
                        else -> error.ifBlank { "Не удалось изменить пароль" }
                    }
                    setError(msg)
                }
            } catch (_: Exception) {
                setError("Нет соединения с сервером")
            }
        }
    }

    private fun setError(msg: String) {
        _uiState.value = _uiState.value.copy(isLoading = false, error = msg)
    }
}
