package com.example.otlhelper.presentation.home.dialogs

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.otlhelper.core.theme.*
import com.example.otlhelper.core.ui.components.DialogDragHandle
import java.io.File

/**
 * Avatar picker bottom sheet — gallery / camera.
 *
 * §TZ-2.3.20 — визуальный язык унифицирован с [AttachmentPickerSheet]:
 * тайтл `titleLarge`, иконки в 32dp скруглённых квадратах с `AccentSubtle`,
 * ряды с разделением 16dp. Раньше использовал эмодзи-иконки и `16.sp Bold`
 * заголовок — визуально разошлось с остальными sheets приложения.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarPickerDialog(
    onDismiss: () -> Unit,
    onAvatarPicked: (bytes: ByteArray, mimeType: String, fileName: String) -> Unit
) {
    val context = LocalContext.current

    val cameraOutputUri = remember { createCameraUri(context) }
    var pendingDismiss by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) consumeUri(context, uri, onAvatarPicked)
        pendingDismiss = true
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraOutputUri != null) {
            consumeUri(context, cameraOutputUri, onAvatarPicked)
        }
        pendingDismiss = true
    }

    LaunchedEffect(pendingDismiss) {
        if (pendingDismiss) onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgElevated,
        dragHandle = { DialogDragHandle() }
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(
                "Сменить аватар",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(16.dp))

            AvatarPickerRow(
                icon = Icons.Outlined.Image,
                label = "Выбрать из галереи",
                onClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            )
            AvatarPickerRow(
                icon = Icons.Outlined.CameraAlt,
                label = "Сделать снимок",
                onClick = {
                    if (cameraOutputUri != null) cameraLauncher.launch(cameraOutputUri)
                }
            )
        }
    }
}

@Composable
private fun AvatarPickerRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val feedback = com.example.otlhelper.core.feedback.LocalFeedback.current
    val hostView = androidx.compose.ui.platform.LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                feedback?.tap(hostView)
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

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun createCameraUri(context: Context): Uri? = try {
    val dir = File(context.cacheDir, "avatar_capture").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
} catch (_: Exception) { null }

/**
 * §TZ-2.3.18 — resize picked image DOWN to 256×256 JPEG 85% before upload.
 *
 * 1) ImageDecoder (API 28+) — корректно жуёт GIF/HEIF/WebP, animated picks
 *    first frame. Рекомендуемый путь на 95% устройств.
 * 2) BitmapFactory fallback для старых устройств / редких форматов.
 * 3) Если оба упали — Toast с ошибкой + лог.
 * 4) Downscale до 256×256, JPEG 85% → ~20-40 КБ.
 */
private fun consumeUri(
    context: Context,
    uri: Uri,
    onAvatarPicked: (ByteArray, String, String) -> Unit
) {
    try {
        val resized = resizeImageForAvatar(context, uri)
        if (resized == null) {
            android.widget.Toast.makeText(
                context,
                "Не удалось обработать изображение. Попробуйте другой файл (JPEG/PNG).",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        android.util.Log.i("AvatarPicker", "resized bytes=${resized.size}")
        onAvatarPicked(resized, "image/jpeg", "avatar.jpg")
    } catch (e: Exception) {
        android.util.Log.e("AvatarPicker", "consumeUri failed", e)
        android.widget.Toast.makeText(
            context,
            "Ошибка обработки: ${e.message ?: e.javaClass.simpleName}",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

private const val AVATAR_TARGET_PX = 256
private const val AVATAR_JPEG_QUALITY = 85

private fun resizeImageForAvatar(context: Context, uri: Uri): ByteArray? {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        try {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val max = maxOf(info.size.width, info.size.height)
                if (max > AVATAR_TARGET_PX) {
                    val ratio = AVATAR_TARGET_PX.toFloat() / max
                    decoder.setTargetSize(
                        (info.size.width * ratio).toInt().coerceAtLeast(1),
                        (info.size.height * ratio).toInt().coerceAtLeast(1)
                    )
                }
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, out)
            bitmap.recycle()
            return out.toByteArray()
        } catch (e: Exception) {
            android.util.Log.w("AvatarPicker", "ImageDecoder failed, trying BitmapFactory fallback", e)
        }
    }

    return try {
        val boundsOpts = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, boundsOpts)
        } ?: return null
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var sampleSize = 1
        val maxDim = maxOf(srcW, srcH)
        while (maxDim / (sampleSize * 2) >= AVATAR_TARGET_PX) {
            sampleSize *= 2
        }

        val decodeOpts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val sampled = context.contentResolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, decodeOpts)
        } ?: return null

        val ratio = AVATAR_TARGET_PX.toFloat() / maxOf(sampled.width, sampled.height)
        val finalBitmap = if (ratio < 1f) {
            val w = (sampled.width * ratio).toInt().coerceAtLeast(1)
            val h = (sampled.height * ratio).toInt().coerceAtLeast(1)
            android.graphics.Bitmap.createScaledBitmap(sampled, w, h, true).also {
                if (it !== sampled) sampled.recycle()
            }
        } else sampled

        val out = java.io.ByteArrayOutputStream()
        finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, out)
        if (finalBitmap !== sampled) finalBitmap.recycle() else sampled.recycle()
        out.toByteArray()
    } catch (e: Exception) {
        android.util.Log.e("AvatarPicker", "BitmapFactory resize failed", e)
        null
    }
}
