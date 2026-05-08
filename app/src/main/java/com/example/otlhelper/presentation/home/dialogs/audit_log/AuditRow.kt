package com.example.otlhelper.presentation.home.dialogs.audit_log

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AccentSubtle
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Single audit-log entry. Actor chip is tappable — tap sends the actor login
 * up so the parent shell can swap the picker selection to this user.
 */
@Composable
internal fun AuditRow(entry: JSONObject, onActorTap: (String) -> Unit) {
    val actor = entry.optString("actor_login", "")
    val actorRole = entry.optString("actor_role", "")
    val action = entry.optString("action", "")
    val targetType = entry.optString("target_type", "")
    val targetId = entry.optString("target_id", "")
    val createdAt = entry.optString("created_at", "")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgCard)
            .border(0.5.dp, BorderDivider, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                actionLabel(action),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                formatAuditTime(createdAt),
                color = TextTertiary,
                fontSize = 11.sp
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(AccentSubtle)
                    .clickable(actor.isNotBlank()) { onActorTap(actor) }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    actor.ifBlank { "—" },
                    color = Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            if (actorRole.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(actorRole, color = TextTertiary, fontSize = 11.sp)
            }
            if (targetType.isNotBlank() || targetId.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    targetLabel(targetType, targetId),
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ── Label helpers ────────────────────────────────────────────────────────────

internal fun actionLabel(raw: String): String = when (raw) {
    "pin_message" -> "Закрепил элемент"
    "unpin_message" -> "Открепил элемент"
    "delete_message" -> "Удалил сообщение"
    "edit_message" -> "Изменил сообщение"
    "edit_news" -> "Изменил новость"
    "edit_poll" -> "Изменил опрос"
    "soft_delete_message" -> "Удалил (мягко) сообщение"
    "undelete_message" -> "Восстановил сообщение"
    "create_news" -> "Создал новость"
    "create_poll" -> "Создал опрос"
    "create_news_poll" -> "Создал опрос"
    "create_user" -> "Создал пользователя"
    "update_user" -> "Изменил пользователя"
    "rename_user" -> "Переименовал пользователя"
    "change_login" -> "Сменил логин"
    "delete_user" -> "Удалил пользователя"
    "suspend_user" -> "Заблокировал пользователя"
    "unsuspend_user" -> "Разблокировал пользователя"
    "set_app_status" -> "Изменил статус приложения"
    "set_app_version" -> "Обновил версию приложения"
    "broadcast_app_version" -> "Разослал push о версии"
    "system_control" -> "Системное действие"
    else -> raw.ifBlank { "—" }
}

internal fun targetLabel(type: String, id: String): String {
    val typeLabel = when (type) {
        "message" -> "сообщение"
        "user" -> "пользователь"
        "news" -> "новость"
        "poll" -> "опрос"
        "app_version" -> "версия"
        "" -> ""
        else -> type
    }
    return when {
        typeLabel.isBlank() && id.isBlank() -> ""
        typeLabel.isBlank() -> "#$id"
        id.isBlank() -> "· $typeLabel"
        else -> "· $typeLabel #$id"
    }
}

// Month name in ru ("янв", "фев" …) + 12-hour time with English AM/PM.
// Composing two formatters keeps the mixed-locale intent explicit.
private val auditDateFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))
private val auditTimeFormatter = DateTimeFormatter.ofPattern(", h:mm a", Locale.ENGLISH)
private val yekZone = ZoneId.of("Asia/Yekaterinburg")

internal fun formatAuditTime(raw: String): String {
    if (raw.isBlank()) return ""
    return try {
        val iso = if (raw.contains('T')) {
            if (raw.endsWith("Z") || raw.contains('+') || raw.lastIndexOf('-') > 10) raw
            else "${raw}Z"
        } else {
            "${raw.replace(' ', 'T')}Z"
        }
        val zdt = ZonedDateTime.parse(iso).withZoneSameInstant(yekZone)
        zdt.format(auditDateFormatter) + zdt.format(auditTimeFormatter)
    } catch (_: Exception) {
        raw.take(16)
    }
}
