package com.example.otlhelper.presentation.home.dialogs.user_management

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AccentSubtle
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.domain.model.Role
import com.example.otlhelper.domain.model.displayName
import com.example.otlhelper.domain.model.wireName

@Composable
internal fun CreateUserForm(
    onSubmit: (login: String, name: String, pass: String, role: String, mustChange: Boolean) -> Unit,
    onCancel: () -> Unit,
    // §TZ-2.3.38 — роль вызывающего (developer видит расширенный набор,
    // включая Client и Developer; обычный admin — только User + Admin).
    myRole: Role = Role.Admin,
) {
    var login by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.User) }
    var mustChange by remember { mutableStateOf(true) }
    // Developer создаёт user/client/admin/developer. Admin — только user/admin.
    val roles = if (myRole == Role.Developer) {
        listOf(Role.User, Role.Client, Role.Admin, Role.Developer)
    } else {
        listOf(Role.User, Role.Admin)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Новый пользователь", color = TextSecondary, style = MaterialTheme.typography.titleSmall)
        OtlField(value = login, label = "Логин", onValueChange = { login = it })
        OtlField(value = name, label = "ФИО", onValueChange = { name = it }, capitalization = KeyboardCapitalization.Words)
        OtlField(value = pass, label = "Пароль", onValueChange = { pass = it }, isPassword = true)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            roles.forEach { r ->
                FilterChip(
                    selected = role == r,
                    onClick = { role = r },
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = mustChange,
                onCheckedChange = { mustChange = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TextPrimary,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = BgCard,
                    uncheckedBorderColor = BorderDivider,
                )
            )
            Spacer(Modifier.width(8.dp))
            Text("Сменить пароль при входе", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Отмена") }
            Button(
                onClick = {
                    if (login.isNotBlank() && name.isNotBlank() && pass.isNotBlank())
                        onSubmit(login.trim(), name.trim(), pass, role.wireName(), mustChange)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = TextPrimary),
                shape = RoundedCornerShape(12.dp),
                enabled = login.isNotBlank() && name.isNotBlank() && pass.isNotBlank()
            ) { Text("Создать") }
        }
    }
}
