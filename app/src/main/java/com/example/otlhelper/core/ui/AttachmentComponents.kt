package com.example.otlhelper.core.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.otlhelper.core.theme.*
import com.example.otlhelper.core.ui.components.DialogDragHandle
import com.example.otlhelper.presentation.home.AttachmentItem
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gate for inline video playback. `false` → every InlineVideoPlayer below
 * this provider pauses and mutes. Lets HomeScreen suppress feed videos
 * while the splash overlay or the block overlay is drawn on top —
 * otherwise ExoPlayer keeps playing with sound under the covered UI and
 * you hear audio before the feed is ever visible.
 */
val LocalVideoPlaybackGate = staticCompositionLocalOf { true }

/**
 * Module-level "one video is fullscreen right now" flag. AttachmentsView
 * flips it when the tap-to-fullscreen Dialog opens; every
 * [InlineVideoPlayer] collects it and pauses its own playback so you
 * don't hear both the inline video AND the fullscreen video at once.
 * Dialog sits in a separate CompositionWindow, so a CompositionLocal
 * cannot reach inline players from inside the Dialog — a module-level
 * StateFlow is the clean way to bridge both compositions.
 */
internal val fullscreenVideoActive = kotlinx.coroutines.flow.MutableStateFlow(false)

// ── Attachment picker bottom sheet ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPickerSheet(
    onDismiss: () -> Unit,
    onAttachmentPicked: (AttachmentItem) -> Unit
) {
    val context = LocalContext.current

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val item = resolveAttachmentItem(context, uri)
            if (item != null) onAttachmentPicked(item)
        }
        onDismiss()
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val item = resolveAttachmentItem(context, uri)
            if (item != null) onAttachmentPicked(item)
        }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgElevated,
        dragHandle = { DialogDragHandle() }
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(
                "Прикрепить",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(16.dp))

            AttachPickerRow(Icons.Outlined.Image, "Фото / GIF") { galleryLauncher.launch("image/*") }
            AttachPickerRow(Icons.Outlined.Videocam, "Видео") { galleryLauncher.launch("video/*") }
            AttachPickerRow(Icons.Outlined.Description, "Файл") { fileLauncher.launch("*/*") }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextSecondary),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Отмена") }
        }
    }
}

@Composable
private fun AttachPickerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    // §TZ-2.3.9 — выбор типа вложения = definitive action → `confirm`
    // (двойной warm pulse). Раньше был `tap` — юзеру казалось что haptic
    // вообще нет, потому что CONFIRM-constant на API 30+ очень тихий, а
    // confirm() добавляет более явный pattern vibrate.
    val attachFeedback = com.example.otlhelper.core.feedback.LocalFeedback.current
    val attachHost = androidx.compose.ui.platform.LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                attachFeedback?.confirm(attachHost)
                onClick()
            }
            .padding(horizontal = 4.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(AccentSubtle, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = TextPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

// ── Pending attachment thumbnails (shown above input bar) ─────────────────────

@Composable
fun AttachmentThumbnailRow(
    attachments: List<AttachmentItem>,
    onRemove: (AttachmentItem) -> Unit
) {
    if (attachments.isEmpty()) return
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgCard)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(attachments) { item ->
            AttachmentThumb(item = item, onRemove = { onRemove(item) })
        }
    }
}

@Composable
private fun AttachmentThumb(item: AttachmentItem, onRemove: () -> Unit) {
    Box(modifier = Modifier.size(64.dp)) {
        if (item.mimeType.startsWith("image")) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(item.uri).crossfade(true).build(),
                contentDescription = item.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgCard)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgCard),
                contentAlignment = Alignment.Center
            ) {
                Text(fileIcon(item.mimeType), fontSize = 24.sp)
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color(0xFF3A3A3A))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, null, tint = TextPrimary, modifier = Modifier.size(12.dp))
        }
    }
}

// ── Attachment view in feed (images, GIFs, video, files) ─────────────────────

