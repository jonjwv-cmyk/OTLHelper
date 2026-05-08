package com.example.otlhelper.presentation.home.dialogs.user_management

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AccentSubtle
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BgElevated
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.PresencePaused
import com.example.otlhelper.core.theme.StatusErrorBorder
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.theme.UnreadGreen
import com.example.otlhelper.core.ui.UserAvatar
import com.example.otlhelper.domain.model.Role
import com.example.otlhelper.domain.model.displayName
import com.example.otlhelper.domain.model.wireName
import com.example.otlhelper.presentation.home.HomeViewModel
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserRow(
    user: JSONObject,
    viewModel: HomeViewModel,
    onStatusChange: (String) -> Unit,
) {
    val login = user.optString("login", "")
    val fullName = user.optString("full_name", login)
    val role = user.optString("role", Role.User.wireName())
    val isActive = user.optInt("is_active", 1) != 0
    val isSuspended = user.optInt("is_suspended", 0) != 0
    val isEnabled = isActive && !isSuspended
    val presence = user.optString("presence_status", "offline")
    val lastSeenAt = user.optString("last_seen_at", "")
    val avatarUrl = com.example.otlhelper.core.security.blobAwareUrl(user, "avatar_url")

    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showChangeLoginDialog by remember { mutableStateOf(false) }
    var showChangeRoleDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCard)
            .border(0.5.dp, BorderDivider, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            avatarUrl = avatarUrl,
            name = fullName,
            // Suspended / deactivated users shouldn't render a live presence
            // halo on their avatar — mute the dot to the offline grey.
            presenceStatus = if (isEnabled) presence else "offline",
            size = 40.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Name on its own line, wraps to 2 (Linear/Notion/Slack-style).
            // Role chip lives on the metadata row below, not crammed next to the name.
            Text(
                fullName,
                color = if (isEnabled) TextPrimary else TextTertiary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                softWrap = true,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoleBadge(role)
                if (Role.fromString(role) != Role.User) {
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = when {
                        isSuspended -> "Заблокирован"
                        !isActive -> "Неактивен"
                        else -> "Активен"
                    },
                    color = when {
                        isSuspended -> StatusErrorBorder
                        !isActive -> TextTertiary
                        else -> UnreadGreen
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                if (isEnabled) {
                    // Presence dot lives on the avatar now — the label here
                    // carries only the word + relative-time fallback.
                    Text(" · ", color = TextTertiary, style = MaterialTheme.typography.labelSmall)
                    Text(
                        when (presence) {
                            "online" -> "онлайн"
                            "paused" -> "был(а) недавно"
                            else -> formatLastSeen(lastSeenAt)
                        },
                        color = when (presence) {
                            "online" -> UnreadGreen
                            "paused" -> PresencePaused
                            else -> TextTertiary
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                containerColor = BgElevated,
                shape = RoundedCornerShape(14.dp),
            ) {
                MenuItemRow(Icons.Outlined.Edit, "Переименовать") { showMenu = false; showRenameDialog = true }
                MenuItemRow(Icons.AutoMirrored.Outlined.Login, "Сменить логин") { showMenu = false; showChangeLoginDialog = true }
                MenuItemRow(Icons.Outlined.Shield, "Сменить роль") { showMenu = false; showChangeRoleDialog = true }
                MenuItemRow(
                    if (isEnabled) Icons.Outlined.Block else Icons.Outlined.CheckCircle,
                    if (isEnabled) "Заблокировать" else "Разблокировать"
                ) {
                    showMenu = false
                    viewModel.toggleUserAdmin(login) { ok -> onStatusChange(if (ok) "Статус изменён" else "Ошибка") }
                }
                MenuItemRow(Icons.Outlined.Key, "Сбросить пароль") { showMenu = false; showResetDialog = true }
                HorizontalDivider(color = BorderDivider, modifier = Modifier.padding(vertical = 4.dp))
                MenuItemRow(Icons.Outlined.Delete, "Удалить", tint = StatusErrorBorder) { showMenu = false; showDeleteConfirm = true }
            }
        }
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(fullName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Переименовать", color = TextPrimary) },
            text = {
                OtlField(
                    value = newName,
                    label = "ФИО",
                    onValueChange = { newName = it },
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameUser(login, newName) { ok -> onStatusChange(if (ok) "Имя обновлено" else "Ошибка"); showRenameDialog = false }
                }) { Text("Сохранить", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Отмена", color = TextSecondary) } },
            containerColor = BgElevated
        )
    }

    if (showResetDialog) {
        var newPass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Сброс пароля", color = TextPrimary) },
            text = { OtlField(value = newPass, label = "Новый пароль (необязательно)", onValueChange = { newPass = it }, isPassword = true) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetPasswordAdmin(login, newPass) { ok -> onStatusChange(if (ok) "Пароль сброшен" else "Ошибка"); showResetDialog = false }
                }) { Text("Сбросить", color = StatusErrorBorder) }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("Отмена", color = TextSecondary) } },
            containerColor = BgElevated
        )
    }

    if (showChangeLoginDialog) {
        var newLogin by remember { mutableStateOf(login) }
        var errorText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showChangeLoginDialog = false },
            title = { Text("Сменить логин", color = TextPrimary) },
            text = {
                Column {
                    OtlField(value = newLogin, label = "Новый логин", onValueChange = { newLogin = it; errorText = "" })
                    if (errorText.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(errorText, color = StatusErrorBorder, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.changeUserLogin(login, newLogin.trim()) { ok, err ->
                        if (ok) {
                            onStatusChange("Логин изменён"); showChangeLoginDialog = false
                        } else errorText = err.ifBlank { "Ошибка" }
                    }
                }) { Text("Сохранить", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { showChangeLoginDialog = false }) { Text("Отмена", color = TextSecondary) } },
            containerColor = BgElevated
        )
    }

    if (showChangeRoleDialog) {
        var selectedRole by remember { mutableStateOf(Role.fromString(role)) }
        // §TZ-2.3.38 — роль Client добавлена в выбор смены ролей.
        val availableRoles = listOf(Role.User, Role.Client, Role.Admin, Role.Developer)
        AlertDialog(
            onDismissRequest = { showChangeRoleDialog = false },
            title = { Text("Сменить роль", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Текущая: ${Role.fromString(role).displayName()}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    availableRoles.forEach { r ->
                        FilterChip(
                            selected = selectedRole == r,
                            onClick = { selectedRole = r },
                            label = { Text(r.displayName(), style = MaterialTheme.typography.bodyMedium) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentSubtle,
                                selectedLabelColor = Accent,
                                containerColor = BgCard,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.changeUserRole(login, selectedRole.wireName()) { ok -> onStatusChange(if (ok) "Роль изменена" else "Ошибка"); showChangeRoleDialog = false }
                }) { Text("Сохранить", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { showChangeRoleDialog = false }) { Text("Отмена", color = TextSecondary) } },
            containerColor = BgElevated
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить пользователя?", color = TextPrimary) },
            text = {
                Text(
                    "Пользователь \"$fullName\" будет удалён. Сообщения сохранятся на сервере 24 часа, затем будут очищены.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteUserAdmin(login) { ok ->
                        onStatusChange(if (ok) "Пользователь удалён" else "Ошибка удаления")
                        showDeleteConfirm = false
                    }
                }) { Text("Удалить", color = StatusErrorBorder) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена", color = TextSecondary) } },
            containerColor = BgElevated
        )
    }
}
