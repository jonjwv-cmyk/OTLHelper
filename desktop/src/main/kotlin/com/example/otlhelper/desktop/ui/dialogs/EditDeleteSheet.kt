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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.StatusErrorBorder
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextTertiary
import com.example.otlhelper.desktop.ui.components.ThinDivider

/**
 * §TZ-DESKTOP-0.1.0 — копия app/presentation/home/dialogs/EditDeleteSheet.kt.
 * На desktop — bottom sheet внутри WorkspacePanel'и.
 */
@Composable
fun EditDeleteSheet(
    canEdit: Boolean,
    canDelete: Boolean,
    canPin: Boolean = false,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(BgElevated)
                .border(
                    0.5.dp,
                    BorderDivider,
                    RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                )
                .clickable(enabled = false) {}
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 28.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(34.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(BorderDivider),
            )
            Spacer(Modifier.height(14.dp))

            if (canPin) {
                SheetActionRow(Icons.Outlined.PushPin, "Закрепить", onClick = { onPin(); onDismiss() })
            }
            if (canEdit) {
                SheetActionRow(Icons.Outlined.Edit, "Изменить", onClick = { onEdit(); onDismiss() })
            }
            if (canDelete) {
                SheetActionRow(
                    Icons.Outlined.Delete,
                    "Удалить",
                    tint = StatusErrorBorder,
                    onClick = { onDelete(); onDismiss() },
                )
            }
            if (!canEdit && !canDelete && !canPin) {
                Text(
                    "Нет доступных действий",
                    color = TextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            ThinDivider()
            Spacer(Modifier.height(4.dp))
            SheetActionRow(Icons.Outlined.Close, "Закрыть", onClick = onDismiss)
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    tint: Color = TextPrimary,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(AccentSubtle, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = tint, style = MaterialTheme.typography.bodyMedium)
    }
}
