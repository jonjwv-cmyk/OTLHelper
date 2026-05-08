package com.example.otlhelper.presentation.home.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.*
import com.example.otlhelper.core.ui.AttachmentPickerSheet
import com.example.otlhelper.core.ui.components.DialogDragHandle
import com.example.otlhelper.core.ui.AttachmentThumbnailRow
import com.example.otlhelper.core.ui.buildAttachmentsJson
import com.example.otlhelper.presentation.home.AttachmentItem
import org.json.JSONArray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PollBuilderDialog(
    onDismiss: () -> Unit,
    onSubmit: (description: String, options: List<String>, attachments: JSONArray) -> Unit
) {
    val context = LocalContext.current
    var description by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(listOf("", "")) }
    var error by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf(listOf<AttachmentItem>()) }
    var showAttachPicker by remember { mutableStateOf(false) }

    // §TZ-2.3.9 — haptic на все action'ы диалога (добавить/удалить/прикрепить/опубликовать).
    val feedback = com.example.otlhelper.core.feedback.LocalFeedback.current
    val hostView = androidx.compose.ui.platform.LocalView.current
    val scrollState = rememberScrollState()

    // §TZ-2.3.21 — ФИКС прыжков штоки.
    // ROOT CAUSE (2.3.20 и раньше): ModalBottomSheet без skipPartiallyExpanded
    // растягивается по размеру content → каждый add/remove варианта менял
    // высоту sheet (partial → full или наоборот), визуально «прыгало». Плюс
    // animateScrollTo к maxValue триггерился на любое изменение size и
    // создавал дополнительное движение.
    //
    // ФИКС: (1) sheetState с skipPartiallyExpanded=true — sheet сразу full-
    // height, content растёт внутри зафиксированной шторки. (2) убран
    // авто-scroll при add/remove — шторка стабильна, юзер сам скроллит
    // если новое поле уехало вниз (обычно не требуется, т.к. full-height
    // помещает 8 вариантов с лихвой).
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    if (showAttachPicker) {
        AttachmentPickerSheet(
            onDismiss = { showAttachPicker = false },
            onAttachmentPicked = { item ->
                pendingAttachments = pendingAttachments + item
                showAttachPicker = false
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BgCard,
        dragHandle = { DialogDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Создать опрос", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            OtlOutlinedField(value = description, onValueChange = { description = it }, label = "Вопрос")
            Spacer(Modifier.height(12.dp))

            Text("Варианты ответов", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))

            options.forEachIndexed { index, opt ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OtlOutlinedField(
                        value = opt,
                        onValueChange = { newVal -> options = options.toMutableList().also { it[index] = newVal } },
                        label = "Вариант ${index + 1}",
                        modifier = Modifier.weight(1f)
                    )
                    if (options.size > 2) {
                        IconButton(onClick = {
                            feedback?.tap(hostView)
                            options = options.toMutableList().also { it.removeAt(index) }
                        }) {
                            Icon(Icons.Default.Close, null, tint = TextTertiary)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (options.size < 8) {
                TextButton(onClick = {
                    feedback?.tap(hostView)
                    options = options + ""
                }) {
                    Icon(Icons.Default.Add, null, tint = Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Добавить вариант", color = Accent, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Attachments ───────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            if (pendingAttachments.isNotEmpty()) {
                AttachmentThumbnailRow(
                    attachments = pendingAttachments,
                    onRemove = { item ->
                        feedback?.tap(hostView)
                        pendingAttachments = pendingAttachments - item
                    }
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BgCard)
                    .clickable {
                        feedback?.tap(hostView)
                        showAttachPicker = true
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "Прикрепить файл",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (pendingAttachments.isEmpty()) "Прикрепить файл (фото, видео, документ)"
                    else "Прикрепить ещё (${pendingAttachments.size} прикреплено)",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }

            if (error.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = StatusErrorBorder, fontSize = 13.sp)
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val validOpts = options.filter { it.isNotBlank() }
                    when {
                        description.isBlank() -> {
                            feedback?.warn(hostView)
                            error = "Введите вопрос"
                        }
                        validOpts.size < 2 -> {
                            feedback?.warn(hostView)
                            error = "Добавьте минимум 2 варианта"
                        }
                        else -> {
                            feedback?.confirm(hostView)
                            val attachmentsJson = buildAttachmentsJson(context, pendingAttachments)
                            onSubmit(description, validOpts, attachmentsJson)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = BgApp)
            ) {
                Text("Опубликовать", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun OtlOutlinedField(
    value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label, color = TextSecondary) },
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
            focusedBorderColor = Accent, unfocusedBorderColor = BorderDivider,
            cursorColor = Accent, focusedContainerColor = BgInput, unfocusedContainerColor = BgInput
        )
    )
}
