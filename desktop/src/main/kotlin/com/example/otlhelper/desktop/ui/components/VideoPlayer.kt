package com.example.otlhelper.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.example.otlhelper.desktop.data.security.AttachmentCache
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.TextPrimary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer

/**
 * §TZ-DESKTOP-0.1.0 этап 5 — inline video-плеер на базе VLCJ (libVLC bindings).
 *
 * Подход: **без SwingPanel**. VLCJ рендерит кадры в `ByteBuffer` через
 * `RenderCallback`, мы копируем в `Skia Bitmap` (BGRA) → `ImageBitmap` → Compose
 * `Image`. Это избавляет от artefacts белой границы и перехвата scroll events,
 * которые возникали с `EmbeddedMediaPlayerComponent`/`CallbackMediaPlayerComponent`
 * внутри `SwingPanel` на macOS.
 *
 * Требует установленный libVLC (macOS: /Applications/VLC.app). Если не найден,
 * показываем кликабельный placeholder с fallback на системный плеер.
 */

internal val fullscreenVideoActive = MutableStateFlow(false)

private val vlcAvailable: Boolean by lazy {
    try {
        val os = System.getProperty("os.name", "").lowercase()
        // §TZ-DESKTOP-DIST 0.8.59 — bundled VLC priority. На Win CI workflow
        // кладёт libvlc.dll + plugins в app-resources/windows/vlc/. Compose
        // Desktop экспонирует эту папку через системное property
        // `compose.application.resources.dir`. Юзер: «без admin прав VLC не
        // установить». Bundled = self-contained, никаких action не нужно.
        val bundledVlc: String? = System.getProperty("compose.application.resources.dir")
            ?.let { resDir ->
                val candidate = java.io.File(resDir, "vlc")
                if (candidate.isDirectory) candidate.absolutePath else null
            }
        val candidates = buildList {
            if (bundledVlc != null) add(bundledVlc)
            when {
                os.contains("mac") -> {
                    add("/Applications/VLC.app/Contents/MacOS/lib")
                    add("/Applications/VLC.app/Contents/MacOS")
                }
                os.contains("win") -> {
                    System.getenv("ProgramFiles")?.let { add("$it\\VideoLAN\\VLC") }
                    System.getenv("ProgramFiles(x86)")?.let { add("$it\\VideoLAN\\VLC") }
                }
                else -> {
                    add("/usr/lib/x86_64-linux-gnu")
                    add("/usr/lib/vlc")
                    add("/usr/lib")
                }
            }
        }
        val existing = candidates.firstOrNull { it.isNotBlank() && java.io.File(it).isDirectory }
        if (existing != null) {
            val current = System.getProperty("jna.library.path", "")
            val merged = if (current.isBlank()) existing else "$current${java.io.File.pathSeparator}$existing"
            System.setProperty("jna.library.path", merged)
            val pluginsDir = when {
                existing == bundledVlc -> "$existing${java.io.File.separator}plugins"
                os.contains("mac") -> "/Applications/VLC.app/Contents/MacOS/plugins"
                os.contains("win") -> "$existing\\plugins"
                else -> "/usr/lib/vlc/plugins"
            }
            if (java.io.File(pluginsDir).isDirectory) {
                System.setProperty("VLC_PLUGIN_PATH", pluginsDir)
            }
        }
        NativeDiscovery().discover()
    } catch (_: Throwable) {
        false
    }
}

private val mediaPlayerFactory: MediaPlayerFactory? by lazy {
    if (!vlcAvailable) null
    else runCatching { MediaPlayerFactory() }.getOrNull()
}

/** Один renderer-экземпляр per URL. Держит MediaPlayer + текущий кадр как [ImageBitmap]. */
private class VlcRenderer(val file: java.io.File) {
    private val factory = mediaPlayerFactory
    val player: EmbeddedMediaPlayer? = factory?.mediaPlayers()?.newEmbeddedMediaPlayer()

