package com.example.otlhelper.desktop.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.example.otlhelper.desktop.theme.BgElevated
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.AccentSubtle
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val yekZone = ZoneId.of("Asia/Yekaterinburg")
private val russianLocale = Locale.forLanguageTag("ru")
private val datePretty = DateTimeFormatter.ofPattern("d MMM, EEE", russianLocale)

/**
 * §TZ-DESKTOP-0.1.0 — прямая копия app/.../dialogs/ScheduledPickDialog.kt.
 * iOS-style wheel picker: дата · час12 · минута (шаг 5) · AM/PM.
 * Возвращает UTC ISO `yyyy-MM-dd HH:mm:ss` (тот же контракт что сервер).
 */
@Composable
fun ScheduledPickDialog(
    onDismiss: () -> Unit,
    onPick: (sendAtUtc: String) -> Unit,
) {
    val now = ZonedDateTime.now(yekZone)
    val seedMinute = (((now.minute / 5) + 1) * 5).let { if (it >= 60) 0 else it }
    val seedHour24 = if (seedMinute == 0 && now.minute > 0) (now.hour + 1) % 24 else now.hour
    val seedHour12 = when {
        seedHour24 == 0 -> 12
        seedHour24 > 12 -> seedHour24 - 12
        else -> seedHour24
    }
    val seedIsPm = seedHour24 >= 12

    val today = now.toLocalDate()
    val dates: List<LocalDate> = remember { List(60) { today.plusDays(it.toLong()) } }
    val hours12: List<Int> = remember { (1..12).toList() }
    val minutes: List<Int> = remember { (0..55 step 5).toList() }
    val periods: List<String> = remember { listOf("AM", "PM") }

    var dateIndex by remember { mutableIntStateOf(0) }
    var hourIndex by remember { mutableIntStateOf(hours12.indexOf(seedHour12).coerceAtLeast(0)) }
    var minuteIndex by remember { mutableIntStateOf(minutes.indexOf(seedMinute).coerceAtLeast(0)) }
    var periodIndex by remember { mutableIntStateOf(if (seedIsPm) 1 else 0) }

    // §TZ-DESKTOP-0.10.2 — DialogWindow вместо AlertDialog. AlertDialog в
    // Compose Desktop рендерится lightweight Popup'ом и СКРЫВАЕТСЯ за
    // heavyweight Sheets webview (KCEF/WebView2 — Swing-canvas). DialogWindow
    // создаёт настоящее OS-окно поверх всего. Юзер сообщил баг: «кнопка часы
    // ничего не делает» — на самом деле диалог открывался, но за webview.
    val dialogState = rememberDialogState(size = DpSize(560.dp, 420.dp))
    DialogWindow(
        onCloseRequest = onDismiss,
        state = dialogState,
        title = "Отложить отправку",
        resizable = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgElevated)
                .padding(20.dp),
        ) {
            Text(
                "Отложить отправку",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WheelColumn(
                    items = dates,
                    selectedIndex = dateIndex,
                    onSelectedIndexChange = { dateIndex = it },
                    label = { d ->
                        when {
                            d == today -> "сегодня"
                            d == today.plusDays(1) -> "завтра"
                            else -> d.format(datePretty)
                        }
                    },
                    modifier = Modifier.weight(1.6f),
                )
                WheelColumn(
                    items = hours12,
                    selectedIndex = hourIndex,
                    onSelectedIndexChange = { hourIndex = it },
                    label = { "%d".format(it) },
                    modifier = Modifier.weight(0.6f),
                )
                Text(":", color = TextSecondary, style = MaterialTheme.typography.titleLarge)
                WheelColumn(
                    items = minutes,
                    selectedIndex = minuteIndex,
                    onSelectedIndexChange = { minuteIndex = it },
                    label = { "%02d".format(it) },
                    modifier = Modifier.weight(0.6f),
                )
                WheelColumn(
                    items = periods,
                    selectedIndex = periodIndex,
                    onSelectedIndexChange = { periodIndex = it },
                    label = { it },
                    modifier = Modifier.weight(0.6f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                previewLabel(
                    dates[dateIndex],
                    hours12[hourIndex],
                    minutes[minuteIndex],
                    periods[periodIndex],
                ),
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Отмена", color = TextSecondary) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val d = dates[dateIndex]
                    val hour12 = hours12[hourIndex]
                    val isPm = periodIndex == 1
                    val hour24 = when {
                        hour12 == 12 && !isPm -> 0
                        hour12 == 12 && isPm -> 12
                        isPm -> hour12 + 12
                        else -> hour12
                    }
                    val local = ZonedDateTime.of(
                        d.year, d.monthValue, d.dayOfMonth,
                        hour24, minutes[minuteIndex], 0, 0,
                        yekZone,
                    )
                    val utc = local.withZoneSameInstant(ZoneOffset.UTC)
                    val iso = utc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    onPick(iso)
                }) { Text("Запланировать", color = Accent) }
            }
        }
    }
}

private fun previewLabel(date: LocalDate, hour12: Int, minute: Int, period: String): String {
    val today = LocalDate.now(yekZone)
    val datePart = when {
        date == today -> "сегодня"
        date == today.plusDays(1) -> "завтра"
        else -> date.format(datePretty)
    }
    return "$datePart · %d:%02d %s".format(hour12, minute, period)
}

// ── Wheel primitive ──────────────────────────────────────────────────────

private val WHEEL_ITEM_HEIGHT: Dp = 40.dp
private const val WHEEL_VISIBLE_ITEMS: Int = 5
private val WHEEL_HEIGHT: Dp = WHEEL_ITEM_HEIGHT * WHEEL_VISIBLE_ITEMS

@Composable
private fun <T> WheelColumn(
    items: List<T>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val scope = rememberCoroutineScope()
    val halfCount = WHEEL_VISIBLE_ITEMS / 2

    val centeredIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to centeredIndex }
            .distinctUntilChanged()
            .collect { (scrolling, idx) ->
                if (!scrolling && idx != selectedIndex && idx in items.indices) {
                    onSelectedIndexChange(idx)
                }
            }
    }
    LaunchedEffect(selectedIndex) {
        if (!listState.isScrollInProgress && selectedIndex != listState.firstVisibleItemIndex) {
            scope.launch { listState.animateScrollToItem(selectedIndex) }
        }
    }

    Box(
        modifier = modifier.height(WHEEL_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WHEEL_ITEM_HEIGHT)
                .clip(RoundedCornerShape(10.dp))
                .background(AccentSubtle),
        )

        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(halfCount) {
                Box(modifier = Modifier.height(WHEEL_ITEM_HEIGHT).fillMaxWidth())
            }
            itemsIndexed(items) { index, item ->
                val distance = abs(index - centeredIndex).toFloat()
                val alpha = (1f - 0.25f * distance).coerceIn(0.25f, 1f)
                val isCenter = index == centeredIndex
                Box(
                    modifier = Modifier
                        .height(WHEEL_ITEM_HEIGHT)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(item),
                        color = if (isCenter) TextPrimary else TextPrimary.copy(alpha = alpha),
                        style = if (isCenter) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCenter) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            items(halfCount) {
                Box(modifier = Modifier.height(WHEEL_ITEM_HEIGHT).fillMaxWidth())
            }
        }
    }
}
