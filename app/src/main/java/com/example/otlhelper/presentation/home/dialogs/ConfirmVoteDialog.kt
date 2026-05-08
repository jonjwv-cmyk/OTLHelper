package com.example.otlhelper.presentation.home.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.example.otlhelper.core.theme.*

@Composable
fun ConfirmVoteDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подтвердить выбор", color = TextPrimary) },
        text = { Text("Подтвердить выбранные варианты?", color = TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Подтвердить", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = TextSecondary) }
        },
        containerColor = BgCard
    )
}
