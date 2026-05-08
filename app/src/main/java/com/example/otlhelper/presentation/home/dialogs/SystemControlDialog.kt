package com.example.otlhelper.presentation.home.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.*
import com.example.otlhelper.core.ui.components.DialogDragHandle
import com.example.otlhelper.presentation.home.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemControlDialog(
    isSuperAdmin: Boolean,
    onDismiss: () -> Unit,
    viewModel: HomeViewModel
) {
    var appPaused by remember { mutableStateOf(false) }
    var pauseTitle by remember { mutableStateOf("Техническая пауза") }
    var pauseMessage by remember { mutableStateOf("Приложение временно недоступно") }
    var statusMsg by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        viewModel.getSystemState { response ->
            loading = false
            if (response != null) {
                // Server returns: { ok, data: { state, title, message, ... } }
                val dataObj = response.optJSONObject("data") ?: response
                val state = dataObj.optString("state", response.optString("app_state", "normal"))
                appPaused = state != "normal"
                if (appPaused) {
                    pauseTitle = dataObj.optString("title", response.optString("app_title", pauseTitle))
                    pauseMessage = dataObj.optString("message", response.optString("app_message", pauseMessage))
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        dragHandle = { DialogDragHandle() }
    ) {
        Column(modifier = Modifier.padding(16.dp).padding(bottom = 32.dp)) {
            Text("Управление системой", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            if (loading) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = appPaused,
                        onCheckedChange = { newVal ->
                            if (newVal) {
                                viewModel.setAppPause(pauseTitle, pauseMessage) { ok ->
                                    if (ok) { appPaused = true; statusMsg = "Пауза включена" }
                                    else statusMsg = "Не удалось включить паузу"
                                }
                            } else {
                                viewModel.clearAppPause { ok ->
                                    if (ok) { appPaused = false; statusMsg = "Пауза отключена" }
                                    else statusMsg = "Не удалось отключить паузу"
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = Accent,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = BgCard,
                            uncheckedBorderColor = BorderDivider,
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(if (appPaused) "Приложение на паузе" else "Приложение активно", color = TextPrimary, fontSize = 14.sp)
                }

                if (appPaused) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pauseTitle,
                        onValueChange = { pauseTitle = it },
                        label = { Text("Заголовок блокировки", color = TextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Accent, unfocusedBorderColor = BorderDivider,
                            focusedContainerColor = BgInput, unfocusedContainerColor = BgInput
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pauseMessage,
                        onValueChange = { pauseMessage = it },
                        label = { Text("Сообщение пользователям", color = TextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 2,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Accent, unfocusedBorderColor = BorderDivider,
                            focusedContainerColor = BgInput, unfocusedContainerColor = BgInput
                        )
                    )
                }

                if (statusMsg.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(statusMsg, color = Accent, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextPrimary)
            ) { Text("Закрыть") }
        }
    }
}
