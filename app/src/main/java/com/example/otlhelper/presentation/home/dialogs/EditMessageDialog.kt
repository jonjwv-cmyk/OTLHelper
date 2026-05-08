package com.example.otlhelper.presentation.home.dialogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.*

/**
 * SF-2026 §3.15.a.А (Phase 12a): диалог правки текста сообщения / новости / опроса.
 * Ограничение 24ч enforce'ится на сервере — клиент показывает серверную ошибку
 * если окно истекло.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMessageDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        title = { Text("Изменить", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "Изменения видны всем. Окно правки — 24ч после создания.",
                    color = TextTertiary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank() && text != initialText,
            ) { Text("Сохранить", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = TextSecondary) }
        },
    )
}
