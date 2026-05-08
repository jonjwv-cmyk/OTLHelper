package com.example.otlhelper.presentation.home.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AccentSubtle
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
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
 * iOS-style scroll-wheel date + time picker. Returns UTC ISO
 * `YYYY-MM-DD HH:MM:SS` via [onPick] — matches the server's send_at contract.
 *
 * Minute wheel steps by 5 (0, 5, 10 … 55): keeps the wheel short and matches
 * the cron's 1-min dispatcher granularity honestly — anything finer would be
 * UX theatre.
 */
@Composable
fun ScheduledPickDialog(
    onDismiss: () -> Unit,
    onPick: (sendAtUtc: String) -> Unit,
) {
    // Seed at "now + 5 min" rounded up to the next 5-min slot so accepting
    // the default doesn't produce a past time.
    val now = ZonedDateTime.now(yekZone)
    val seedMinute = (((now.minute / 5) + 1) * 5).let { if (it >= 60) 0 else it }
    val seedHour24 = if (seedMinute == 0 && now.minute > 0) (now.hour + 1) % 24 else now.hour
    // 12-hour display: 0 → 12 AM, 12 → 12 PM, else mod 12.
    val seedHour12 = when {
        seedHour24 == 0 -> 12
        seedHour24 > 12 -> seedHour24 - 12
        else -> seedHour24
    }
    val seedIsPm = seedHour24 >= 12

    val today = now.toLocalDate()
    val dates: List<LocalDate> = remember {
        // 60 days is plenty for "отложить" — further ranges belong to a
        // different workflow, not a quick scheduler.
        List(60) { today.plusDays(it.toLong()) }
    }
    val hours12: List<Int> = remember { (1..12).toList() }
    val minutes: List<Int> = remember { (0..55 step 5).toList() }
    val periods: List<String> = remember { listOf("AM", "PM") }

    var dateIndex by remember { mutableIntStateOf(0) }
    var hourIndex by remember { mutableIntStateOf(hours12.indexOf(seedHour12).coerceAtLeast(0)) }
    var minuteIndex by remember { mutableIntStateOf(minutes.indexOf(seedMinute).coerceAtLeast(0)) }
    var periodIndex by remember { mutableIntStateOf(if (seedIsPm) 1 else 0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                "Отложить отправку",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
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
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = dates[dateIndex]
                val hour12 = hours12[hourIndex]
                val isPm = periodIndex == 1
                // Convert 12-hour display back to 24-hour for the server.
                // 12 AM = 00, 12 PM = 12, 1-11 AM = 1-11, 1-11 PM = 13-23.
                val hour24 = when {
                    hour12 == 12 && !isPm -> 0
                    hour12 == 12 && isPm -> 12
                    isPm -> hour12 + 12
                    else -> hour12
                }
                val local = ZonedDateTime.of(
                    d.year, d.monthValue, d.dayOfMonth,
                    hour24, minutes[minuteIndex], 0, 0,
                    yekZone
                )
                val utc = local.withZoneSameInstant(ZoneOffset.UTC)
                val iso = utc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                onPick(iso)
            }) { Text("Запланировать", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = TextSecondary) }
        },
    )
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

// ── Wheel primitive ──────────────────────────────────────────────────────────

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

    // Centre-most visible item once the list is idle. `firstVisibleItemIndex`
    // points at the top of the padded list, which equals the centre slot
    // because we pad with `halfCount` empty rows on top.
    val centeredIndex by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }
    // §TZ-2.3.7 — тик на каждое изменение центрального элемента (каждый щёлк
    // колёсика). SF-iOS-стиль: крутишь — чувствуешь каждый тик.
    val feedback = com.example.otlhelper.core.feedback.LocalFeedback.current
    val hostView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { _ ->
                if (listState.isScrollInProgress) feedback?.tick(hostView)
            }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to centeredIndex }
            .distinctUntilChanged()
            .collect { (scrolling, idx) ->
                if (!scrolling && idx != selectedIndex && idx in items.indices) {
                    onSelectedIndexChange(idx)
                    feedback?.tap(hostView)  // snap landed — более ощутимый tap
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
        contentAlignment = Alignment.Center
    ) {
        // Centre selection strip — subtle accent-tinted capsule behind
        // the middle slot, iOS-style.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WHEEL_ITEM_HEIGHT)
                .clip(RoundedCornerShape(10.dp))
                .background(AccentSubtle)
        )

        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Phantom rows above/below the real items so every real item
            // can be scrolled into the exact centre slot.
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
                    contentAlignment = Alignment.Center
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

