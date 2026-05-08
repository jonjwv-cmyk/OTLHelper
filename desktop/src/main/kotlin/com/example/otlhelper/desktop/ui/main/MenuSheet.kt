package com.example.otlhelper.desktop.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.otlhelper.desktop.model.Role
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgCardHover
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.StatusErrorBorder
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.ui.components.cursorGlow
import com.example.otlhelper.desktop.ui.components.UserAvatar
import com.example.otlhelper.desktop.ui.dialogs.BottomSheetShell

/**
 * §TZ-DESKTOP-0.1.0 — копия [ActionMenuDialog].
 *
 * Роль определяется СЕРВЕРОМ при логине и дальше не меняется —
 * никакого role-switcher'а в UI. Набор пунктов зависит от role:
 *
 * • Developer: Создать опрос, Запланированные, Аккаунт, Настройки, Пользователи
 * • Administrator: Создать опрос, Запланированные, Аккаунт, Настройки
 * • User/Client: Аккаунт, Настройки
 * • Все: Выйти (red)
 *
 * Шторка открывается SF2026-стилем с кнопкой «← Назад» сверху.
 */
@Composable
fun MenuSheet(
    login: String,
    fullName: String,
    avatarUrl: String,
    role: Role,
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
    onCreatePoll: () -> Unit,
    onShowScheduled: () -> Unit,
    onShowAccount: () -> Unit,
    onShowSettings: () -> Unit,
    onShowUsers: () -> Unit,
) {
    BottomSheetShell(onDismiss = onDismiss, title = "Меню", onBack = onDismiss) {
        Spacer(Modifier.height(6.dp))

        val displayName = fullName.ifBlank { login }

        // Profile header — клик открывает «Аккаунт»
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onShowAccount(); onDismiss() }
                .padding(vertical = 12.dp),
        ) {
            UserAvatar(
                name = displayName,
                avatarUrl = avatarUrl,
                presenceStatus = "online",
                size = 44.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    displayName.ifBlank { "—" },
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    role.displayName,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        MenuDivider()

        // ── Admin actions (dev + admin) ──
        if (role.canCreateNews) {
            MenuRow(Icons.Outlined.BarChart, "Создать опрос") { onCreatePoll() }
        }
        if (role.canScheduleMessages) {
            MenuRow(Icons.Outlined.Schedule, "Запланированные") { onShowScheduled() }
        }
        if (role.canCreateNews) MenuDivider()

        // ── Для всех ролей ──
        MenuRow(Icons.Outlined.Person, "Аккаунт") { onShowAccount() }
        MenuRow(Icons.Outlined.Settings, "Настройки") { onShowSettings() }

        // ── Только developer: пользователи ──
        if (role.canManageUsers) {
            MenuDivider()
            MenuRow(Icons.Outlined.Group, "Пользователи") { onShowUsers() }
        }

        MenuDivider()

        MenuRow(
            Icons.AutoMirrored.Outlined.Logout,
            "Выйти",
            tint = StatusErrorBorder,
            onClick = { onLogout() },
        )
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    tint: Color = TextPrimary,
    onClick: () -> Unit,
) {
    // §TZ-DESKTOP-0.10.1 — hover state. На наведении: фон строки тинт'ится
    // BgCardHover, иконочный бокс из AccentSubtle → ярче (Accent с alpha 0.28),
    // иконка/текст становятся ярче (Accent для tint=TextPrimary). Юзер просил
    // подсветку при наведении — реализовано.
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()

    val rowBg by animateColorAsState(
        targetValue = if (isHovered) BgCardHover else Color.Transparent,
        animationSpec = tween(150),
        label = "menuRowBg",
    )
    val iconBoxBg by animateColorAsState(
        targetValue = if (isHovered) Accent.copy(alpha = 0.28f) else AccentSubtle,
        animationSpec = tween(150),
        label = "menuIconBoxBg",
    )
    // Если default tint = TextPrimary, на hover усиливаем до Accent. Иначе
    // (например красный Logout) hover не меняет цвет — только фон.
    val effectiveTint by animateColorAsState(
        targetValue = if (isHovered && tint == TextPrimary) Accent else tint,
        animationSpec = tween(150),
        label = "menuRowTint",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(rowBg)
            // §TZ-DESKTOP-0.10.2 — radial glow за курсором (видно как мышь
            // движется по кнопке) + hover state. Glow цвет = tint (Accent
            // для обычных кнопок, StatusErrorBorder для Logout).
            .cursorGlow(interaction = interaction, glowColor = if (tint == TextPrimary) Accent else tint)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(iconBoxBg, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = effectiveTint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = effectiveTint, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MenuDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(0.5.dp)
            .background(BorderDivider),
    )
}