    private var bufferW = 0
    private var bufferH = 0
    private var skiaBitmap: Bitmap? = null

    var currentFrame by mutableStateOf<ImageBitmap?>(null)
        private set

    init {
        player?.let { p ->
            val bufferFormatCallback = object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    bufferW = sourceWidth
                    bufferH = sourceHeight
                    skiaBitmap?.close()
                    val info = ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
                    skiaBitmap = Bitmap().apply { allocPixels(info) }
                    return RV32BufferFormat(sourceWidth, sourceHeight)
                }
                override fun allocatedBuffers(buffers: Array<out ByteBuffer>) { /* no-op */ }
            }
            val renderCallback = RenderCallback { _, nativeBuffers, _ ->
                val bmp = skiaBitmap ?: return@RenderCallback
                val src = nativeBuffers.firstOrNull() ?: return@RenderCallback
                try {
                    // rewind ДО capacity — position мог остаться в конце от прошлого кадра.
                    src.rewind()
                    val bytes = ByteArray(src.capacity())
                    src.get(bytes)
                    // VLC "RV32" = 32-bit int 0x00RRGGBB. На little-endian CPU
                    // (x86/ARM) байты в памяти — B,G,R,0 — ровно формат
                    // Skia BGRA_8888. Swap НЕ нужен. Только alpha=0xFF на 4-м байте,
                    // иначе PREMUL делает все пиксели прозрачными.
                    val total = bytes.size
                    var i = 3
                    while (i < total) {
                        bytes[i] = 0xFF.toByte()
                        i += 4
                    }
                    bmp.installPixels(bytes)
                    val img = SkiaImage.makeFromBitmap(bmp)
                    currentFrame = img.toComposeImageBitmap()
                } catch (_: Throwable) { /* skip frame */ }
            }
            runCatching {
                val videoSurface = factory!!.videoSurfaces().newVideoSurface(
                    bufferFormatCallback,
                    renderCallback,
                    true,
                )
                p.videoSurface().set(videoSurface)
            }
        }
    }

    fun start(muted: Boolean) {
        val p = player ?: return
        runCatching {
            p.audio().setMute(muted)
            // §TZ-DESKTOP-0.1.0 — loop как Android InlineVideoPlayer (REPEAT_MODE_ONE).
            p.controls().setRepeat(true)
            p.media().play(file.absolutePath)
        }
    }

    fun setMute(m: Boolean) = runCatching { player?.audio()?.setMute(m) }
    fun setPause(p: Boolean) = runCatching { player?.controls()?.setPause(p) }
    fun stop() {
        runCatching {
            player?.controls()?.stop()
            player?.release()
            skiaBitmap?.close()
            skiaBitmap = null
        }
    }
}

@Composable
fun InlineVideoPlayer(
    url: String,
    fileName: String,
    modifier: Modifier = Modifier,
) {
    if (!vlcAvailable) {
        VideoPlaceholderBox(url = url, fileName = fileName, modifier = modifier)
        return
    }

    var localFile by remember(url) { mutableStateOf<java.io.File?>(null) }
    var loadFailed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        val extHint = fileName.substringAfterLast('.', "").ifBlank { "mp4" }
        val f = AttachmentCache.ensureLocal(url, ".$extHint")
        if (f != null) localFile = f else loadFailed = true
    }

    val file = localFile
    var showFullscreen by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color(0xFF0A0A0A)),
    ) {
        when {
            loadFailed -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(40.dp),
                )
            }
            file == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.6f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(32.dp),
                )
            }
            else -> InlinePlayerImpl(
                file = file,
                onRequestFullscreen = { showFullscreen = true },
            )
        }
    }

    if (showFullscreen && file != null) {
        FullscreenVideoViewer(file = file, onDismiss = { showFullscreen = false })
    }
}

