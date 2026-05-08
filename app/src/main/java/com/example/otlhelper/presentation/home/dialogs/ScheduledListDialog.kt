package com.example.otlhelper.presentation.home.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.ApiClient
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BgElevated
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.StatusErrorBorder
import com.example.otlhelper.core.theme.StatusOkBorder
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.ui.components.DialogDragHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Phase 12c + §TZ-2.3.5: список запланированных новостей с историей.
 *
 * Раньше показывал только `status='pending'` — после отправки запись
 * пропадала и диалог становился пустым. Теперь три статуса с бейджами:
 *   • «Запланировано» (amber) — send_at в будущем, «Отменить» активна
 *   • «Отправлено» (зелёный) — sent_at виден, кнопки отмены нет
 *   • «Отменено» (серый) — показан факт, отмены нет
 *
 * Сервер возвращает pending + sent/cancelled за последние 30 дней.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledListDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    fun reload() {
        loading = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { ApiClient.listScheduled() }
                if (r.optBoolean("ok", false)) {
                    val arr = r.optJSONArray("data")
                    items = buildList {
                        for (i in 0 until (arr?.length() ?: 0)) arr?.optJSONObject(i)?.let { add(it) }
                    }
                }
            } catch (_: Exception) {
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { reload() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgElevated,
        dragHandle = { DialogDragHandle() },
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Запланированные", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                "Отложенные новости и история за 30 дней",
                color = TextTertiary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))

            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxWidth().height(120.dp)) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Accent,
                        strokeWidth = 2.dp,
                    )
                }
                items.isEmpty() -> Column(
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
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it.optLong("id") }) { item ->
                        ScheduledRow(
                            item = item,
                            onCancel = { id ->
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { ApiClient.cancelScheduled(id) }
                                        reload()
                                    } catch (_: Exception) {}
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduledRow(item: JSONObject, onCancel: (Long) -> Unit) {
    val status = item.optString("status", "pending")
    val payload = item.optJSONObject("payload")
    val text = payload?.optString("text", "")?.trim().orEmpty()
    val sendAt = item.optString("send_at", "")
    val sentAt = item.optString("sent_at", "").takeIf { it.isNotBlank() }
    val cancelledAt = item.optString("cancelled_at", "").takeIf { it.isNotBlank() }
    val cancelledBy = item.optString("cancelled_by", "").takeIf { it.isNotBlank() }
    val id = item.optLong("id", 0L)

    val badge = when (status) {
        "pending" -> StatusBadge("Запланировано", Accent, Icons.Outlined.Schedule)
        "sent" -> StatusBadge("Отправлено", StatusOkBorder, Icons.Outlined.CheckCircle)
        "cancelled" -> StatusBadge("Удалено", TextTertiary, Icons.Outlined.Close)
        else -> StatusBadge(status, TextSecondary, Icons.Outlined.Schedule)
    }

    val whenLabel = when (status) {
        "sent" -> sentAt?.let { "Отправлено · ${formatYekaterinburg(it)}" }
            ?: "Отправлено · ${formatYekaterinburg(sendAt)}"
        "cancelled" -> buildString {
            append("Должно было выйти ${formatYekaterinburg(sendAt)}")
            if (cancelledAt != null) {
                append("\nУдалено · ${formatYekaterinburg(cancelledAt)}")
                if (cancelledBy != null) append(" · ${cancelledBy}")
            }
        }
        else -> formatYekaterinburg(sendAt)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCard)
            .border(0.5.dp, BorderDivider, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badge.color.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = badge.icon,
                        contentDescription = null,
                        tint = badge.color,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        badge.label,
                        color = badge.color,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            whenLabel,
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        if (text.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                color = TextPrimary,
                fontSize = 13.sp,
                maxLines = 3,
            )
        }
        if (status == "pending" && id > 0L) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { onCancel(id) },
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

private data class StatusBadge(val label: String, val color: Color, val icon: ImageVector)

/**
 * Форматируем "YYYY-MM-DD HH:MM:SS" (UTC) → "19 апр 2026, 16:30" в Екатеринбурге.
 * Совпадает со стилем дат в остальном приложении (BaseDatabaseRow, AppStatsDialog).
 */
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
