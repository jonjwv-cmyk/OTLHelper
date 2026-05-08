package com.example.otlhelper.desktop.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.TextTertiary

/**
 * §TZ-DESKTOP-0.1.0 — вертикальная «рейка» вкладок для свёрнутой панели
 * (64dp ширина). Тот же набор что в [BottomTabBar]: Menu / Новости / Чаты —
 * иконка + подпись под ней + бейдж непрочитанных. Визуально кнопки
 * «переезжают» из горизонтального нижнего бара в вертикальную рейку.
 */
@Composable
fun CompactTabRail(
    activeTab: Tab,
    menuOpen: Boolean,
    newsUnread: Int,
    chatsUnread: Int,
    onMenuClick: () -> Unit,
    onTabSelected: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RailSlot(
            icon = Icons.Outlined.Menu,
            label = "Меню",
            active = menuOpen,
            badge = 0,
            onClick = onMenuClick,
        )
        RailSlot(
            icon = Icons.Outlined.Newspaper,
            label = "Новости",
            active = activeTab == Tab.News && !menuOpen,
            badge = newsUnread,
            onClick = { onTabSelected(Tab.News) },
        )
        RailSlot(
            icon = Icons.Outlined.ChatBubble,
            label = "Чаты",
            active = activeTab == Tab.Chats && !menuOpen,
            badge = chatsUnread,
            onClick = { onTabSelected(Tab.Chats) },
        )
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun RailSlot(
    icon: ImageVector,
    label: String,
    active: Boolean,
    badge: Int,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (active) Accent else TextTertiary,
        animationSpec = tween(200),
        label = "railSlot",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (active) AccentSubtle else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            if (badge > 0) {
                UnreadBadge(
                    count = badge,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(6.dp, (-4).dp),
                    small = true,
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        // §TZ-DESKTOP 0.2.0 — 9sp был нечитабелен на retina. Минимум 10sp.
        Text(
            label,
            color = tint,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