@Composable
private fun InlinePlayerImpl(
    file: java.io.File,
    onRequestFullscreen: () -> Unit,
) {
    var isPlaying by remember(file) { mutableStateOf(true) }
    var muted by remember(file) { mutableStateOf(true) }
    val fullscreenActive by fullscreenVideoActive.collectAsState()

    val renderer = remember(file) { VlcRenderer(file) }
    DisposableEffect(file) {
        renderer.start(muted = true)
        onDispose { renderer.stop() }
    }
    LaunchedEffect(muted) { renderer.setMute(muted) }
    LaunchedEffect(isPlaying, fullscreenActive) {
        renderer.setPause(fullscreenActive || !isPlaying)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val bmp = renderer.currentFrame
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.6f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        ControlButton(Modifier.align(Alignment.TopEnd), muted.let { if (it) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp }) {
            muted = !muted
        }
        ControlButton(Modifier.align(Alignment.BottomStart), if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow) {
            isPlaying = !isPlaying
        }
        ControlButton(Modifier.align(Alignment.BottomEnd), Icons.Filled.Fullscreen) {
            onRequestFullscreen()
        }
    }
}

@Composable
private fun ControlButton(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .padding(8.dp)
            .size(32.dp)
            .clip(CircleShape)
            .background(BgElevated.copy(alpha = 0.72f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun FullscreenVideoViewer(
    file: java.io.File,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { fullscreenVideoActive.value = true }
    DisposableEffect(Unit) { onDispose { fullscreenVideoActive.value = false } }

    var isPlaying by remember(file) { mutableStateOf(true) }
    var muted by remember(file) { mutableStateOf(false) }

    val renderer = remember(file) { VlcRenderer(file) }
    DisposableEffect(file) {
        renderer.start(muted = false)
        onDispose { renderer.stop() }
    }
    LaunchedEffect(muted) { renderer.setMute(muted) }
    LaunchedEffect(isPlaying) { renderer.setPause(!isPlaying) }

    val screen = remember { java.awt.Toolkit.getDefaultToolkit().screenSize }
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(
            position = WindowPosition(0.dp, 0.dp),
            size = DpSize(screen.width.dp, screen.height.dp),
        ),
        undecorated = true,
        // §RULE-DESKTOP-OVERLAY-2026-04-25 — fullscreen video viewer должен
        // быть выше heavyweight Chromium (Sheets-зона). DialogWindow с
        // transparent=false + alwaysOnTop. См. memory feedback_compose_heavyweight_overlays.
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
        // Blur Sheets под видео-плеером.
        DisposableEffect(Unit) {
            com.example.otlhelper.desktop.sheets.SheetsViewBridge.setBlur(true)
            onDispose { com.example.otlhelper.desktop.sheets.SheetsViewBridge.setBlur(false) }
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val bmp = renderer.currentFrame
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Color(0x99000000)).clickable { isPlaying = !isPlaying },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Color(0x99000000)).clickable { muted = !muted },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 24.dp, end = 16.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun VideoPlaceholderBox(url: String, fileName: String, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val isWin = remember {
        System.getProperty("os.name", "").lowercase().contains("win")
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color(0xFF0A0A0A))
            .clickable {
                scope.launch {
                    val extHint = fileName.substringAfterLast('.', "").ifBlank { "mp4" }
                    val f = AttachmentCache.ensureLocal(url, ".$extHint") ?: return@launch
                    runCatching { java.awt.Desktop.getDesktop().open(f) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(56.dp).clip(CircleShape).background(BgElevated.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Воспроизвести",
                    tint = TextPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }
            // §TZ-DESKTOP-UX-2026-05 0.8.58 — на Win если VLC не установлен,
            // inline player падает в placeholder. Юзер: «не работает видео
            // прямо внутри программы в чате в ленте новостей а оно качается».
            // Показываем подсказку — установить VLC для inline play.
            if (isWin && !vlcAvailable) {
                androidx.compose.material3.Text(
                    text = "Установи VLC для воспроизведения в приложении",
                    color = TextPrimary.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                androidx.compose.material3.Text(
                    text = "videolan.org/vlc/ • клик откроет в системном плеере",
                    color = TextPrimary.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
