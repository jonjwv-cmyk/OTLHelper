package com.example.otlhelper.desktop.ui.main.workspace

import com.example.otlhelper.desktop.data.network.ApiClient
import com.example.otlhelper.desktop.ui.main.DesktopAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.logging.Logger

/**
 * §TZ-DESKTOP-0.1.0 — построение JSONArray с data:URL для отправки вложений.
 *
 * Каждый элемент = `{file_url: "data:mime;base64,...", file_name, file_type, file_size}`.
 * Сервер `normalizeAttachments` парсит data:URL → encrypted blob в R2 + возвращает
 * URL с blob_key_b64/nonce_b64 для клиентов.
 *
 * Read errors на конкретном файле молча скипаются — лучше отправить
 * меньше вложений чем уронить весь send.
 */
internal fun buildAttachmentsPayload(items: List<DesktopAttachment>): JSONArray {
    val arr = JSONArray()
    for (item in items) {
        try {
            val bytes = item.file.readBytes()
            val b64 = Base64.getEncoder().encodeToString(bytes)
            val dataUrl = "data:${item.mimeType};base64,$b64"
            arr.put(
                JSONObject()
                    .put("file_url", dataUrl)
                    .put("file_name", item.name)
                    .put("file_type", item.mimeType)
                    .put("file_size", item.size),
            )
        } catch (_: Exception) { /* skip unreadable */ }
    }
    return arr
}

private val avatarLogger: Logger = Logger.getLogger("AvatarUpload")

/**
 * §TZ-DESKTOP-0.1.0 — загрузка собственной аватарки (set_avatar).
 *
 * Ограничение сервера — 500 KB, JPEG/PNG/WebP (не GIF/video). Файлы
 * крупнее лимита логируются и не отправляются.
 */
internal suspend fun uploadAvatar(file: File): Boolean = withContext(Dispatchers.IO) {
    try {
        val bytes = file.readBytes()
        if (bytes.size > 500 * 1024) {
            avatarLogger.warning("Avatar image is too large: ${bytes.size / 1024}KB > 500KB limit")
            return@withContext false
        }
        val mime = when (file.name.lowercase().substringAfterLast('.', "")) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val dataUrl = "data:$mime;base64,$b64"
        val resp = ApiClient.setAvatar(dataUrl, mime, file.name)
        resp.optBoolean("ok", false)
    } catch (_: Exception) { false }
}
