package com.example.otlhelper.desktop.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary

/**
 * §TZ-DESKTOP-0.1.0 — attachment picker с реальным native file-chooser.
 * Три категории фильтра (photo/gif, video, file). На выбор — [java.awt.FileDialog]
 * открывается системный диалог. Результат — DesktopAttachment с File + mime + size,
 * отправляется как `data:mimetype;base64,...` в body.attachments, сервер
 * normalizeAttachments перекладывает в R2 с шифрованием.
 */
@Composable
fun AttachmentPickerSheet(
    onDismiss: () -> Unit,
    onPicked: (com.example.otlhelper.desktop.ui.main.DesktopAttachment) -> Unit,
) {
    SheetContainer(onDismiss = onDismiss) {
        Text("Прикрепить", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        PickerRow(Icons.Outlined.Image, "Фото / GIF") {
            pickFile("Выберите фото", arrayOf("jpg", "jpeg", "png", "gif", "webp", "bmp"))?.let {
                onPicked(buildAttachment(it, "photo"))
            }
            onDismiss()
        }
        PickerRow(Icons.Outlined.Videocam, "Видео") {
            pickFile("Выберите видео", arrayOf("mp4", "mov", "webm", "mkv", "avi", "m4v"))?.let {
                onPicked(buildAttachment(it, "video"))
            }
            onDismiss()
        }
        PickerRow(Icons.Outlined.Description, "Файл") {
            pickFile("Выберите файл", null)?.let {
                onPicked(buildAttachment(it, "file"))
            }
            onDismiss()
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextSecondary),
            shape = RoundedCornerShape(12.dp),
        ) { Text("Отмена") }
    }
}

/**
 * §TZ-DESKTOP 0.3.1 — AvatarPickerSheet с двумя опциями:
 *   1. "Выбрать файл"   — native file-chooser (JPG/PNG/WEBP из файловой системы)
 *   2. "Сделать снимок" — веб-камера через sarxos webcam-capture → open
 *                         [WebcamCaptureDialog] overlay с preview и снимком
 *
 * Раньше был вариант "Вставить из буфера" — убран по фидбэку (пользователь
 * хотел именно камеру). Сам webcam-диалог — отдельный оверлей над этим
 * sheet'ом; после снимка или отмены возвращаемся сюда, а при успехе
 * передаём File через onPicked и закрываем оба.
 */
@Composable
fun AvatarPickerSheet(
    onDismiss: () -> Unit,
    onPicked: (java.io.File) -> Unit,
) {
    var webcamOpen by remember { mutableStateOf(false) }

    SheetContainer(onDismiss = onDismiss) {
        Text("Сменить аватар", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        PickerRow(Icons.Outlined.Image, "Выбрать файл") {
            pickFile("Выберите аватар", arrayOf("jpg", "jpeg", "png", "webp"))?.let { onPicked(it) }
            onDismiss()
        }
        PickerRow(Icons.Outlined.PhotoCamera, "Сделать снимок") {
            webcamOpen = true
        }
    }

    if (webcamOpen) {
        WebcamCaptureDialog(
            onCapture = { file ->
                webcamOpen = false
                onPicked(file)
                onDismiss()
            },
            onDismiss = { webcamOpen = false },
        )
    }
}

// ── File picker helper (AWT native dialog, кроссплатформенный) ──

private fun pickFile(title: String, extensions: Array<String>?): java.io.File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    if (extensions != null) {
        dialog.filenameFilter = java.io.FilenameFilter { _, name ->
            val lower = name.lowercase()
            extensions.any { lower.endsWith(".$it") }
        }
    }
    dialog.isVisible = true
    val files = dialog.files
    return files.firstOrNull()
}

private fun buildAttachment(file: java.io.File, kind: String): com.example.otlhelper.desktop.ui.main.DesktopAttachment {
    return com.example.otlhelper.desktop.ui.main.DesktopAttachment(
        id = System.currentTimeMillis(),
        file = file,
        name = file.name,
        mimeType = guessMimeType(file.name),
        size = file.length(),
        kind = kind,
    )
}

private fun guessMimeType(fileName: String): String {
    val lower = fileName.lowercase()
    return when {
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
        lower.endsWith(".doc") -> "application/msword"
        lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        lower.endsWith(".xls") -> "application/vnd.ms-excel"
        lower.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        lower.endsWith(".zip") -> "application/zip"
        lower.endsWith(".mp3") -> "audio/mpeg"
        lower.endsWith(".txt") -> "text/plain"
        else -> "application/octet-stream"
    }
}

@Composable
private fun SheetContainer(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(BgElevated)
                .border(0.5.dp, BorderDivider, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .clickable(enabled = false) {}
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 32.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(34.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(BorderDivider),
            )
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun PickerRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(AccentSubtle, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = TextPrimary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}
