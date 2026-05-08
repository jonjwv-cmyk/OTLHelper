package com.example.otlhelper.presentation.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary

/**
 * Composer-bar reply preview — sits above InputBar while the user is
 * composing a reply. Shares visual language (accent stripe + sender +
 * text preview) with the in-bubble reply block inside ChatBubble.
 */
@Composable
internal fun ReplyPreviewBar(
    senderName: String,
    text: String,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgCard)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(36.dp)
                .background(Accent, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Ответ — ${senderName.ifBlank { "сообщение" }}",
                color = Accent,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
            Text(
                text.ifBlank { "сообщение" }.take(80),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Отменить ответ",
                tint = TextTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
