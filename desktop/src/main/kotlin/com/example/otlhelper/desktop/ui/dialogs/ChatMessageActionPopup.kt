package com.example.otlhelper.desktop.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.StatusErrorBorder
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.ui.components.ThinDivider

/**
 * §TZ-DESKTOP-0.1.0 — Reactions whitelist, зеркалит domain/model/Reactions.ALLOWED.
 * §TZ-DESKTOP 0.3.3 — ❤ с VS16 (\uFE0F) для colored варианта (тот же fix что в
 * ui.components.ReactionsBar.AllowedEmojis). Второй список существует потому
 * что этот popup показывает 5 quick-emoji в правом-клике контекстном меню —
 * независимо от reactions-chip'ов под сообщением.
 */
val AllowedReactions = listOf("👍", "❤\uFE0F", "😂", "🎉", "✅")

/**
 * §TZ-DESKTOP-0.1.0 — копия app/presentation/home/dialogs/ChatMessageActionPopup.kt.
 * Floating popup: quick-reactions row сверху + actions (Ответить / Копировать / Изменить / Удалить).
 * На desktop показывается по центру (нет anchor'а к bubble как на Android — попап всегда видимый).
 */
@Composable
fun ChatMessageActionPopup(
    myReactions: Set<String>,
    canReply: Boolean = true,
    canCopy: Boolean = true,
    canEdit: Boolean = false,
    canDelete: Boolean = false,
    onReact: (emoji: String, alreadyReacted: Boolean) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 240.dp, max = 320.dp)
                .shadow(elevation = 12.dp, shape = RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(BgElevated)
                .border(0.5.dp, BorderDivider, RoundedCornerShape(14.dp))
                .clickable(enabled = false) {},
        ) {
            // Quick reactions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (emoji in AllowedReactions) {
                    val selected = emoji in myReactions
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .then(
                                if (selected) Modifier.background(AccentSubtle) else Modifier,
                            )
                            .clickable {
                                onReact(emoji, selected)
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        // §TZ-DESKTOP 0.3.3 — fontFamily = FontFamily.Default
                        // для цветного emoji. Без этого Inter (активный
                        // MaterialTheme fontFamily) берёт ❤ U+2764 глифом из
                        // собственной таблицы → монохром. С Default Skia
                        // запрашивает у системы colored emoji font (Apple
                        // Color Emoji / Segoe UI Emoji) → красное сердечко.
                        Text(
                            emoji,
                            fontFamily = FontFamily.Default,
                            fontSize = 22.sp,
                        )
                    }
                }
            }

            ThinDivider()

            if (canReply) {
                ActionRow(Icons.AutoMirrored.Outlined.Reply, "Ответить") {
                    onReply(); onDismiss()
                }
            }
            if (canCopy) {
                ActionRow(Icons.Outlined.ContentCopy, "Копировать") {
                    onCopy(); onDismiss()
                }
            }
            if (canEdit) {
                ActionRow(Icons.Outlined.Edit, "Изменить") {
                    onEdit(); onDismiss()
                }
            }
            if (canDelete) {
                ActionRow(Icons.Outlined.Delete, "Удалить", tint = StatusErrorBorder) {
                    onDelete(); onDismiss()
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    tint: Color = TextPrimary,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Normal)
    }
}
