package com.example.otlhelper.desktop.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgApp
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.TextTertiary
import com.example.otlhelper.desktop.theme.UnreadGreen
import com.example.otlhelper.desktop.ui.components.Pill
import com.example.otlhelper.desktop.ui.components.PillSize

/**
 * §TZ-DESKTOP-0.1.0 — точная копия Android BottomTabBar.
 * Три слота слева направо: Настройки · Новости · Чаты.
 * На иконках «Новости» и «Чаты» — badge с общим счётчиком непрочитанных.
 */
@Composable
fun BottomTabBar(
    activeTab: Tab,
    menuOpen: Boolean,
    newsUnread: Int,
    chatsUnread: Int,
    onTabSelected: (Tab) -> Unit,
    onMenuClick: () -> Unit,
) {
    // §TZ-DESKTOP 0.3.1 — ещё тоньше по фидбэку ("большие кнопки навигации").
    // Row vertical 4→2dp + TabSlot 6→3dp + icon 22→20dp + label 11→10sp.
    // Общая высота: ~55 → ~44dp.
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(BorderDivider))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgCard)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabSlot(
                icon = Icons.Outlined.Menu,
                label = "Настройки",
                active = menuOpen,
                badge = 0,
                onClick = onMenuClick,
                modifier = Modifier.weight(1f),
            )
            TabSlot(
                icon = Icons.Outlined.Newspaper,
                label = "Новости",
                active = activeTab == Tab.News && !menuOpen,
                badge = newsUnread,
                onClick = { onTabSelected(Tab.News) },
                modifier = Modifier.weight(1f),
            )
            TabSlot(
                icon = Icons.Outlined.ChatBubble,
                label = "Чаты",
                active = activeTab == Tab.Chats && !menuOpen,
                badge = chatsUnread,
                onClick = { onTabSelected(Tab.Chats) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TabSlot(
    icon: ImageVector,
    label: String,
    active: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(
        targetValue = if (active) Accent else TextTertiary,
        animationSpec = tween(200),
        label = "tabSlot",
    )
    // §TZ-DESKTOP 0.3.1 — padding 6→3dp, icon 22→20dp, label 11→10sp.
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            if (badge > 0) {
                UnreadBadge(
                    count = badge,
                    modifier = Modifier.offset(6.dp, (-4).dp),
                    small = true,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = tint,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** §TZ-DESKTOP 0.2.0 — badge-пилюля через общий [Pill]-компонент. small=true для
 *  компактной рейки (9sp было нечитабельно → теперь 10sp). */
@Composable
fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    Pill(
        text = if (count > 99) "99+" else count.toString(),
        containerColor = UnreadGreen,
        contentColor = BgApp,
        modifier = modifier,
        size = if (small) PillSize.Sm else PillSize.Md,
    )
}

