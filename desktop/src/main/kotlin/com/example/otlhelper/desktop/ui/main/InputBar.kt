package com.example.otlhelper.desktop.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.AccentMuted
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgInput
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import com.example.otlhelper.desktop.ui.components.ThinDivider
import java.util.logging.Level
import java.util.logging.Logger

private val inputBarLogger: Logger = Logger.getLogger("InputBar")

/** §TZ-DESKTOP-0.1.0 — pending attachment выбранный в file-picker.
 *  Хранит реальный путь к файлу, mime и размер — используется при отправке
 *  для построения `data:mimetype;base64,<bytes>` payload'а, который сервер
 *  (`normalizeAttachments`) кладёт в R2 с шифрованием. */
data class DesktopAttachment(
    val id: Long,
    val file: java.io.File,
    val name: String,
    val mimeType: String,
    val size: Long,
    val kind: String,  // "photo" | "video" | "file"
)

/**
 * §TZ-DESKTOP-0.1.0 — desktop-копия app/presentation/home/components/InputBar.kt.
 * Одна полоса снизу WorkspacePanel'и: attachments-row + ThinDivider + Row {TextField, Send}.
 * trailingIcon = [Schedule, Attach] — те же отступы/размеры что в Android.
 *
 * §TZ-DESKTOP 0.3.0 — drag-and-drop: [onAttachmentsDropped] непустой callback
 * включает drop-zone на весь InputBar. Юзер перетаскивает файлы из Finder/
 * Explorer, они конвертируются в [DesktopAttachment] и добавляются в список.
 * Визуальный feedback: фон переключается на AccentSubtle во время drag-over.
 *
 * Компонент чисто UI, НЕ меняет серверное поведение — аттачменты загружаются
 * тем же путём (data-URL base64 в body.attachments), что при клике на скрепку.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    hint: String,
    showSend: Boolean,
    showAttach: Boolean,
    attachments: List<DesktopAttachment>,
    onSend: () -> Unit,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (DesktopAttachment) -> Unit,
    onScheduleClick: (() -> Unit)? = null,
    notice: String? = null,
    onAttachmentsDropped: ((List<DesktopAttachment>) -> Unit)? = null,
) {
    var dragActive by remember { mutableStateOf(false) }
    // Плавный переход фона во время drag-over. На 180ms — достаточно быстро
    // чтобы ощущалось отзывчиво, но не рывок.
    val bg by animateColorAsState(
        targetValue = if (dragActive) AccentSubtle else BgCard,
        animationSpec = tween(180),
        label = "inputBarDragBg",
    )

    // §TZ-DESKTOP 0.3.0 — drag-and-drop через новое Compose API
    // (dragAndDropTarget). Старый onExternalDrag deprecated-error в Compose
    // Multiplatform 1.7+. dndTarget — долгоживущий object с колбэками
    // onStarted/onExited/onEnded/onDrop. onStarted триггерится когда drag
    // входит в зону — подсвечиваем фон.
    val dndTarget = remember(onAttachmentsDropped) {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) { dragActive = true }
            override fun onExited(event: DragAndDropEvent) { dragActive = false }
            override fun onEnded(event: DragAndDropEvent) { dragActive = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragActive = false
                val data = event.dragData()
                if (data is DragData.FilesList) {
                    val atts = data.readFiles().mapNotNull { uri ->
                        buildDesktopAttachmentFromUri(uri)
                    }
                    if (atts.isNotEmpty()) {
                        onAttachmentsDropped?.invoke(atts)
                    }
                    return true
                }
                return false
            }
        }
    }
    val dropModifier = if (onAttachmentsDropped != null) {
        Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { event -> event.dragData() is DragData.FilesList },
            target = dndTarget,
        )
    } else Modifier

    Column(modifier = dropModifier) {
        if (!notice.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgCard)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    notice,
                    color = Accent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (attachments.isNotEmpty()) {
            AttachmentThumbnailRow(attachments = attachments, onRemove = onRemoveAttachment)
        }
        ThinDivider()
        // §TZ-DESKTOP 0.3.1 — vertical 6→4dp по фидбэку "строка ввода слишком
        // большая". OutlinedTextField min-height = 56dp (Material3-default,
        // изменить без swap на BasicTextField нельзя) — делаем внешний
        // padding минимальным.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        hint,
                        color = TextTertiary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = if (!showAttach && onScheduleClick == null) null else {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (onScheduleClick != null) {
                                IconButton(onClick = onScheduleClick, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        Icons.Outlined.Schedule,
                                        contentDescription = "Отложить",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            if (showAttach) {
                                IconButton(onClick = onAttachClick, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        Icons.Outlined.AttachFile,
                                        contentDescription = "Прикрепить",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentMuted.copy(alpha = 0.4f),
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = Accent,
                    focusedContainerColor = BgInput,
                    unfocusedContainerColor = BgInput,
                ),
            )

            if (showSend) {
                Spacer(Modifier.width(6.dp))
                val active = text.isNotBlank() || attachments.isNotEmpty()
                // §TZ-DESKTOP 0.3.0 — 40→36dp (убрали «слишком огромные» по
                // фидбэку). Icon 20→18dp пропорционально. Визуально теперь
                // ближе к trailing-icon'ам (schedule/attach 36dp).
                IconButton(
                    onClick = onSend,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (active) Accent else BgCard),
                ) {
                    Icon(
                        Icons.Rounded.ArrowUpward,
                        contentDescription = "Отправить",
                        tint = if (active) BgApp else TextTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * §TZ-DESKTOP 0.3.0 — конверсия drag-URI (file:///…) в [DesktopAttachment].
 * Используется в onDrop callback'e InputBar. Определяет kind (photo/video/file)
 * и mime по расширению — та же логика что в AttachmentPickerSheet.buildAttachment.
 *
 * Возвращает null если URI не парсится или файл недоступен — тихо игнорируем
 * такой файл (остальные из drop-batch продолжают добавляться).
 */
private fun buildDesktopAttachmentFromUri(uri: String): DesktopAttachment? {
    return try {
        val file = java.io.File(java.net.URI(uri))
        if (!file.exists()) return null
        val lower = file.name.lowercase()
        val mime = when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".bmp") -> "image/bmp"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".txt") -> "text/plain"
            else -> "application/octet-stream"
        }
        val kind = when {
            mime.startsWith("image/") -> "photo"
            mime.startsWith("video/") -> "video"
            else -> "file"
        }
        DesktopAttachment(
            id = System.currentTimeMillis() + file.hashCode(),
            file = file,
            name = file.name,
            mimeType = mime,
            size = file.length(),
            kind = kind,
        )
    } catch (e: Exception) {
        inputBarLogger.log(Level.FINE, "Failed to parse drag-and-drop file URI: $uri", e)
        null
    }
}