@Composable
fun AttachmentsView(attachmentsJson: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val attachments = remember(attachmentsJson) { parseAttachments(attachmentsJson) }
    if (attachments.isEmpty()) return

    // Fullscreen viewer state — null = closed; non-null = show given media.
    var fullscreen by remember { mutableStateOf<FullscreenMedia?>(null) }
    // §TZ-2.3.7 final — предоставляем handler fullscreen-enter для inline-плеера
    // через CompositionLocal. Player внутри subtree вызывает его из своей
    // fullscreen-кнопки → fullscreen state обновляется → FullscreenMediaViewer
    // рендерится. Раньше tap-overlay поверх видео перехватывал ВСЕ тапы
    // (включая play/pause) — это ломало контролы. Теперь tap-overlay удалён,
    // fullscreen только через явную кнопку.
    val requestFullscreen: (String, String) -> Unit = { url, name ->
        fullscreen = FullscreenMedia(url, isVideo = true, fileName = name)
    }
    // Publish fullscreen-open state to the module-level flag so EVERY
    // inline video in the feed (not just this card) mutes while any
    // fullscreen viewer is on screen. Prevents the double-audio the user
    // reported when they tap a video to enlarge it and the feed video
    // keeps playing with sound behind the overlay.
    LaunchedEffect(fullscreen != null) {
        fullscreenVideoActive.value = (fullscreen != null)
    }
    // Safety: if this AttachmentsView leaves composition while fullscreen
    // is open (e.g. user scrolls the card away), clear the flag so the
    // rest of the feed doesn't stay suppressed forever.
    DisposableEffect(Unit) {
        onDispose {
            if (fullscreen != null) fullscreenVideoActive.value = false
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalRequestFullscreenVideo provides requestFullscreen
    ) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (att in attachments) {
            val fileType = att.optString("file_type", "")
            val fileName = att.optString("file_name", "")
            val fileNameLower = fileName.lowercase()
            // §TZ-2.3.28 — fileUrl композитится с blob key+nonce (если
            // attachment зашифрован). Coil/ExoPlayer custom fetchers'ы
            // парсят fragment и расшифровывают bytes на лету.
            val fileUrl = com.example.otlhelper.core.security.blobAwareUrl(att, "file_url")

            when {
                // ── Image / GIF — Coil renders animated GIFs automatically ──
                fileType.startsWith("image") ||
                fileNameLower.endsWith(".jpg") || fileNameLower.endsWith(".jpeg") ||
                fileNameLower.endsWith(".png") || fileNameLower.endsWith(".gif") ||
                fileNameLower.endsWith(".webp") -> {
                    if (fileUrl.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(fileUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = fileName,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 360.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BgCard)
                                .clickable {
                                    fullscreen = FullscreenMedia(fileUrl, isVideo = false, fileName = fileName)
                                }
                        )
                    }
                }

                // ── Video — ExoPlayer inline with sound toggle ──────────────
                fileType.startsWith("video") ||
                fileNameLower.endsWith(".mp4") || fileNameLower.endsWith(".mov") ||
                fileNameLower.endsWith(".avi") || fileNameLower.endsWith(".webm") ||
                fileNameLower.endsWith(".mkv") -> {
                    if (fileUrl.startsWith("http")) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 300.dp)
                                .clip(RoundedCornerShape(10.dp))
                        ) {
                            InlineVideoPlayer(
                                url = fileUrl,
                                fileName = fileName,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Tap-overlay УДАЛЁН (§TZ-2.3.7): он ловил все тапы
                            // на play/pause и fullscreen-кнопки плеера. Теперь
                            // fullscreen — только через явную кнопку «На весь
                            // экран» внизу справа плеера.
                        }
                    }
                }

                // ── Any other file — download row (queues DownloadManager) ──
                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(BgCard)
                            .clickable {
                                if (fileUrl.startsWith("http")) {
                                    enqueueFileDownload(context, fileUrl, fileName.ifBlank { "file" })
                                }
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(fileIcon(fileType), fontSize = 24.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                fileName.ifBlank { "Файл" },
                                color = TextPrimary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val size = att.optLong("file_size", 0L)
                            if (size > 0) Text(formatFileSize(size), color = TextTertiary, fontSize = 11.sp)
                        }
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = "Скачать",
                            tint = Accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
    }  // CompositionLocalProvider(LocalRequestFullscreenVideo)

    // ── Fullscreen overlay (image / video) ───────────────────────────────
    fullscreen?.let { media ->
        FullscreenMediaViewer(media = media, onDismiss = { fullscreen = null })
    }
}

