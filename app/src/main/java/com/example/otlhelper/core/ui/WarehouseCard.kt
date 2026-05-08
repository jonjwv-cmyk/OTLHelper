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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.*
import com.example.otlhelper.core.ui.animations.AppMotion
import com.example.otlhelper.domain.model.MolRecord

// ═══════════════════════════════════════════════════════════════════════════
//  WAREHOUSE CARD + PILL
//
//  Layout:
//    Склад 0120
//    ─────────────────────────────────────────────
//    Цех термической   │  описание / примечание
//    обработки         │  отметка
//                      │  МОЛ Иванов И. И.
//    ─────────────────────────────────────────────
//    📞  XX XX XX       ← 6-digit, dialable → +7AAAXXXXXXX (with area code)
//    📞  X XX XX         ← 5-digit, display-only
//
//  The body is identical whether shown standalone (WarehouseCard, first item
//  in the search list) or inside the sticky pill (WarehousePillBar) when the
//  full card has scrolled out of view.
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun WarehouseCard(
    record: MolRecord,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(BgCard, CardShape)
            .border(1.dp, BorderDivider, CardShape)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        WarehouseHeader(record)
        WarehouseDivider()
        WarehouseBody(record)
    }
}

@Composable
fun WarehousePillBar(
    record: MolRecord,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    // §TZ-2.3.6 — стиль Linear / Apple SF: subtle accent border (0.5dp
    // Accent @ alpha 0.35), slight elevated background. Раньше был plain
    // dark-on-dark — plашка терялась на фоне списка. Теперь визуально чётко
    // выделяется как «sticky контекст», но без агрессивных жёлтых кнопок.
    //
    // §TZ-2.3.23 — tap haptic на expand/collapse. Тот же отклик что в
    // остальных контейнерах (MenuRow, ToggleRow) — юзер чувствует что
    // pill отреагировал на жест.
    val pillFeedback = com.example.otlhelper.core.feedback.LocalFeedback.current
    val pillView = androidx.compose.ui.platform.LocalView.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(CardShape)
            .background(WarehousePillBg, CardShape)
            .border(0.5.dp, Accent.copy(alpha = 0.35f), CardShape)
            .clickable {
                pillFeedback?.tap(pillView)
                onToggle()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Склад ${record.warehouseId.ifBlank { "—" }}",
                color = WarehousePillText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (expanded) "▲" else "▼",
                color = WarehousePillSub,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = AppMotion.SpringStandardSize) +
                    fadeIn(animationSpec = AppMotion.SpringStandard),
            exit = shrinkVertically(animationSpec = AppMotion.SpringStandardSize) +
                    fadeOut(animationSpec = AppMotion.SpringStandard)
        ) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                WarehouseDivider(subtle = true)
                WarehouseBody(record)
            }
        }
    }
}

