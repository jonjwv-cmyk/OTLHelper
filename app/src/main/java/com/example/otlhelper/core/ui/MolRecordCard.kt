package com.example.otlhelper.core.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.CardStatusErrorBg
import com.example.otlhelper.core.theme.CardStatusOkBg
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.ui.components.ThinDivider
import com.example.otlhelper.core.theme.StatusErrorBorder
import com.example.otlhelper.core.theme.StatusOkBorder
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.ui.animations.AppMotion
import com.example.otlhelper.domain.model.MolRecord
import com.example.otlhelper.domain.model.SearchMode

/**
 * Contact (MOL) card in search results.
 *
 * [searchMode] determines which fields show up in the expanded view:
 * - WAREHOUSE: only FIO/position/phone/email — the warehouse context is
 *   already shown in the main warehouse card above, repeating it here is noise.
 * - NAME/PHONE/EMAIL: full detail including warehouse assignment(s).
 *
 * [isAdminOrDev]: only admin/developer see табельный номер (tab number).
 */
@Composable
fun MolRecordCard(
    record: MolRecord,
    modifier: Modifier = Modifier,
    searchMode: SearchMode = SearchMode.NAME,
    isAdminOrDev: Boolean = false,
    /** Warehouse IDs where this person is MOL — shown as "Склады:" line, visible to all. */
    warehouseIds: List<String> = emptyList()
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    // §TZ-2.3.23 — tap haptic на раскрытие/сворачивание карточки.
    val cardFeedback = com.example.otlhelper.core.feedback.LocalFeedback.current
    val cardView = androidx.compose.ui.platform.LocalView.current

    val isWorking = record.status.trim().equals("работает", ignoreCase = true)
    val borderColor = if (isWorking) StatusOkBorder else StatusErrorBorder
    // §0.10.13 — субтильная заливка карточки в цвет статуса. Раньше отличие
    // было только по border'у — на маленьких screen'ах слабо считывалось.
    // Теперь BgCard заменяется на CardStatusOkBg (зеленоватый) или
    // CardStatusErrorBg (красноватый) — однозначно, но не агрессивно.
    val cardBgColor = if (isWorking) CardStatusOkBg else CardStatusErrorBg

    // Warehouses list for this person — only shown when NOT warehouse-search
    // (warehouse search already shows the warehouse context up top).
    val showWarehousesLine = searchMode != SearchMode.WAREHOUSE && warehouseIds.isNotEmpty()
    val showTabNumber = isAdminOrDev && record.tab.isNotBlank()

    val hasExtra = record.work.isNotBlank() || showWarehousesLine || showTabNumber

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(cardBgColor, CardShape)
            .border(1.5.dp, borderColor, CardShape)
            .then(
                if (hasExtra) Modifier.clickable {
                    cardFeedback?.tap(cardView)
                    expanded = !expanded
                } else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        // FIO + status
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                text = record.fio.ifBlank { "—" },
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 20.sp,
                modifier = Modifier.weight(1f)
            )
            if (record.status.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = record.status,
                    color = if (isWorking) StatusOkBorder else StatusErrorBorder,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Position — full text, may wrap to multiple lines (positions like
        // "Главный специалист по охране труда и промышленной безопасности"
        // were getting truncated mid-thought with maxLines=2).
        if (record.position.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = record.position,
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                softWrap = true
            )
        }

        // Mobile phone
        if (record.mobile.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            PhoneChip(raw = record.mobile, context = context, contactName = record.fio)
        }

        // Email — long corporate addresses don't fit single line on narrow
        // screens; allow wrapping but cap at 2 lines to keep the card compact.
        if (record.mail.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = record.mail,
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                softWrap = true,
                modifier = Modifier.clickable {
                    // §TZ-2.3.23 — tap haptic на email tap (как phone tap).
                    cardFeedback?.tap(cardView)
                    context.startActivity(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${record.mail}"))
                    )
                }
            )
        }

        // Expanded details — role/search-mode-aware
        AnimatedVisibility(
            visible = expanded && hasExtra,
            enter = expandVertically(animationSpec = AppMotion.SpringStandardSize) + fadeIn(animationSpec = AppMotion.SpringStandard),
            exit = shrinkVertically(animationSpec = AppMotion.SpringStandardSize) + fadeOut(animationSpec = AppMotion.SpringStandard)
        ) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                ThinDivider()
                Spacer(Modifier.height(8.dp))

                if (record.work.isNotBlank()) {
                    // §TZ-2.3.7 — для рабочего номера в dialog'е показываем
                    // контекст склада («Склад 0609»), не ФИО. Админ видит ЧТО
                    // звонит, а не только КОМУ.
                    val workLabel = if (record.warehouseId.isNotBlank())
                        "Склад ${record.warehouseId}"
                    else "Рабочий"
                    PhoneChip(raw = record.work, label = "Рабочий", context = context, contactName = workLabel)
                    Spacer(Modifier.height(4.dp))
                }
                // Warehouses where person is MOL — visible to EVERYONE
                if (showWarehousesLine) {
                    DetailRow("Склады", warehouseIds.joinToString(", "))
                }
                // Tab number — admin/developer only
                if (showTabNumber) {
                    DetailRow("Таб. №", record.tab)
                }
            }
        }
    }
}

// ── Phone chip — icon + formatted number, clickable if dialable ───────────────
@Composable
private fun PhoneChip(
    raw: String,
    label: String? = null,
    context: android.content.Context,
    contactName: String? = null,
) {
    val dialNumber = getDialNumber(raw)
    val displayText = formatPhoneDisplay(raw)
    val phoneHandler = com.example.otlhelper.core.phone.LocalPhoneCallHandler.current
    // §TZ-2.3.8 — tap-хаптика ПЕРЕД открытием dialog'а «Позвонить?». Отклик
    // юзеру что касание номера принято.
    val phoneFeedback = com.example.otlhelper.core.feedback.LocalFeedback.current
    val phoneHost = androidx.compose.ui.platform.LocalView.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (dialNumber != null) {
            Modifier.clickable {
                phoneFeedback?.tap(phoneHost)
                if (phoneHandler != null) {
                    phoneHandler(
                        com.example.otlhelper.core.phone.PhoneCallRequest(
                            number = dialNumber,
                            contactName = contactName,
                        )
                    )
                } else {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dialNumber")))
                }
            }
        } else Modifier
    ) {
        Text("📞", fontSize = 14.sp)
        Spacer(Modifier.width(5.dp))
        if (label != null) {
            Text(
                "$label: ",
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = displayText,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (dialNumber != null) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label: ",
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        // weight(1f) so the value fills the rest of the row and wraps cleanly
        // when it's long (e.g. comma-joined warehouse list).
        Text(
            value,
            color = TextPrimary,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            softWrap = true,
            modifier = Modifier.weight(1f)
        )
    }
}
