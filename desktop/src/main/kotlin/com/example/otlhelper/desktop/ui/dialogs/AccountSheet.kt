package com.example.otlhelper.desktop.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.data.network.ApiClient
import com.example.otlhelper.desktop.model.Role
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BgInput
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.StatusErrorBorder
import com.example.otlhelper.desktop.theme.StatusOkBorder
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import com.example.otlhelper.desktop.ui.components.UserAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AccountSheet(
    login: String,
    fullName: String,
    avatarUrl: String,
    role: Role,
    onDismiss: () -> Unit,
    onAvatarPickerOpen: () -> Unit,
    onBack: () -> Unit = onDismiss,
) {
    var changePasswordOpen by remember { mutableStateOf(false) }
    val displayName = fullName.ifBlank { login }

    BottomSheetShell(onDismiss = onDismiss, title = "Аккаунт", onBack = onBack) {
        Spacer(Modifier.height(8.dp))

        Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
            UserAvatar(
                name = displayName,
                avatarUrl = avatarUrl,
                presenceStatus = "online",
                size = 92.dp,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Accent)
                    .clickable(onClick = onAvatarPickerOpen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.CameraAlt,
                    "Сменить аватар",
                    tint = com.example.otlhelper.desktop.theme.BgApp,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            displayName,
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            role.displayName,
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        if (fullName.isNotBlank() && login.isNotBlank() && fullName != login) {
            Text(
                "@$login",
                color = TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = { changePasswordOpen = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(0.5.dp, BorderDivider),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
        ) {
            Icon(
                Icons.Outlined.Key,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = TextSecondary,
            )
            Spacer(Modifier.width(10.dp))
            Text("Сменить пароль", style = MaterialTheme.typography.labelLarge)
        }
    }

    if (changePasswordOpen) {
        ChangePasswordDialog(onDismiss = { changePasswordOpen = false })
    }
}


/**
 * §TZ-DESKTOP-0.1.0 — диалог смены пароля. Просит old_password, new_password и
 * подтверждение (клиентская валидация). На submit зовёт [ApiClient.changePassword];
 * при ok=true закрывается с сообщением успеха, при error показывает текст ошибки.
 */
@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit) {
    var oldPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var inflight by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var successNotice by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val canSubmit = !inflight && oldPwd.isNotBlank() && newPwd.length >= 8 && newPwd == confirm

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(BgElevated)
                .border(0.5.dp, BorderDivider, RoundedCornerShape(14.dp))
                .clickable(enabled = false) {}
                .padding(18.dp),
        ) {
            Text("Сменить пароль", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            SecretInput("Текущий пароль", oldPwd) { oldPwd = it }
            Spacer(Modifier.height(10.dp))
            SecretInput("Новый пароль (не менее 8 символов)", newPwd) { newPwd = it }
            Spacer(Modifier.height(10.dp))
            SecretInput("Повторить новый", confirm) { confirm = it }
            Spacer(Modifier.height(8.dp))

            val hint = when {
                successNotice -> "Пароль изменён"
                error.isNotBlank() -> "Ошибка: $error"
                newPwd.isNotBlank() && newPwd.length < 8 -> "Минимум 8 символов"
                newPwd.isNotBlank() && confirm.isNotBlank() && newPwd != confirm -> "Пароли не совпадают"
                else -> ""
            }
            val hintColor = when {
                successNotice -> StatusOkBorder
                error.isNotBlank() || (newPwd.length in 1..7) || (newPwd.isNotBlank() && confirm.isNotBlank() && newPwd != confirm) -> StatusErrorBorder
                else -> Color.Transparent
            }
            if (hint.isNotBlank()) {
                Text(hint, color = hintColor, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
            }

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Отмена",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Сохранить",
                    color = if (canSubmit) Accent else TextTertiary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = canSubmit) {
                            inflight = true
                            error = ""
                            scope.launch {
                                val resp = withContext(Dispatchers.IO) {
                                    ApiClient.changePassword(oldPwd, newPwd)
                                }
                                inflight = false
                                if (resp.optBoolean("ok", false)) {
                                    successNotice = true
                                    // Закрыть через секунду, чтобы увидеть подтверждение.
                                    kotlinx.coroutines.delay(900)
                                    onDismiss()
                                } else {
                                    error = resp.optString("error", "unknown")
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SecretInput(label: String, value: String, onChange: (String) -> Unit) {
    Text(label, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BgInput)
            .border(0.5.dp, BorderDivider, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(Accent),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
