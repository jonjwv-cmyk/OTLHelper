package com.example.otlhelper.desktop.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextTertiary

/** §TZ-DESKTOP-0.1.0 — миниатюры вложений над InputBar (аналог AttachmentThumbnailRow). */
@Composable
fun AttachmentThumbnailRow(
    attachments: List<DesktopAttachment>,
    onRemove: (DesktopAttachment) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgCard)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { a ->
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgElevated),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (a.kind) {
                        "image" -> "🖼"
                        "video" -> "🎥"
                        "audio" -> "🎵"
                        else -> "📄"
                    },
                    fontSize = 22.sp,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(BgCard)
                        .clickable { onRemove(a) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Убрать",
                        tint = TextTertiary,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
        }
    }
}