@Composable
private fun WarehouseHeader(record: MolRecord) {
    Text(
        text = "Склад ${record.warehouseId.ifBlank { "—" }}",
        color = TextPrimary,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun WarehouseDivider(subtle: Boolean = false) {
    Spacer(Modifier.height(if (subtle) 2.dp else 10.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(BorderDivider)
    )
    Spacer(Modifier.height(if (subtle) 8.dp else 12.dp))
}

@Composable
private fun WarehouseBody(record: MolRecord) {
    val context = LocalContext.current
    val workshop = record.warehouseName.trim().prettifyForWrap().allowWrapInLongWords()

    // Right-column items — no labels, just raw values in fixed visual order:
    //   примечание(-я) → отметка → МОЛ
    val rightValues = buildList {
        record.warehouseDesc.trim()
            .takeIf { it.isNotBlank() && it != record.warehouseName.trim() }
            ?.let { addAll(splitIntoLines(it, maxLines = 3)) }
        record.warehouseMark.trim().takeIf { it.isNotBlank() }?.let { add(it) }
        record.warehouseKeeper.trim().takeIf { it.isNotBlank() }?.let { add(it) }
    }.map { it.prettifyForWrap().allowWrapInLongWords() }

    if (workshop.isNotBlank() || rightValues.isNotEmpty()) {
        // Adaptive layout — if one column is empty, the other takes the whole
        // width instead of leaving a dead Spacer on its side.
        val hasLeft = workshop.isNotBlank()
        val hasRight = rightValues.isNotEmpty()

        when {
            hasLeft && hasRight -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = workshop,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 18.sp,
                    softWrap = true,
                    modifier = Modifier.weight(1.2f)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    rightValues.forEachIndexed { index, value ->
                        if (index > 0) Spacer(Modifier.height(3.dp))
                        Text(
                            value,
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            softWrap = true
                        )
                    }
                }
            }

            hasLeft -> Text(
                text = workshop,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
                softWrap = true,
                modifier = Modifier.fillMaxWidth()
            )

            hasRight -> Column(modifier = Modifier.fillMaxWidth()) {
                rightValues.forEachIndexed { index, value ->
                    if (index > 0) Spacer(Modifier.height(3.dp))
                    Text(
                        value,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        softWrap = true
                    )
                }
            }
        }
    }

    if (record.warehouseWorkPhones.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        splitPhones(record.warehouseWorkPhones).forEach { phone ->
            val dialNumber = getDialNumber(phone)
            val phoneHandler = com.example.otlhelper.core.phone.LocalPhoneCallHandler.current
            val phoneFeedback = com.example.otlhelper.core.feedback.LocalFeedback.current
            val phoneHost = androidx.compose.ui.platform.LocalView.current
            val displayText = formatPhoneDisplay(phone)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .then(
                        if (dialNumber != null) Modifier.clickable {
                            phoneFeedback?.tap(phoneHost)
                            if (phoneHandler != null) {
                                phoneHandler(
                                    com.example.otlhelper.core.phone.PhoneCallRequest(
                                        number = dialNumber,
                                        // §TZ-2.3.7 — в dialog'е показываем
                                        // только «Склад NNNN», без ФИО хранителя.
                                        // Юзер просил чистый контекст: это
                                        // номер склада, а не конкретный человек.
                                        contactName = "Склад ${record.warehouseId}",
                                    )
                                )
                            } else {
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dialNumber"))
                                )
                            }
                        } else Modifier
                    )
            ) {
                Text("📞", fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = displayText,
                    color = if (dialNumber != null) TextPrimary else TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = if (dialNumber != null) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

private fun splitIntoLines(raw: String, maxLines: Int): List<String> =
    raw.split('\n', ';', '|')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(maxLines)

/**
 * Insert zero-width spaces inside very long words (>= 14 chars) so the layout
 * engine can wrap them gracefully instead of overflowing the column or
 * orphaning a tail. Only touches words longer than the threshold; short words
 * stay intact. We chunk every 7 chars which gives a natural visual break.
 *
 * Example:
 *   "Теплоэлектроцентраль" → "Теплоэле\u200Bктроцен\u200Bтраль"
 * Visually: "Теплоэлектроцентраль" — but wrapping is now allowed at the ZWSPs
 * if the line runs out.
 */
private fun String.allowWrapInLongWords(): String {
    if (isBlank()) return this
    return split(' ').joinToString(" ") { word ->
        if (word.length >= 14) word.chunked(7).joinToString("\u200B") else word
    }
}

/**
 * Stitch pairs that read ugly when split across lines.
 *
 * Replaces regular space with non-breaking space (U+00A0) in tight pairs so
 * the text layout engine never breaks between them. Examples:
 *   "Конвертерный цех №1" → line break OK before "цех", NOT between "цех" and "№1"
 *   "корпус 3А"           → keeps "корпус 3А" glued
 *   "1-я очередь"         → stays on one piece
 *
 * Heuristic: glue the last word before a "№N" / single-digit / short token to
 * that token, because those are almost always single concepts.
 */
private fun String.prettifyForWrap(): String {
    if (isBlank()) return this
    val nbsp = '\u00A0'
    var s = this
    // 1) №\s*digits  →  №<nbsp>digits
    s = Regex("""№\s+(\d+[а-яА-Яa-zA-Z]?)""").replace(s) { "№$nbsp${it.groupValues[1]}" }
    // 2) word + "№..." glued together (so "цех №1" becomes one block)
    s = Regex("""([А-Яа-яA-Za-z]+)\s+(№\S+)""").replace(s) { "${it.groupValues[1]}$nbsp${it.groupValues[2]}" }
    // 3) short noun + number: "корпус 3", "этаж 2", "очередь 1"
    s = Regex("""(корпус|здание|этаж|блок|секция|очередь)\s+(\d+[а-яА-Яa-zA-Z\-/]*)""", RegexOption.IGNORE_CASE)
        .replace(s) { "${it.groupValues[1]}$nbsp${it.groupValues[2]}" }
    return s
}
