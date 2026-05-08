package com.example.otlhelper.presentation.home.dialogs.user_management

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.BgInput
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.StatusErrorBorder
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.domain.model.Role

/**
 * Narrow-scope helpers shared between the user-management shell, row, and
 * create-form. Live here rather than in a global primitives file because
 * their behaviour is specific to this dialog (password transformation,
 * capitalisation mode, role-badge three-state palette, etc.).
 */

/** Outlined text field with the dialog's password/capitalisation conventions. */
@Composable
internal fun OtlField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextSecondary) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(capitalization = capitalization),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = Accent,
            unfocusedBorderColor = BorderDivider,
            focusedContainerColor = BgInput,
            unfocusedContainerColor = BgInput
        )
    )
}

@Composable
internal fun MenuItemRow(
    icon: ImageVector,
    label: String,
    tint: Color = TextPrimary,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        leadingIcon = { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) },
        text = { Text(label, color = tint, style = MaterialTheme.typography.bodyMedium) },
        onClick = onClick
    )
}

@Composable
internal fun RoleBadge(role: String) {
    val (label, color) = when (Role.fromString(role)) {
        Role.Developer -> "DEV" to StatusErrorBorder
        Role.Admin -> "ADM" to Accent
        // §TZ-2.3.38 — отдельный badge для клиентов (CLI), в админке сразу видно.
        Role.Client -> "CLI" to TextSecondary
        Role.User -> return
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .border(0.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Last-seen timestamp in human form: "только что" / "5 мин назад" / "15.04.2026". */
internal fun formatLastSeen(raw: String): String {
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