// Distinct payload for the fullscreen viewer; small enough to live next to
// the parent composable without a separate file.
private data class FullscreenMedia(
    val url: String,
    val isVideo: Boolean,
    val fileName: String,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun FullscreenMediaViewer(
    media: FullscreenMedia,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            if (media.isVideo) {
                val context = LocalContext.current
                val exo = remember(media.url) {
                    ExoPlayer.Builder(context)
                        .setMediaSourceFactory(
                            com.example.otlhelper.core.media.VideoCache.mediaSourceFactory(context)
                        )
                        .build().apply {
                            setMediaItem(MediaItem.fromUri(media.url))
                            repeatMode = Player.REPEAT_MODE_ONE
                            prepare()
                            playWhenReady = true
                            volume = 1f
                        }
                }
                // Track real video aspect — без него PlayerView растягивает
                // первый кадр на всю Box.fillMaxSize, видео визуально
                // искажается и «размазывается» при первом рендере.
                var videoAspect by remember(media.url) { mutableStateOf(16f / 9f) }
                DisposableEffect(exo) {
                    val listener = object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            if (videoSize.width > 0 && videoSize.height > 0) {
                                videoAspect = videoSize.width.toFloat() / videoSize.height.toFloat()
                            }
                        }
                    }
                    exo.addListener(listener)
                    onDispose { exo.removeListener(listener); exo.release() }
                }
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exo
                            useController = true
                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(videoAspect)
                )
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(media.url)
                        .crossfade(true)
                        .build(),
                    contentDescription = media.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Close button — top-right, always visible above the media.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 36.dp, end = 16.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Закрыть",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * §TZ-2.3.7 final — handler для перехода в fullscreen ИЗНУТРИ видео-плеера.
 * AttachmentsView провайдит реализацию через [LocalRequestFullscreenVideo],
 * которая устанавливает `fullscreen` state → рендерит `FullscreenMediaViewer`
 * НАШЕГО UI (не системного video viewer'а). Юзер хотел именно «раскрыть
 * внутри» — SF-style, тот же темный фон и контролы.
 */
val LocalRequestFullscreenVideo = androidx.compose.runtime.staticCompositionLocalOf<((url: String, fileName: String) -> Unit)?> { null }

// ── Inline video player (ExoPlayer via AndroidView) ──────────────────────────
// Behaviour §TZ-2.3.4 (SF-2026 feed apps — Instagram / TikTok / Telegram):
//   1. Sound ALWAYS OFF by default — даже когда видео полностью видно.
//   2. Sound ON — только если юзер явно нажал 🔊 (soundRequested = true).
//   3. Как только видео уходит из viewport — soundRequested сбрасывается в false.
//      При возвращении на экран звук снова выключен пока юзер опять не тапнет.
//      Это избавляет от раздражения «звук врубается сам при скролле».
//   4. Filename label под видео убран.
//   5. Pauses on background, releases on dispose (LazyColumn recycling).

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun InlineVideoPlayer(
    url: String,
    fileName: String = "",  // kept in signature but no longer rendered
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Юзер явно попросил звук (tap 🔇→🔊). false = тихо (по умолчанию).
    // При уходе видео из viewport сбрасываем в false — чтобы следующий раз
    // на этом видео звук опять был выключен, как на каждом другом.
    var soundRequested by remember(url) { mutableStateOf(false) }
    // Layout coordinates captured as state so we can poll visibility from a
    // coroutine that ticks regardless of whether Compose re-fires
    // onGloballyPositioned on a given scroll frame. The subagent audit
    // found the previous implementation relied ENTIRELY on
    // onGloballyPositioned firing; fast LazyColumn scroll sometimes batches
    // frames and the stale visibility lived through the scroll even when
    // rect had moved below threshold. A 100 ms poll is a sub-frame-
    // granularity safety net.
    var coords by remember(url) {
        mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null)
    }
    var isMostlyVisible by remember(url) { mutableStateOf(false) }
    // Parent surface (splash / block overlay) can veto playback entirely.
    val playbackAllowed = LocalVideoPlaybackGate.current
    // Global fullscreen-active suppression — inline pauses while ANY
    // fullscreen viewer is open, so two audio tracks never overlap.
    val fullscreenOpen by fullscreenVideoActive.collectAsState()

    val density = androidx.compose.ui.platform.LocalDensity.current
    val topChromePx = with(density) { 60.dp.toPx() }
    val bottomChromePx = with(density) { 96.dp.toPx() }
    // LocalView is the actual ComposeView hosting this composition — its
    // height is the authoritative, per-frame-accurate window height used
    // by the same layout pipeline boundsInWindow() reports coordinates
    // relative to. LocalWindowInfo.containerSize sometimes lagged by a
    // frame on transitions (IME open, tab swap), pushing the bottom
    // threshold below where boundsInWindow actually measured the video
    // and leaving "fully visible" false for videos that were visually
    // fully in the viewport.
    val hostView = androidx.compose.ui.platform.LocalView.current

    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context)
            // Cache-aware media source factory — videos stream through SimpleCache
            // so replays hit local disk (instant, no data)
            .setMediaSourceFactory(com.example.otlhelper.core.media.VideoCache.mediaSourceFactory(context))
            .build().apply {
                setMediaItem(MediaItem.fromUri(url))
                repeatMode = Player.REPEAT_MODE_ONE
                // Start muted — will be unmuted the moment we detect full visibility
                volume = 0f
                prepare()
                // Don't auto-play; the effect below decides based on the gate.
                playWhenReady = false
            }
    }

    // Fully-visible rule with a subpixel tolerance. Density rounding
    // can push rect.bottom to 704.3 when viewportBottom is 704.0 — a
    // strict `<=` says "not fully visible" and audio never triggers
    // even though the video is clearly on screen. 4-pixel tolerance
    // covers the rounding without permitting real clipping.
    LaunchedEffect(url) {
        while (true) {
            val c = coords
            if (c != null && c.isAttached) {
                val rect = c.boundsInWindow()
                val windowHeightPx = hostView.height.toFloat()
                val viewportTop = topChromePx
                val viewportBottom = windowHeightPx - bottomChromePx
                val tol = 4f
                val fullyVisible = rect.width > 0f &&
                        rect.height > 0f &&
                        rect.top >= (viewportTop - tol) &&
                        rect.bottom <= (viewportBottom + tol)
                if (fullyVisible != isMostlyVisible) {
                    isMostlyVisible = fullyVisible
                    // Видео вышло из viewport → сбрасываем "юзер хотел звук".
                    // Следующее появление этого же видео в кадре снова начнёт
                    // тихо — SF-2026 паттерн (никогда не взрываемся звуком
                    // случайно при скролле).
                    if (!fullyVisible) soundRequested = false
                    android.util.Log.d(
                        "InlineVideo",
                        "fully=$fullyVisible rect=[${rect.top.toInt()}..${rect.bottom.toInt()}] " +
                            "viewport=[${viewportTop.toInt()}..${viewportBottom.toInt()}]"
                    )
                }
            } else if (isMostlyVisible) {
                isMostlyVisible = false
                soundRequested = false
            }
            kotlinx.coroutines.delay(100)
        }
    }

    // Play/pause driven by a single snapshotFlow — coalesces rapid flips
    // into one transition. Includes the fullscreen-open veto so inline
    // pauses instantly when the user taps into fullscreen mode.
    //
    // §TZ-2.3.4 audio rule: звук = playback allowed AND fully visible AND
    // soundRequested==true (юзер тапнул 🔊). Всё остальное — 0. Без явного
    // тапа видео крутится тихо.
    LaunchedEffect(exoPlayer) {
        androidx.compose.runtime.snapshotFlow {
            Triple(
                playbackAllowed && isMostlyVisible && !fullscreenOpen,
                soundRequested,
                Unit,  // placeholder; snapshotFlow ре-emit при смене любого из верхних
            )
        }.collect { (shouldPlay, wantSound, _) ->
            if (shouldPlay) {
                exoPlayer.volume = if (wantSound) 1f else 0f
                exoPlayer.play()
            } else {
                // Mute FIRST, then pause — order prevents a one-frame
                // audio spike on some Media3 versions where pause()
                // leaves volume at its previous value for a decode tick.
                exoPlayer.volume = 0f
                exoPlayer.pause()
            }
        }
    }

    // Pause on background, resume on foreground.
    //
    // Teardown-race defence (§2.4 passport, Фаза 5):
    // Если вызвать exoPlayer.release() пока PlayerView ещё удерживает на него
    // ссылку и свои внутренние слушатели, Media3 валится с
    // `pthread_mutex_lock called on a destroyed mutex` при teardown screen'а
    // с играющим видео. Поэтому:
    //   1. Флаг `released` отсекает любые отложенные Lifecycle-события, которые
    //      могли успеть встать в очередь основного потока к моменту release().
    //   2. AndroidView.onRelease ниже обнуляет `PlayerView.player` ДО release(),
    //      Compose дизпосит в LIFO: AndroidView → DisposableEffect.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val released = remember(exoPlayer) { java.util.concurrent.atomic.AtomicBoolean(false) }
    DisposableEffect(lifecycle, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (released.get()) return@LifecycleEventObserver
            // Only pause on background. Resume is delegated to the
            // visibility LaunchedEffect so ON_RESUME doesn't force playback
            // on a video that's not actually on screen (was the race that
            // made audio kick in right after app resume before the first
            // layout pass settled isMostlyVisible).
            if (event == Lifecycle.Event.ON_PAUSE) exoPlayer.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (released.compareAndSet(false, true)) {
                exoPlayer.release()
            }
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF0A0A0A))
            // onGloballyPositioned just captures coords into state; all
            // visibility math lives in the polling LaunchedEffect above so
            // one scroll-frame skip can't leave isMostlyVisible stuck true.
            .onGloballyPositioned { coords = it }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            // Отвязываем player ДО того как ExoPlayer.release() вызовется в
            // DisposableEffect.onDispose — PlayerView успевает снять свои
            // внутренние слушатели, не обращаясь к уже освобождённому
            // native-ресурсу (см. §2.4 баг `destroyed mutex`).
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize()
        )

        // Sound toggle. §TZ-2.3.4: иконка 🔇 по умолчанию, 🔊 только когда
        // юзер явно попросил И видео полностью видимо. Тап инвертирует
        // soundRequested — тихо→звук включается, звук→тихо.
        val isEffectivelyMuted = !isMostlyVisible || !soundRequested
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(BgElevated.copy(alpha = 0.72f))
                .clickable {
                    // Нажатие имеет смысл только когда видео в viewport
                    // (иначе soundRequested тут же обнулится polling'ом).
                    if (isMostlyVisible) soundRequested = !soundRequested
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isEffectivelyMuted) Icons.AutoMirrored.Outlined.VolumeOff
                              else Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = if (isEffectivelyMuted) "Включить звук" else "Выключить звук",
                tint = TextPrimary,
                modifier = Modifier.size(18.dp),
            )
        }

        // §TZ-2.3.7 — bottom controls: play/pause + fullscreen. Иконки в
        // полупрозрачном круге над видео, SF-стиль. Раньше был только
        // встроенный tap-to-toggle (ExoPlayer default controller), теперь
        // юзер видит явные кнопки.
        var isPlaying by remember(exoPlayer) {
            mutableStateOf(exoPlayer.isPlaying || exoPlayer.playWhenReady)
        }
        LaunchedEffect(exoPlayer) {
            while (true) {
                isPlaying = exoPlayer.isPlaying || exoPlayer.playWhenReady
                kotlinx.coroutines.delay(500)
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Play/Pause
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(BgElevated.copy(alpha = 0.72f))
                    .clickable {
                        if (exoPlayer.isPlaying) exoPlayer.pause()
                        else { exoPlayer.playWhenReady = true; exoPlayer.play() }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        // Fullscreen кнопка — правый нижний угол. §TZ-2.3.7 final: открывает
        // НАШ fullscreen viewer через LocalRequestFullscreenVideo (provided
        // выше в AttachmentsView). Никакого системного video viewer'а.
        val requestFs = LocalRequestFullscreenVideo.current
        if (requestFs != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(BgElevated.copy(alpha = 0.72f))
                    .clickable { requestFs(url, fileName) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = "На весь экран",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

fun resolveAttachmentItem(context: Context, uri: Uri): AttachmentItem? {
    return try {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        var fileName = "file"
        var fileSize = 0L
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (nameIdx >= 0) fileName = it.getString(nameIdx) ?: "file"
                if (sizeIdx >= 0) fileSize = it.getLong(sizeIdx)
            }
        }
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val maxSize = 20L * 1024 * 1024
        if (fileSize > maxSize) return null
        AttachmentItem(uri = uri, fileName = fileName, mimeType = mimeType, fileSize = fileSize)
    } catch (_: Exception) { null }
}

fun buildAttachmentsJson(context: Context, attachments: List<AttachmentItem>): JSONArray {
    val arr = JSONArray()
    for (item in attachments) {
        try {
            val bytes = context.contentResolver.openInputStream(item.uri)?.readBytes() ?: continue
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUrl = "data:${item.mimeType};base64,$b64"
            arr.put(JSONObject().apply {
                put("file_url", dataUrl)
                put("file_name", item.fileName)
                put("file_type", item.mimeType)
                put("file_size", item.fileSize)
            })
        } catch (_: Exception) {}
    }
    return arr
}

private fun parseAttachments(json: String): List<JSONObject> {
    if (json.isBlank() || json == "null") return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    } catch (_: Exception) { emptyList() }
}

/**
 * §TZ-2.3.35 Phase 2C — скачиваем файл (PDF/doc/xlsx/mp3/…) через НАШ OkHttp,
 * расшифровываем AES-GCM envelope если URL содержит fragment `#k=&n=`, сохраняем
 * в публичную папку Downloads (через MediaStore на API 29+, либо Environment
 * на старше), затем открываем через FileProvider ACTION_VIEW.
 *
 * Ключевой момент: **VPS / CF видят только encrypted bytes**. Системный
 * DownloadManager шёл напрямую через Android-стек, без наших DNS override'ов
 * и без ключей расшифровки — сохранял бы мусор для encrypted attachments.
 */
private val fileDownloadScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
)

private fun enqueueFileDownload(context: Context, url: String, fileName: String) {
    val appContext = context.applicationContext
    val sanitized = fileName.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)
        .ifBlank { "file" }
    android.widget.Toast.makeText(appContext, "Скачивание: $sanitized", android.widget.Toast.LENGTH_SHORT).show()

    fileDownloadScope.launch {
        try {
            val baseUrl = com.example.otlhelper.core.security.BlobUrlComposer.stripFragment(url)
            val keys = com.example.otlhelper.core.security.BlobUrlComposer.parseKeys(url)

            val client = com.example.otlhelper.data.network.HttpClientFactory.downloadClient()
            val response = client.newCall(
                okhttp3.Request.Builder().url(baseUrl).build()
            ).execute()

            val plain: ByteArray = response.body?.use { body ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("HTTP ${response.code}")
                }
                val raw = body.bytes()
                if (keys != null) {
                    com.example.otlhelper.shared.security.BlobCrypto.decrypt(raw, keys.first, keys.second)
                } else {
                    raw
                }
            } ?: throw java.io.IOException("empty body")

            val uri = saveToDownloads(appContext, sanitized, plain)
                ?: throw java.io.IOException("save_failed")

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(
                    appContext, "Готово: $sanitized (${formatFileSize(plain.size.toLong())})",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                openDownloadedFile(appContext, uri, sanitized)
            }
        } catch (e: Throwable) {
            // §TZ-2.3.36 log hygiene: URL содержит opaque_id — не логируем.
            android.util.Log.e("FileDownload", "fail err=${e.javaClass.simpleName}")
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(
                    appContext, "Ошибка скачивания: ${e.message?.take(80)}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

private fun saveToDownloads(ctx: Context, name: String, bytes: ByteArray): Uri? {
    return try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // §TZ API 29+ — MediaStore.Downloads; файл появляется в системном
            // файл-менеджере в папке Downloads без запроса WRITE permission.
            val resolver = ctx.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, guessMime(name))
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = android.provider.MediaStore.Downloads.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
            )
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            // API <29 fallback — пишем в app-specific Downloads (не требует permission),
            // экспонируем через FileProvider.
            val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: ctx.cacheDir
            val file = java.io.File(dir, name)
            file.writeBytes(bytes)
            androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file
            )
        }
    } catch (_: Throwable) { null }
}

