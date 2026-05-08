package com.example.otlhelper.presentation.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.otlhelper.core.phone.PhoneFormatter
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.StatusErrorBorder
import com.example.otlhelper.core.theme.StatusOkBorder
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary

/**
 * §TZ-2.3.6 — подтверждение звонка в стиле приложения. Вопрос «Позвонить?»
 * + отформатированный номер + две пилюли: «Нет» (красная с 🚫) / «Да»
 * (зелёная с 📞). После «Да» — CallStateManager запускает ACTION_CALL и
 * показывает InCallBar над BottomTabBar.
 *
 * Номер телефона форматируется в стиле `8 901 438 88 31` — читаемо,
 * удобно для диктовки.
 */
@Composable
fun CallConfirmDialog(
    rawPhone: String,
    contactName: String? = null,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                // §TZ-2.3.6 — более сочный фон (BgCard вместо BgElevated),
                // тёплый Accent border по контуру + внутренний warm glow.
                // Раньше dialog выглядел «пепельным» на общем dark surface —
                // теперь выделяется в стиле SF-2026.
                .background(BgCard)
                .border(0.8.dp, Accent.copy(alpha = 0.32f), RoundedCornerShape(20.dp))
                .padding(horizontal = 22.dp, vertical = 22.dp)
        ) {
            Text(
                "Позвонить?",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            // §TZ-2.3.6 — ФИО контакта над номером. Full-width, до 2 строк
            // (длинные русские ФИО «Иванов-Петров Иван Иванович» не
            // обрезаются), всегда читаемо. Если имя не передано — пропуск.
            if (!contactName.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    contactName,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    PhoneFormatter.pretty(rawPhone),
                    color = Accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    PhoneFormatter.pretty(rawPhone),
                    color = Accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    label = "Нет",
                    icon = Icons.Default.Close,
                    color = StatusErrorBorder,
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    label = "Да",
                    icon = Icons.Default.Phone,
                    color = StatusOkBorder,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.14f),
            contentColor = color,
        ),
        shape = RoundedCornerShape(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}
