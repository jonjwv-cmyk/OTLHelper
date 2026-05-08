package com.example.otlhelper.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.otlhelper.desktop.data.security.AttachmentCache
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage
import java.util.concurrent.ConcurrentHashMap

/**
 * §TZ-DESKTOP-0.1.0 этап 5 — network image loader с disk-cache и animated GIF.
 *
 * Поведение как у AsyncImage (Android):
 *  - Loading — Spinner в небольшом BgCard-боксе.
 *  - Loaded — image с нативным aspect ratio (width = fillMaxWidth, height = width/aspect),
 *    но не выше 360dp. Без лишнего фона вокруг.
 *  - Animated (GIF/WebP/APNG) — все кадры крутятся через withFrameNanos.
 *  - File — через AttachmentCache (OkHttp + decrypt + disk cache), повторный
 *    показ мгновенный.
 */
/**
 * §TZ-DESKTOP-0.1.0 — network image loader.
 *
 * Режимы:
 *  - [useIntrinsicSize] = true (по умолчанию) — image получает aspect-based
 *    height через fillMaxWidth + aspectRatio. Для attachments в ленте/чате.
 *  - useIntrinsicSize = false — [modifier] диктует размер (например `.fillMaxSize()`
 *    внутри круглого avatar-контейнера). Внутри просто Image заполняет пространство.
 */
@Composable
fun NetworkImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
    useIntrinsicSize: Boolean = true,
) {
    var state by remember(url) { mutableStateOf<ImageState>(ImageState.Loading) }

    LaunchedEffect(url) {
        val mem = memCache[url]
        if (mem != null) {
            state = ImageState.Loaded(mem)
            return@LaunchedEffect
        }
        val loaded = runCatching { loadFrames(url) }.getOrNull()
        state = if (loaded != null) {
            memCache[url] = loaded
            ImageState.Loaded(loaded)
        } else ImageState.Error
    }

    when (val s = state) {
        is ImageState.Loaded -> {
            val first = s.frames.bitmaps.first()
            val aspect = if (first.height > 0) first.width.toFloat() / first.height.toFloat() else 1f
            AnimatedFrames(
                frames = s.frames,
                contentDescription = contentDescription,
                contentScale = contentScale,
                aspectRatio = if (useIntrinsicSize) aspect else 0f,
                modifier = modifier,
            )
        }
        is ImageState.Loading -> {
            if (useIntrinsicSize) {
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(BgCard),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp),
                    )
                }
            } else {
                Box(modifier = modifier.background(BgCard))
            }
        }
        is ImageState.Error -> {
            if (useIntrinsicSize) {
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .background(BgCard),
                ) {}
            } else {
                Box(modifier = modifier.background(BgCard))
            }
        }
    }
}

private sealed class ImageState {
    data object Loading : ImageState()
    data object Error : ImageState()
    data class Loaded(val frames: Frames) : ImageState()
}

private class Frames(
    val bitmaps: List<ImageBitmap>,
    val durationsMs: List<Int>,
) {
    val isAnimated: Boolean get() = bitmaps.size > 1
}

private val memCache = ConcurrentHashMap<String, Frames>()

@Composable
private fun AnimatedFrames(
    frames: Frames,
    contentDescription: String?,
    contentScale: ContentScale,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
) {
    // aspectRatio > 0 → intrinsic-sized (для attachments).
    // aspectRatio = 0 → modifier от caller'а полностью диктует размер (avatar).
    val sizedModifier = if (aspectRatio > 0f) {
        modifier.fillMaxWidth().heightIn(max = 360.dp).aspectRatio(aspectRatio)
    } else {
        modifier
    }

    if (!frames.isAnimated) {
        Image(
            bitmap = frames.bitmaps.first(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = sizedModifier,
        )
        return
    }

    var frameIndex by remember(frames) { mutableStateOf(0) }

    DisposableEffect(frames) {
        onDispose { frameIndex = 0 }
    }

    LaunchedEffect(frames) {
        var base = 0L
        var nextAt = frames.durationsMs[0].toLong() * 1_000_000L
        while (true) {
            withFrameNanos { ts ->
                if (base == 0L) base = ts
                val elapsed = ts - base
                if (elapsed >= nextAt) {
                    frameIndex = (frameIndex + 1) % frames.bitmaps.size
                    val d = frames.durationsMs[frameIndex].coerceAtLeast(20)
                    nextAt = elapsed + d.toLong() * 1_000_000L
                }
            }
        }
    }

    Image(
        bitmap = frames.bitmaps[frameIndex.coerceIn(0, frames.bitmaps.lastIndex)],
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = sizedModifier,
    )
}

private suspend fun loadFrames(url: String): Frames? = withContext(Dispatchers.IO) {
    val extHint = guessExt(url)
    val file = AttachmentCache.ensureLocal(url, extHint) ?: return@withContext null
    val bytes = file.readBytes()

    val codec = runCatching { Codec.makeFromData(Data.makeFromBytes(bytes)) }.getOrNull()
    if (codec != null) {
        val frameCount = codec.frameCount
        if (frameCount > 1) {
            val bitmaps = ArrayList<ImageBitmap>(frameCount)
            val durations = ArrayList<Int>(frameCount)
            for (i in 0 until frameCount) {
                val bmp = org.jetbrains.skia.Bitmap().apply {
                    allocPixels(codec.imageInfo)
                }
                codec.readPixels(bmp, i)
                bitmaps += org.jetbrains.skia.Image.makeFromBitmap(bmp).toComposeImageBitmap()
                val d = codec.framesInfo.getOrNull(i)?.duration ?: 100
                durations += d
                bmp.close()
            }
            codec.close()
            return@withContext Frames(bitmaps, durations)
        }
        codec.close()
    }

    val bitmap = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    Frames(listOf(bitmap), listOf(0))
}

private fun guessExt(url: String): String {
    val base = com.example.otlhelper.desktop.data.security.BlobUrlComposer.stripFragment(url)
    val lastDot = base.lastIndexOf('.')
    if (lastDot < 0) return ""
    val ext = base.substring(lastDot).lowercase()
    return if (ext.length <= 6 && ext.all { it.isLetterOrDigit() || it == '.' }) ext else ""
}
