package com.example.otlhelper.presentation.home.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.otlhelper.core.theme.AccentSubtle
import com.example.otlhelper.core.theme.AvatarBg
import com.example.otlhelper.core.theme.BgElevated
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.StatusErrorBorder
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.ui.animations.AppMotion
import com.example.otlhelper.core.ui.components.DialogDragHandle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionMenuDialog(
    isAdmin: Boolean,
    isSuperAdmin: Boolean,
    pollsFeatureEnabled: Boolean = true,
    userName: String,
    userRole: String,
    avatarUrl: String = "",
    presenceStatus: String = "offline",
    onDismiss: () -> Unit,
    onCreatePoll: () -> Unit,
    onShowAccount: () -> Unit,
    onShowSettings: () -> Unit,
    onSystemControl: () -> Unit,
    onManageUsers: () -> Unit,
    onShowAuditLog: () -> Unit = {},
    onShowAppStats: () -> Unit = {},
    onShowScheduled: () -> Unit = {},
    onPcLogin: () -> Unit = {},
    onLogout: () -> Unit
) {
    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { contentVisible = true }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgElevated,
        dragHandle = { DialogDragHandle() }
    ) {
        AnimatedVisibility(
            visible = contentVisible,
            enter = AppMotion.ScaleInSheet,
            exit = AppMotion.ScaleOutSheet
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                // ── Profile header ───────────────────────────────────────
                val initials = userName
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .take(2)
                    .joinToString("") { it.first().uppercaseChar().toString() }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onShowAccount)
                        .padding(vertical = 12.dp)
                ) {
                    Box(modifier = Modifier.size(44.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(
                                    com.example.otlhelper.core.theme.avatarColor(userName)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (avatarUrl.isNotBlank()) {
                                val context = LocalContext.current
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(avatarUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = userName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                )
                            } else {
                                Text(
                                    text = initials.ifBlank { "?" },
                                    color = TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        // Presence indicator — bottom-right, same palette as feed avatars
                        val dotColor = when (presenceStatus) {
                            "online" -> com.example.otlhelper.core.theme.UnreadGreen
                            "paused" -> com.example.otlhelper.core.theme.PresencePaused
                            else -> androidx.compose.ui.graphics.Color(0xFF555555)
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                                .border(1.5.dp, BgElevated, CircleShape)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = userName.ifBlank { "—" },
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (userRole.isNotBlank()) {
                            Text(
                                text = userRole,
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                MenuDivider()

                // ── Content actions ──────────────────────────────────────
                if (isAdmin && pollsFeatureEnabled) {
                    MenuRow(Icons.Outlined.BarChart, "Создать опрос", onCreatePoll)
                }
                if (isAdmin) {
                    MenuRow(Icons.Outlined.Schedule, "Запланированные", onShowScheduled)
                }

                if (isAdmin && pollsFeatureEnabled) MenuDivider()

                // ── Account ──────────────────────────────────────────────
                MenuRow(Icons.Outlined.Person, "Аккаунт", onShowAccount)
                MenuRow(Icons.Outlined.Settings, "Настройки", onShowSettings)

                // ── Admin tools ──────────────────────────────────────────
                if (isSuperAdmin) {
                    MenuDivider()
                    SectionLabel("Администрирование")
                    MenuRow(Icons.Outlined.Build, "Управление системой", onSystemControl)
                    MenuRow(Icons.Outlined.Group, "Пользователи", onManageUsers)
                    MenuRow(Icons.Outlined.History, "Аудит действий", onShowAuditLog)
                    MenuRow(Icons.AutoMirrored.Outlined.TrendingUp, "Статистика", onShowAppStats)
                }

                // §TZ-0.10.5/2.5.2 — QR-вход на ПК (developer/admin only).
                if (isAdmin) {
                    MenuDivider()
                    MenuRow(Icons.Outlined.QrCodeScanner, "Войти на ПК", onPcLogin)
                }

                MenuDivider()

                // ── Danger ───────────────────────────────────────────────
                MenuRow(
                    Icons.AutoMirrored.Outlined.Logout,
                    "Выйти",
                    onLogout,
                    tint = StatusErrorBorder
                )
            }
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = TextPrimary
) {
    // §TZ-2.3.7 — тактилика на каждый пункт меню.
    val menuFeedback = com.example.otlhelper.core.feedback.LocalFeedback.current
    val menuHost = androidx.compose.ui.platform.LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                menuFeedback?.tap(menuHost)
                onClick()
            }
            .padding(horizontal = 4.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(AccentSubtle, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = tint,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = TextTertiary,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp)
    )
}

@Composable
private fun MenuDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(0.5.dp)
            .background(BorderDivider)
    )
}
