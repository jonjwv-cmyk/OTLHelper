package com.example.otlhelper.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextPrimary

data class ContextMenuItem(
    val icon: ImageVector,
    val label: String,
    val tint: Color = TextPrimary,
    val onClick: () -> Unit,
)

/**
 * §TZ-DESKTOP-0.1.0 — маленький floating-popup меню по правому клику
 * (desktop-аналог long-press). Закрывается кликом по дим-фону.
 * На этапе 5 полноценно подключится к NewsCard и ChatRow.
 */
@Composable
fun ContextMenu(
    items: List<ContextMenuItem>,
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
                .clip(RoundedCornerShape(12.dp))
                .background(BgElevated)
                .border(0.5.dp, BorderDivider, RoundedCornerShape(12.dp))
                .padding(vertical = 6.dp)
                .clickable(enabled = false) {},
        ) {
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .clickable {
                            item.onClick()
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = item.tint,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(item.label, color = item.tint, fontSize = 13.sp)
                }
            }
        }
    }
}
