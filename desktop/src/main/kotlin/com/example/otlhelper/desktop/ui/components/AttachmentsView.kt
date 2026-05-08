package com.example.otlhelper.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.example.otlhelper.desktop.data.feed.NewsRepository
import com.example.otlhelper.desktop.data.network.HttpClientFactory
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * §TZ-DESKTOP-0.1.0 этап 5 — attachments рендер для новостей.
 *
 * - Image/GIF: inline через [NetworkImage], тап → [FullscreenMediaViewer] (image).
 * - Video: placeholder с play-иконкой, тап → открыть в системном OS-плеере
 *   (временное решение; полноценный inline ExoPlayer-like — отдельный этап).
 * - File: download-row, тап → GET через OkHttp + save в ~/Downloads + открыть default app.
 */
@Composable
fun AttachmentsView(
    attachments: List<NewsRepository.Attachment>,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return

    var fullscreenImage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (att in attachments) {
            when {
                att.isImage -> NetworkImage(
                    url = att.url,
                    contentDescription = att.fileName,
                    contentScale = ContentScale.FillWidth,
                    // aspect-based sizing живёт внутри NetworkImage; добавляем
                    // только кадр для клика и скруглённые углы.
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { fullscreenImage = att.url },
                )

                att.isVideo -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp)),
                ) {
                    InlineVideoPlayer(
                        url = att.url,
                        fileName = att.fileName,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                else -> FileDownloadRow(
                    att = att,
                    onClick = { scope.launch { downloadAndOpen(att) } },
                )
            }
        }
    }

    fullscreenImage?.let { url ->
        FullscreenImageViewer(url = url, onDismiss = { fullscreenImage = null })
    }
}

@Composable
private fun FileDownloadRow(
    att: NewsRepository.Attachment,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgCard)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(fileIcon(att.fileType, att.fileName), fontSize = 24.sp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                att.fileName.ifBlank { "Файл" },
                color = TextPrimary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (att.fileSize > 0) {
                Text(formatFileSize(att.fileSize), color = TextTertiary, fontSize = 11.sp)
            }
        }
        Icon(
            Icons.Outlined.Download,
            contentDescription = "Скачать",
            tint = Accent,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * §TZ-DESKTOP 0.4.x — fullscreen image viewer через DialogWindow.
 *
 * §RULE-DESKTOP-OVERLAY-2026-04-25: lightweight Compose `Dialog` невидим под
 * heavyweight Chromium NSView (Sheets-зона). Юзер 2026-04-26: «когда
 * раскрывается картинка/видео из сайтбара, она за пределами гугл таблиц её
 * перекрывают, нужно сверху быть на всё приложение». Решение — DialogWindow
 * (отдельное OS-окно), полноэкранный размер, transparent=false (см. lessons
 * learned по transparent на macOS), Sheets под спудом блюрится через
 * SheetsViewBridge.
 */
@Composable
private fun FullscreenImageViewer(url: String, onDismiss: () -> Unit) {
    val screen = remember { java.awt.Toolkit.getDefaultToolkit().screenSize }
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(
            position = WindowPosition(0.dp, 0.dp),
            size = DpSize(screen.width.dp, screen.height.dp),
        ),
        undecorated = true,
        transparent = false,
        resizable = false,
        focusable = true,
        alwaysOnTop = true,
        onPreviewKeyEvent = { ev ->
            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                onDismiss(); true
            } else false
        },
    ) {
        // Blur Sheets под dialog'ом. Compose-overlay scrim на heavyweight'ом
        // не работает — blur'им изнутри Sheets через CSS filter.
        DisposableEffect(Unit) {
            com.example.otlhelper.desktop.sheets.SheetsViewBridge.setBlur(true)
            onDispose { com.example.otlhelper.desktop.sheets.SheetsViewBridge.setBlur(false) }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            NetworkImage(
                url = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Закрыть",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ── Download helper ──
// §TZ-2.3.28 — через AttachmentCache (OkHttp + cert trust + AES-GCM decrypt),
// сохраняется в ~/Downloads + открывается в default OS app. Cache файл в
// ~/.otldhelper/media-cache/ используется как plain-источник.
private suspend fun downloadAndOpen(att: NewsRepository.Attachment): Boolean = withContext(Dispatchers.IO) {
    try {
        val extHint = att.fileName.substringAfterLast('.', "")
        val cached = com.example.otlhelper.desktop.data.security.AttachmentCache
            .ensureLocal(att.url, ".$extHint") ?: return@withContext false
        val safeName = att.fileName.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)
            .ifBlank { "file" }
        val downloads = File(System.getProperty("user.home"), "Downloads").also { it.mkdirs() }
        val target = File(downloads, safeName).let { base ->
            if (!base.exists()) base
            else {
                val dot = base.name.lastIndexOf('.')
                val stem = if (dot > 0) base.name.take(dot) else base.name
                val ext = if (dot > 0) base.name.substring(dot) else ""
                generateSequence(2) { it + 1 }
                    .map { File(downloads, "$stem-$it$ext") }
                    .first { !it.exists() }
            }
        }
        target.writeBytes(cached.readBytes())
        runCatching { Desktop.getDesktop().open(target) }
        true
    } catch (_: Exception) { false }
}

private fun fileIcon(mimeType: String, name: String): String {
    val s = (mimeType + " " + name).lowercase()
    return when {
        s.contains("pdf") -> "📕"
        s.contains("zip") || s.contains("rar") || s.contains("7z") -> "🗜️"
        s.contains("excel") || s.contains("spreadsheet") || s.contains("xls") -> "📊"
        s.contains("word") || s.contains("doc") -> "📝"
        s.contains("video") -> "🎬"
        s.contains("audio") || s.contains("mp3") || s.contains("m4a") -> "🎵"
        s.contains("apk") -> "📦"
        else -> "📄"
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))}MB"
}
