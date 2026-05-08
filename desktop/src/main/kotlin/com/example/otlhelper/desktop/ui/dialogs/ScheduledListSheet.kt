package com.example.otlhelper.desktop.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.data.scheduled.ScheduledRepository
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.Space
import com.example.otlhelper.desktop.theme.StatusErrorBorder
import com.example.otlhelper.desktop.theme.StatusOkBorder
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import com.example.otlhelper.desktop.ui.components.Pill
import com.example.otlhelper.desktop.ui.components.PillSize
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * §TZ-DESKTOP-0.1.0 этап 6 — список запланированных сообщений (admin+).
 * Показывает pending сверху + sent/cancelled последние 30 дней.
 * Отменять можно только pending и только автор.
 */
@Composable
fun ScheduledListSheet(
    state: ScheduledRepository.State,
    onCancel: (id: Long) -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit = onDismiss,
) {
    BottomSheetShell(onDismiss = onDismiss, title = "Запланированные", onBack = onBack) {
        Spacer(Modifier.height(2.dp))
        // §TZ-DESKTOP 0.2.2 — укоротили ("Отложенные новости и история за 30
        // дней" → "История за 30 дней") + maxLines=1 чтобы не переносилось на
        // узкой панели 280dp.
        Text(
            "История за 30 дней",
            color = TextTertiary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))

        when {
            state.isLoading && state.items.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            state.lastError.isNotBlank() && state.items.isEmpty() -> Text(
                "Ошибка: ${state.lastError}",
                color = TextTertiary,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 20.dp),
            )
            state.items.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text("Ничего не запланировано", color = TextSecondary, fontSize = 13.sp)
            }
            else -> state.items.forEach { it ->
                ScheduledRow(item = it, onCancel = { onCancel(it.id) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private data class StatusBadge(val label: String, val color: Color, val icon: ImageVector)

/**
 * §TZ-DESKTOP 0.2.1 — рефактор строки. Было:
 *   • строка статуса "Отправлено · дата" в Text без maxLines → дата
 *     переносилась на 2 строки на узкой панели 280dp
 *   • для cancelled две строки через `\n` с префиксом разной длины
 *     ("Должно было выйти" vs "Удалено") → «·» bullets не выравнивались
 *   • статус дублировался в badge И в text-префиксе → избыточность
 *
 * Стало:
 *   • статус через общий Pill-компонент (Md размер)
 *   • основная дата отдельной строкой (без префикса — он уже в pill)
 *   • для cancelled добавляется вторичная строка "Планировалось на <дата>"
 *     TextTertiary 11sp — не перекрывается с primary датой
 *   • body показывает только тип+preview без смешивания со статусами
 */
@Composable
private fun ScheduledRow(
    item: ScheduledRepository.Item,
    onCancel: () -> Unit,
) {
    val badge = when (item.status) {
        "pending" -> StatusBadge("Запланировано", Accent, Icons.Outlined.Schedule)
        "sent" -> StatusBadge("Отправлено", StatusOkBorder, Icons.Outlined.CheckCircle)
        "cancelled" -> StatusBadge("Удалено", TextTertiary, Icons.Outlined.Close)
        else -> StatusBadge(item.status, TextSecondary, Icons.Outlined.Schedule)
    }

    // Основная дата — когда что-то произошло или запланировано.
    val primaryDate = when (item.status) {
        "sent" -> formatYekaterinburg(item.sentAt.ifBlank { item.sendAtRaw })
        "cancelled" -> formatYekaterinburg(item.cancelledAt.ifBlank { item.sendAtRaw })
        else -> formatYekaterinburg(item.sendAtRaw)
    }

    // Вторичная строка — показываем только для cancelled, чтобы сохранить
    // информацию «когда должно было выйти» (основная у отменённых — дата отмены).
    val secondaryDate = if (item.status == "cancelled" && item.sendAtRaw.isNotBlank()) {
        "Планировалось на ${formatYekaterinburg(item.sendAtRaw)}"
    } else null

    val kindPrefix = if (item.kind == "poll") "Опрос" else "Новость"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCard)
            .border(0.5.dp, BorderDivider, RoundedCornerShape(12.dp))
            .padding(Space.md),
    ) {
        Pill(
            text = badge.label,
            containerColor = badge.color.copy(alpha = 0.14f),
            contentColor = badge.color,
            size = PillSize.Md,
            icon = badge.icon,
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            primaryDate,
            color = TextPrimary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
        )
        if (secondaryDate != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                secondaryDate,
                color = TextTertiary,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Space.sm))
        Text(
            buildString {
                append(kindPrefix)
                append(": ")
                append(item.previewText.ifBlank { "(без текста)" })
            },
            color = if (item.status == "cancelled") TextTertiary else TextPrimary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (item.isPending) {
            Spacer(Modifier.height(Space.xs))
            TextButton(
                onClick = onCancel,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 0.dp,
                    vertical = 4.dp,
                ),
            ) {
                Text(
                    "Отменить",
                    color = StatusErrorBorder,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun formatYekaterinburg(raw: String): String {
    if (raw.isBlank()) return ""
    return try {
        val iso = if (raw.contains('T')) {
            if (raw.endsWith("Z") || raw.contains('+') || raw.lastIndexOf('-') > 10) raw
            else "${raw}Z"
        } else {
            "${raw.replace(' ', 'T')}Z"
        }
        val zdt = ZonedDateTime.parse(iso)
        val yek = zdt.withZoneSameInstant(ZoneId.of("Asia/Yekaterinburg"))
        yek.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale("ru")))
    } catch (_: Exception) {
        raw.take(16)
    }
}
