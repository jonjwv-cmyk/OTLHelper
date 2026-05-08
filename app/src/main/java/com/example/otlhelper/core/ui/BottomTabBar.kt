package com.example.otlhelper.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.BgApp
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.ui.components.ThinDivider
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.theme.UnreadGreen
import com.example.otlhelper.presentation.home.HomeTab

/**
 * Bottom navigation dock — 4 items: Menu, News, Search, Monitoring.
 *
 * Menu is a "modal trigger", not a real screen — tapping it opens the
 * action sheet without changing the active tab highlight.
 */
@Composable
fun BottomTabBar(
    activeTab: HomeTab,
    newsUnreadCount: Int,
    monitoringUnreadCount: Int,
    monitoringTabLabel: String,
    onTabSelected: (HomeTab) -> Unit,
    onMenuClick: () -> Unit,
    // §TZ-2.3.38 — для роли `client` вкладка Новости скрыта. Вся остальная
    // навигация (Меню / Поиск / Чат) работает как у user.
    showNewsTab: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ThinDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgCard)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Menu — opens ActionMenuDialog; never acts as active tab
            TabItem(
                icon = Icons.Outlined.Menu,
                label = "Меню",
                isActive = false,
                badge = 0,
                onClick = onMenuClick,
                modifier = Modifier.weight(1f)
            )
            if (showNewsTab) {
                TabItem(
                    icon = Icons.Outlined.Newspaper,
                    label = "Новости",
                    isActive = activeTab == HomeTab.NEWS,
                    badge = newsUnreadCount,
                    onClick = { onTabSelected(HomeTab.NEWS) },
                    modifier = Modifier.weight(1f)
                )
            }
            TabItem(
                icon = Icons.Outlined.Search,
                label = "Поиск",
                isActive = activeTab == HomeTab.SEARCH,
                badge = 0,
                onClick = { onTabSelected(HomeTab.SEARCH) },
                modifier = Modifier.weight(1f)
            )
            TabItem(
                icon = Icons.Outlined.ChatBubble,
                label = monitoringTabLabel,
                isActive = activeTab == HomeTab.MONITORING,
                badge = monitoringUnreadCount,
                onClick = { onTabSelected(HomeTab.MONITORING) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TabItem(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasBadge = badge > 0

    val iconTint by animateColorAsState(
        targetValue = when {
            isActive -> Accent
            hasBadge -> TextSecondary
            else -> TextTertiary
        },
        animationSpec = tween(200), label = "tabIcon"
    )

    val labelColor by animateColorAsState(
        targetValue = when {
            isActive -> TextPrimary
            hasBadge -> TextSecondary
            else -> TextTertiary
        },
        animationSpec = tween(200), label = "tabLabel"
    )

    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
            if (hasBadge) {
                val text = if (badge > 99) "99+" else badge.toString()
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 10.dp, y = (-6).dp)
                        .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                        .background(UnreadGreen, CircleShape)
                        .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = text,
                        color = BgApp,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            letterSpacing = 0.2.sp
        )
    }
}
