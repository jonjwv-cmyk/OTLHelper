package com.example.otlhelper.presentation.changepassword

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.otlhelper.core.theme.*

@Composable
fun ChangePasswordScreen(
    forced: Boolean = false,
    onNavigateToHome: () -> Unit,
    onCancel: () -> Unit,
    viewModel: ChangePasswordViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.success) {
        if (state.success) onNavigateToHome()
    }

    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier.fillMaxSize().background(BgApp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Смена пароля", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (forced) {
                Text("Необходимо изменить пароль перед входом", color = TextSecondary, fontSize = 14.sp)
            }
            Spacer(Modifier.height(32.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = BgCard
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    PwdField("Текущий пароль", oldPassword, { oldPassword = it }, ImeAction.Next) {
                        focusManager.moveFocus(FocusDirection.Down)
                    }
                    Spacer(Modifier.height(14.dp))
                    PwdField("Новый пароль", newPassword, { newPassword = it }, ImeAction.Next) {
                        focusManager.moveFocus(FocusDirection.Down)
                    }
                    Spacer(Modifier.height(14.dp))
                    PwdField("Повторите новый пароль", confirmPassword, { confirmPassword = it }, ImeAction.Done) {
                        focusManager.clearFocus()
                        viewModel.changePassword(oldPassword, newPassword, confirmPassword)
                    }

                    AnimatedVisibility(visible = state.error.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                        Text(state.error, color = StatusErrorBorder, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { viewModel.changePassword(oldPassword, newPassword, confirmPassword) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = !state.isLoading,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgApp)
                    ) {
                        if (state.isLoading) CircularProgressIndicator(color = BgApp, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text("Изменить пароль", fontWeight = FontWeight.Bold)
                    }

                    if (!forced) {
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                            Text("Отмена", color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PwdField(
    label: String, value: String, onValueChange: (String) -> Unit,
    imeAction: ImeAction, onAction: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextSecondary) },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onAction() }, onDone = { onAction() }),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
            focusedBorderColor = Accent, unfocusedBorderColor = BorderDivider,
            cursorColor = Accent, focusedContainerColor = BgInput, unfocusedContainerColor = BgInput
        )
    )
}
