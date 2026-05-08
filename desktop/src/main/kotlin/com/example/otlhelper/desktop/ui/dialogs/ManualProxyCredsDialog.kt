package com.example.otlhelper.desktop.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.otlhelper.desktop.core.network.ProxyCredsStore
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgInput
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary

/**
 * §TZ-DESKTOP-0.9.3 — Модальный диалог "Введите доменный логин и пароль для прокси".
 *
 * Поднимается **автоматически** через [ProxyCredsStore.showDialog] StateFlow
 * когда [JcifsNtlmAuthenticator] не находит cached creds. Юзер вводит свой
 * Windows-логин (тот же что в session) и пароль — jcifs-ng ими подписывает
 * NTLM Type-3 → прокси пускает.
 *
 * Юзер вводит **один раз на сессию**. Cached в [ProxyCredsStore] (in-memory),
 * не сериализуется на диск.
 */
@Composable
fun ManualProxyCredsDialogHost() {
    val show by ProxyCredsStore.showDialog.collectAsState()
    val proxyAddr by ProxyCredsStore.proxyAddress.collectAsState()
    if (show) {
        ManualProxyCredsDialog(
            proxyAddress = proxyAddr ?: "(unknown)",
            onSubmit = { login, password -> ProxyCredsStore.submitCreds(login, password) },
            onCancel = { ProxyCredsStore.cancelDialog() },
        )
    }
}

@Composable
private fun ManualProxyCredsDialog(
    proxyAddress: String,
    onSubmit: (login: String, password: String) -> Unit,
    onCancel: () -> Unit,
) {
    var login by remember { mutableStateOf(System.getProperty("user.name") ?: "") }
    var password by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(480.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(BgCard)
                    .padding(28.dp),
            ) {
                Text(
                    "Корпоративный прокси требует авторизации",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Прокси: $proxyAddress",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Введите свои доменные учётные данные (для входа в Windows). " +
                        "Они сохранятся только на текущую сессию.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )

                Spacer(Modifier.height(20.dp))

                val fieldColors = TextFieldDefaults.colors(
                    focusedContainerColor = BgInput,
                    unfocusedContainerColor = BgInput,
                    disabledContainerColor = BgInput,
                    focusedIndicatorColor = Accent,
                    unfocusedIndicatorColor = BorderDivider,
                    cursorColor = Accent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                )

                OutlinedTextField(
                    value = login,
                    onValueChange = { login = it },
                    label = { Text("Логин (DOMAIN\\login или login@domain)", color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions.Default,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль", color = TextSecondary) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) {
                        Text("Отмена", color = TextSecondary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSubmit(login.trim(), password) },
                        enabled = login.isNotBlank() && password.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) {
                        Text("Подключиться", color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
