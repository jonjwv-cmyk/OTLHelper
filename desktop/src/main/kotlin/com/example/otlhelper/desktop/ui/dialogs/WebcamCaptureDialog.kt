package com.example.otlhelper.desktop.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.StatusErrorBorder
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §TZ-DESKTOP 0.3.1 — WebcamCaptureDialog.
 *
 * Назначение:
 *   Позволяет сделать снимок с веб-камеры для аватарки. Аналог мобильного
 *   "Сделать фото" в Android-приложении.
 *
 * Реализация:
 *   • sarxos webcam-capture (Pure-Java bindings на нативные OS-API камеры)
 *   • Webcam.getDefault() → если null, показываем "Камера не найдена"
 *     (нет подключённой камеры / не получены права доступа)
 *   • LaunchedEffect поллит кадры ~25fps (delay 40ms), конвертит
 *     BufferedImage → ComposeImageBitmap для отображения
 *   • По клику "Снять" последний кадр пишется в temp-PNG и возвращается
 *     через onCapture(File)
 *   • DisposableEffect закрывает webcam на onDispose (освобождаем device)
 *
 * Fallback:
 *   Если sarxos падает / камеры нет — error-state с текстом. Юзер
 *   возвращается к AvatarPickerSheet и выбирает "Выбрать файл".
 *
 * Не меняет серверное поведение — результат сохраняется в temp-файл PNG и
 * попадает в тот же uploadAvatar pipeline (base64 data-URL → set_avatar).
 */
@Composable
fun WebcamCaptureDialog(
    onCapture: (java.io.File) -> Unit,
    onDismiss: () -> Unit,
) {
    var webcamImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var snapping by remember { mutableStateOf(false) }
    val webcam = remember {
        try { com.github.sarxos.webcam.Webcam.getDefault() } catch (_: Throwable) { null }
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(webcam) {
        if (webcam == null) {
            error = "Камера не найдена или доступ запрещён"
            return@LaunchedEffect
        }
        try {
            // Выбираем разумное разрешение: >= 640x480 или max поддерживаемое
            val sizes = webcam.viewSizes
            webcam.viewSize = sizes.firstOrNull { it.width >= 640 } ?: sizes.last()
            withContext(Dispatchers.IO) { webcam.open() }
            while (isActive) {
                val bi = withContext(Dispatchers.IO) {
                    runCatching { webcam.image }.getOrNull()
                }
                if (bi != null) webcamImage = bi.toComposeImageBitmap()
                kotlinx.coroutines.delay(40)
            }
        } catch (e: Exception) {
            error = "Ошибка камеры: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { webcam?.close() } catch (_: Throwable) {}
        }
    }

    fun snap() {
        if (webcam == null || snapping) return
        snapping = true
        scope.launch {
            try {
                val bi = withContext(Dispatchers.IO) {
                    runCatching { webcam.image }.getOrNull()
                }
                if (bi == null) {
                    error = "Не удалось снять кадр"
                    snapping = false
                    return@launch
                }
                val tempFile = java.io.File.createTempFile("avatar_webcam_", ".png")
                tempFile.deleteOnExit()
                withContext(Dispatchers.IO) {
                    javax.imageio.ImageIO.write(bi, "png", tempFile)
                }
                onCapture(tempFile)
            } catch (e: Exception) {
                error = "Ошибка снимка: ${e.message}"
            } finally {
                snapping = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(BgElevated)
                .border(0.5.dp, BorderDivider, RoundedCornerShape(14.dp))
                .pointerInput(Unit) { detectTapGestures { /* absorb */ } }
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Сделать снимок",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Start),
            )
            Spacer(Modifier.height(12.dp))

            // Preview / Loading / Error
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(BgCard)
                    .border(0.5.dp, BorderDivider, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    error != null -> Text(
                        error ?: "",
                        color = StatusErrorBorder,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(20.dp),
                    )
                    webcamImage != null -> Image(
                        bitmap = webcamImage!!,
                        contentDescription = "Preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    else -> CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, BorderDivider),
                ) {
                    Text("Отмена", color = TextSecondary)
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { snap() },
                    enabled = error == null && webcamImage != null && !snapping,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = BgApp,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (snapping) "Снимаем…" else "Снять")
                }
            }
        }
    }
}