private fun openDownloadedFile(ctx: Context, uri: Uri, name: String) {
    try {
        val mime = guessMime(name)
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(openIntent, "Открыть файл").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
    } catch (_: Throwable) {
        android.widget.Toast.makeText(
            ctx, "Файл сохранён, но нет приложения для открытия",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

private fun guessMime(fileName: String): String {
    val lower = fileName.lowercase()
    return when {
        lower.endsWith(".pdf") -> "application/pdf"
        lower.endsWith(".doc") || lower.endsWith(".docx") -> "application/msword"
        lower.endsWith(".xls") || lower.endsWith(".xlsx") -> "application/vnd.ms-excel"
        lower.endsWith(".ppt") || lower.endsWith(".pptx") -> "application/vnd.ms-powerpoint"
        lower.endsWith(".txt") -> "text/plain"
        lower.endsWith(".csv") -> "text/csv"
        lower.endsWith(".zip") -> "application/zip"
        lower.endsWith(".rar") -> "application/vnd.rar"
        lower.endsWith(".7z") -> "application/x-7z-compressed"
        lower.endsWith(".mp3") -> "audio/mpeg"
        lower.endsWith(".m4a") -> "audio/mp4"
        lower.endsWith(".ogg") -> "audio/ogg"
        lower.endsWith(".wav") -> "audio/wav"
        lower.endsWith(".mp4") -> "video/mp4"
        lower.endsWith(".webm") -> "video/webm"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".gif") -> "image/gif"
        else -> "application/octet-stream"
    }
}

private fun fileIcon(mimeType: String): String = when {
    mimeType.contains("pdf") -> "📕"
    mimeType.contains("zip") || mimeType.contains("rar") || mimeType.contains("7z") -> "🗜️"
    mimeType.contains("excel") || mimeType.contains("spreadsheet") || mimeType.contains("xls") -> "📊"
    mimeType.contains("word") || mimeType.contains("doc") -> "📝"
    mimeType.contains("video") -> "🎬"
    mimeType.contains("audio") -> "🎵"
    mimeType.contains("apk") -> "📦"
    else -> "📄"
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))}MB"
}
