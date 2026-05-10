package com.example.otlhelper.desktop.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.data.users.UsersRepository
import com.example.otlhelper.desktop.model.Role
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BgInput
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.StatusErrorBorder
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import com.example.otlhelper.desktop.ui.components.UserAvatar

/**
 * §TZ-DESKTOP-0.1.0 — управление пользователями (admin+).
 *
 * - Список из [UsersRepository.State] (загружается при открытии).
 * - Actions per user: Сменить логин / Переименовать / Сбросить пароль /
 *   Сменить роль / Удалить. superadmin-only checks ведутся на сервере.
 * - Создание пользователя — простая форма (login, full_name, role, password).
 */
@Composable
fun UserManagementSheet(
    state: UsersRepository.State,
    currentRole: Role,
    onCreate: (login: String, fullName: String, password: String, role: String, mustChange: Boolean) -> Unit,
    onChangeRole: (targetLogin: String, newRole: String) -> Unit,
    onResetPassword: (targetLogin: String, newPassword: String) -> Unit,
    onChangeLogin: (targetLogin: String, newLogin: String) -> Unit,
    onChangeFullName: (targetLogin: String, newName: String) -> Unit = { _, _ -> },
    onDelete: (targetLogin: String) -> Unit,
    onResetPasswordCounter: (targetLogin: String) -> Unit = {},
    onDismiss: () -> Unit,
    onBack: () -> Unit = onDismiss,
) {
    var actionsFor by remember { mutableStateOf<UsersRepository.User?>(null) }
    var createOpen by remember { mutableStateOf(false) }

    BottomSheetShell(onDismiss = onDismiss, title = "Пользователи", onBack = onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val label = when {
                state.isLoading && state.users.isEmpty() -> "Загрузка..."
                state.lastError.isNotBlank() && state.users.isEmpty() -> "Ошибка: ${state.lastError}"
                else -> "${state.users.size} пользователей"
            }
            Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { createOpen = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Add, null, tint = Accent)
            }
        }
        Spacer(Modifier.height(12.dp))

        if (state.isLoading && state.users.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
        } else {
            state.users.forEach { u ->
                UserRow(u, onActions = { actionsFor = u })
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    actionsFor?.let { u ->
        UserActionsSheet(
            user = u,
            currentRole = currentRole,
            onDismiss = { actionsFor = null },
            onChangeRole = { newRole -> onChangeRole(u.login, newRole); actionsFor = null },
            onResetPassword = { newPass -> onResetPassword(u.login, newPass); actionsFor = null },
            onChangeLogin = { newLogin -> onChangeLogin(u.login, newLogin); actionsFor = null },
            onChangeFullName = { newName -> onChangeFullName(u.login, newName); actionsFor = null },
            onDelete = { onDelete(u.login); actionsFor = null },
            onResetPasswordCounter = { onResetPasswordCounter(u.login); actionsFor = null },
        )
    }

    if (createOpen) {
        CreateUserSheet(
            currentRole = currentRole,
            onDismiss = { createOpen = false },
            onConfirm = { login, fullName, password, role, mustChange ->
                onCreate(login, fullName, password, role, mustChange)
                createOpen = false
            },
        )
    }
}

@Composable
private fun UserRow(u: UsersRepository.User, onActions: () -> Unit) {
    // §0.10.26 — формат как в Android: ФИО + RoleBadge + presence label
    // (online / был(а) недавно / formatLastSeen) + status. Login убран —
    // юзеры не оперируют им в UI, видна только реальная информация о
    // присутствии и роли. Точка статуса (зелёная/жёлтая/серая) — на avatar.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCard)
            .border(0.5.dp, BorderDivider, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(
            name = u.fullName,
            avatarUrl = u.avatarUrl,
            presenceStatus = if (u.isActive) u.presence else "offline",
            size = 36.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                u.fullName.ifBlank { u.login },
                color = if (u.isActive) TextPrimary else TextTertiary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoleBadge(u.role)
                Spacer(Modifier.width(6.dp))
                val presenceLabel = when {
                    !u.isActive -> "заблокирован"
                    u.presence == "online" -> "онлайн"
                    u.presence == "paused" -> "был(а) недавно"
                    else -> formatLastSeen(u.lastSeenAt)
                }
                val presenceColor = when {
                    !u.isActive -> StatusErrorBorder
                    u.presence == "online" -> com.example.otlhelper.desktop.theme.UnreadGreen
                    u.presence == "paused" -> com.example.otlhelper.desktop.theme.PresencePaused
                    else -> TextTertiary
                }
                Text(
                    presenceLabel,
                    color = presenceColor,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onActions, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.MoreVert, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
        }
    }
}

/** §0.10.26 — last-seen timestamp humanized. Копирует логику Android
 *  UserManagementSupport.formatLastSeen для UI consistency. */
private fun formatLastSeen(raw: String): String {
    if (raw.isBlank()) return "оффлайн"
    return try {
        val iso = if (raw.contains('T')) {
            if (raw.endsWith("Z") || raw.contains('+')) raw else "${raw}Z"
        } else {
            "${raw.replace(' ', 'T')}Z"
        }
        val zdt = java.time.ZonedDateTime.parse(iso)
        val diff = java.time.Duration.between(zdt.toInstant(), java.time.Instant.now())
        val mins = diff.toMinutes()
        when {
            mins < 1 -> "только что"
            mins < 60 -> "${mins} мин назад"
            mins < 24 * 60 -> "${mins / 60}ч назад"
            mins < 7 * 24 * 60 -> "${mins / (24 * 60)} дн назад"
            else -> {
                val yek = zdt.withZoneSameInstant(java.time.ZoneId.of("Asia/Yekaterinburg"))
                yek.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
            }
        }
    } catch (_: Exception) {
        "оффлайн"
    }
}

@Composable
private fun RoleBadge(role: String) {
    // §0.11.2 — обычные юзеры (USER) не получают badge — пустое место.
    // Только DEV/ADM/CLI отмечены. SADM (superadmin) трактуется как DEV
    // (developer-level access). Совпадает с Android паттерном.
    val label = when (role.lowercase()) {
        "developer", "superadmin" -> "DEV"
        "admin" -> "ADM"
        "client" -> "CLI"
        else -> return  // user — без badge
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(AccentSubtle)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(label, color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun UserActionsSheet(
    user: UsersRepository.User,
    currentRole: Role,
    onDismiss: () -> Unit,
    onChangeRole: (String) -> Unit,
    onResetPassword: (String) -> Unit,
    onChangeLogin: (String) -> Unit,
    onChangeFullName: (String) -> Unit,
    onDelete: () -> Unit,
    onResetPasswordCounter: () -> Unit = {},
) {
    var roleChangeOpen by remember { mutableStateOf(false) }
    var resetPasswordOpen by remember { mutableStateOf(false) }
    var changeLoginOpen by remember { mutableStateOf(false) }
    var changeNameOpen by remember { mutableStateOf(false) }

    BottomSheetShell(onDismiss = onDismiss, title = user.fullName.ifBlank { user.login }, onBack = onDismiss) {
        Text(
            "@${user.login} · ${user.role}",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        Spacer(Modifier.height(8.dp))

        ActionRow(Icons.AutoMirrored.Outlined.Login, "Сменить логин") { changeLoginOpen = true }
        ActionRow(Icons.Outlined.DriveFileRenameOutline, "Переименовать") { changeNameOpen = true }
        if (currentRole.isSuperAdmin) {
            ActionRow(Icons.Outlined.Password, "Сбросить пароль") { resetPasswordOpen = true }
            // §TZ-0.10.6 — сброс лимита парольных входов на этой неделе.
            ActionRow(Icons.Outlined.RestartAlt, "Сбросить лимит парольных входов") {
                onResetPasswordCounter()
            }
            ActionRow(Icons.Outlined.AdminPanelSettings, "Сменить роль") { roleChangeOpen = true }
            ActionRow(Icons.Outlined.Delete, "Удалить", tint = StatusErrorBorder) { onDelete() }
        }
    }

    if (roleChangeOpen) {
        ChangeRoleSheet(
            user = user,
            onDismiss = { roleChangeOpen = false },
            onPick = { newRole ->
                roleChangeOpen = false
                onChangeRole(newRole)
            },
        )
    }
    if (resetPasswordOpen) {
        SingleInputSheet(
            title = "Сбросить пароль",
            hint = "Новый пароль (пусто = 1234)",
            placeholder = "",
            initial = "",
            confirmLabel = "Сбросить",
            onDismiss = { resetPasswordOpen = false },
            onConfirm = { pwd ->
                resetPasswordOpen = false
                onResetPassword(pwd)
            },
        )
    }
    if (changeLoginOpen) {
        SingleInputSheet(
            title = "Сменить логин",
            hint = "Новый login",
            placeholder = user.login,
            initial = user.login,
            confirmLabel = "Сохранить",
            onDismiss = { changeLoginOpen = false },
            onConfirm = { newLogin ->
                changeLoginOpen = false
                if (newLogin.isNotBlank() && newLogin != user.login) onChangeLogin(newLogin)
            },
        )
    }
    if (changeNameOpen) {
        SingleInputSheet(
            title = "Переименовать",
            hint = "Имя (full name)",
            placeholder = user.fullName,
            initial = user.fullName,
            confirmLabel = "Сохранить",
            onDismiss = { changeNameOpen = false },
            onConfirm = { newName ->
                changeNameOpen = false
                if (newName.isNotBlank() && newName != user.fullName) onChangeFullName(newName)
            },
        )
    }
}

@Composable
private fun ChangeRoleSheet(
    user: UsersRepository.User,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val choices = listOf(
        "admin" to "Администратор",
        "user" to "Пользователь",
        "client" to "Клиент",
    )
    BottomSheetShell(onDismiss = onDismiss, title = "Сменить роль", onBack = onDismiss) {
        Text(
            "@${user.login} · текущая: ${user.role}",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        Spacer(Modifier.height(8.dp))
        choices.forEach { (key, label) ->
            RoleChoiceRow(label = label, selected = user.role.equals(key, ignoreCase = true)) { onPick(key) }
        }
    }
}

@Composable
private fun CreateUserSheet(
    currentRole: Role,
    onDismiss: () -> Unit,
    onConfirm: (login: String, fullName: String, password: String, role: String, mustChange: Boolean) -> Unit,
) {
    var login by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("user") }
    val mustChange = true

    BottomSheetShell(onDismiss = onDismiss, title = "Создать пользователя", onBack = onDismiss) {
        Spacer(Modifier.height(8.dp))
        InputBlock("Login", login, onChange = { login = it })
        Spacer(Modifier.height(8.dp))
        InputBlock("Имя (full name)", fullName, onChange = { fullName = it })
        Spacer(Modifier.height(8.dp))
        InputBlock("Начальный пароль (пусто = 1234)", password, onChange = { password = it })
        Spacer(Modifier.height(12.dp))
        Text("Роль", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        val roles = buildList {
            add("user" to "Пользователь")
            add("admin" to "Администратор")
            if (currentRole.isSuperAdmin) add("client" to "Клиент")
        }
        roles.forEach { (key, label) ->
            RoleChoiceRow(label = label, selected = role == key) { role = key }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Отмена",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDismiss)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(8.dp))
            val enabled = login.isNotBlank() && fullName.isNotBlank()
            Text(
                "Создать",
                color = if (enabled) Accent else TextTertiary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled) {
                        onConfirm(login.trim(), fullName.trim(), password.trim(), role, mustChange)
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SingleInputSheet(
    title: String,
    hint: String,
    placeholder: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    BottomSheetShell(onDismiss = onDismiss, title = title, onBack = onDismiss) {
        Spacer(Modifier.height(12.dp))
        InputBlock(hint, value, placeholder = placeholder, onChange = { value = it })
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Отмена",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDismiss)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                confirmLabel,
                color = Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable { onConfirm(value.trim()) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun InputBlock(
    label: String,
    value: String,
    placeholder: String = "",
    onChange: (String) -> Unit,
) {
    Text(label, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BgInput)
            .border(0.5.dp, BorderDivider, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        if (value.isBlank() && placeholder.isNotBlank()) {
            Text(placeholder, color = TextTertiary, fontSize = 14.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RoleChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AccentSubtle else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) Accent else Color.Transparent)
                .border(1.5.dp, if (selected) Accent else BorderDivider, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(com.example.otlhelper.desktop.theme.BgApp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = if (selected) Accent else TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, tint: Color = TextPrimary, onClick: () -> Unit) {
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
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = tint, style = MaterialTheme.typography.bodyMedium)
    }
}
