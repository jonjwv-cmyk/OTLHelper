package com.example.otlhelper.presentation.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.BgApp
import com.example.otlhelper.core.theme.BgElevated
import com.example.otlhelper.core.theme.BgInput
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.StatusErrorBorder
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.ui.components.DialogDragHandle
import com.example.otlhelper.presentation.changepassword.ChangePasswordViewModel

/**
 * Change-password UI as a bottom sheet, hosted inside Home. Keeps the user on
 * the current screen — no NavController jump to a separate destination, no
 * splash/re-init on cancel, no `popUpTo(0)` flash on success. The old
 * full-screen `ChangePasswordScreen` stays reachable for the forced-change
 * flow out of login.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChangePasswordSheet(
    onDismiss: () -> Unit,
    viewModel: ChangePasswordViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    // On success just close — user stays on Home, no navigation side effects.
    LaunchedEffect(state.success) { if (state.success) onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgElevated,
        dragHandle = { DialogDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "Смена пароля",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(16.dp))

            PwdField(
                label = "Текущий пароль",
                value = oldPassword,
                onValueChange = { oldPassword = it },
                imeAction = ImeAction.Next,
                onAction = { focusManager.moveFocus(FocusDirection.Down) },
            )
            Spacer(Modifier.height(12.dp))
            PwdField(
                label = "Новый пароль",
                value = newPassword,
                onValueChange = { newPassword = it },
                imeAction = ImeAction.Next,
                onAction = { focusManager.moveFocus(FocusDirection.Down) },
            )
            Spacer(Modifier.height(12.dp))
            PwdField(
                label = "Повторите новый пароль",
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                imeAction = ImeAction.Done,
                onAction = {
                    focusManager.clearFocus()
                    viewModel.changePassword(oldPassword, newPassword, confirmPassword)
                },
            )

            AnimatedVisibility(
                visible = state.error.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    state.error,
                    color = StatusErrorBorder,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { viewModel.changePassword(oldPassword, newPassword, confirmPassword) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = !state.isLoading,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgApp),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        color = BgApp,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Изменить пароль", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Отмена", color = TextSecondary)
            }
        }
    }
}

@Composable
private fun PwdField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    imeAction: ImeAction,
    onAction: () -> Unit,
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
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = Accent,
            unfocusedBorderColor = BorderDivider,
            cursorColor = Accent,
            focusedContainerColor = BgInput,
            unfocusedContainerColor = BgInput,
        ),
    )
}
