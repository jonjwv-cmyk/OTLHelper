package com.example.otlhelper.desktop.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.AccentMuted
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgInput
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary

/**
 * §TZ-DESKTOP-0.1.0 этап 5 — PollBuilder с реальным create_news_poll.
 * Сейчас: вопрос = title = description (упрощение), варианты ≥ 2. Attachments —
 * пока моки (будут в рамках R2-upload этапа).
 */
@Composable
fun PollBuilderSheet(
    onDismiss: () -> Unit,
    onCreate: (title: String, description: String, options: List<String>) -> Unit,
    onBack: () -> Unit = onDismiss,
) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    val attachments = remember { mutableStateListOf<String>() }
    var attachPickerOpen by remember { mutableStateOf(false) }

    // dismissOnOutsideClick=false — в поллбилдере много ввода текста, случайный
    // клик мимо не должен терять введённые данные. Выйти можно через «← Назад»
    // или кнопку «Отмена».
    BottomSheetShell(
        onDismiss = onDismiss,
        title = "Создать опрос",
        onBack = onBack,
        dismissOnOutsideClick = false,
    ) {
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            placeholder = { Text("Вопрос...", color = TextTertiary) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = textFieldColors(),
        )

        Spacer(Modifier.height(12.dp))
        Text("Варианты", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))

        options.forEachIndexed { idx, opt ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = opt,
                    onValueChange = { options[idx] = it },
                    placeholder = { Text("Вариант ${idx + 1}", color = TextTertiary) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = textFieldColors(),
                )
                if (options.size > 2) {
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = { options.removeAt(idx) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Close, "Убрать", tint = TextTertiary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { options.add("") }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(AccentSubtle, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, null, tint = Accent, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("Добавить вариант", color = Accent, fontSize = 13.sp)
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { attachPickerOpen = true }
                .background(BgCard)
                .border(0.5.dp, BorderDivider, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.AttachFile, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (attachments.isEmpty()) "Прикрепить файл / фото"
                else "Прикреплено: ${attachments.size}",
                color = TextPrimary,
                fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(16.dp))
        // §TZ-DESKTOP 0.3.2 — "Опубликовать" (12 Cyrillic chars 14sp SemiBold)
        // не помещался в кнопку на панели 280dp: weight(1f) каждой из двух
        // кнопок × 112dp - 48dp Material-внутренний padding = 64dp для текста
        // → резалось до "Опублик...". Фикс: (1) weight публиковать больше чем
        // отмена (1.6f vs 1f), (2) contentPadding уменьшен с дефолтного
        // 24h×12v до 12h×12v — больше места для глифов.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextSecondary),
                shape = RoundedCornerShape(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 12.dp,
                ),
            ) { Text("Отмена", maxLines = 1) }

            Button(
                onClick = {
                    val validOptions = options.map { it.trim() }.filter { it.isNotBlank() }
                    if (question.isNotBlank() && validOptions.size >= 2) {
                        onCreate(question.trim(), question.trim(), validOptions)
                        onDismiss()
                    }
                },
                modifier = Modifier.weight(1.6f),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgApp),
                shape = RoundedCornerShape(12.dp),
                enabled = question.isNotBlank() && options.count { it.isNotBlank() } >= 2,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 12.dp,
                ),
            ) {
                Text(
                    "Опубликовать",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }

    if (attachPickerOpen) {
        AttachmentPickerSheet(
            onDismiss = { attachPickerOpen = false },
            onPicked = { att -> attachments.add(att.name) },
        )
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = AccentMuted.copy(alpha = 0.4f),
    unfocusedBorderColor = Color.Transparent,
    cursorColor = Accent,
    focusedContainerColor = BgInput,
    unfocusedContainerColor = BgInput,
)
