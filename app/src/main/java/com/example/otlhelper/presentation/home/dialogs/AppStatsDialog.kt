package com.example.otlhelper.presentation.home.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.ApiClient
import com.example.otlhelper.core.theme.*
import com.example.otlhelper.core.ui.components.DialogDragHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Developer-only app statistics (§3.13 Phase 10):
 *   — aggregate counters (accounts / messages / errors)
 *   — per-user activity: full name, role, event count, relative last-seen
 *   — top event types (translated to Russian so the picker is readable)
 *
 * Time formatted Yekaterinburg, 12-hour AM/PM — consistent with the rest
 * of the app after the Stage-3 locale pass.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppStatsDialog(onDismiss: () -> Unit) {
    var sinceDays by remember { mutableIntStateOf(7) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var showErrorsDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reload() {
        loading = true; errorMessage = ""
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.getAppStats(sinceDays) }
                if (response.optBoolean("ok", false)) {
                    data = response.optJSONObject("data")
                } else {
                    errorMessage = response.optString("error", "Не удалось загрузить")
                }
            } catch (_: Exception) {
                errorMessage = "Нет соединения"
            } finally { loading = false }
        }
    }

    LaunchedEffect(sinceDays) { reload() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgElevated,
        dragHandle = { DialogDragHandle() },
    ) {
        // §TZ-2.3.5: sheet-контент — один vertically-scrollable Column
        // + видимый правосторонний thin-scrollbar (developer-only диалог —
        // юзер хочет знать сколько прокручено). Раньше внутри были вложенные
        // LazyColumn с `heightIn(max=…)`: nested scroll в Compose ломается,
        // «Топ действий» уезжал за экран без способа прокрутить. Теперь
        // плоский Column.verticalScroll + thumb справа как у Material Finder.
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            // §TZ-2.3.6 — content'у нужен правый отступ 14dp чтобы текст
            // цифр справа (например «309» в NetActionRow) не заходил за
            // thumb скроллбара (thumb width=3dp + end=6dp = ~10dp от края;
            // даём ещё 4dp air'а).
            Column(
                modifier = Modifier
                    .padding(end = 14.dp)
                    .verticalScroll(scrollState)
            ) {
            Text("Статистика приложения", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text("За последние $sinceDays дн.", color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 7, 30).forEach { d ->
                    FilterChip(
                        selected = sinceDays == d,
                        onClick = { sinceDays = d },
                        label = { Text("$d дн.", fontSize = 12.sp) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (loading && data == null) {
                Box(Modifier.fillMaxWidth().height(120.dp)) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Accent)
                }
            } else if (errorMessage.isNotEmpty()) {
                Text(errorMessage, color = StatusErrorBorder, fontSize = 13.sp)
            } else data?.let { d ->
                StatsList(
                    totalUsers = d.optInt("total_users"),
                    totalMessages = d.optInt("total_messages"),
                    totalErrors = d.optInt("total_errors_window"),
                    onErrorsClick = { showErrorsDialog = true },
                )

                Spacer(Modifier.height(14.dp))
                SectionHeader("Активные пользователи")
                Text(
                    "Кто заходил в приложение за выбранный период — рядом число " +
                        "действий (открытия экранов, отправки сообщений, голосования) и " +
                        "когда последний раз был онлайн.",
                    color = TextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                Spacer(Modifier.height(6.dp))
                val users = d.optJSONArray("active_users")
                val userList = buildList {
                    for (i in 0 until (users?.length() ?: 0)) users?.optJSONObject(i)?.let { add(it) }
                }
                if (userList.isEmpty()) {
                    Text("Нет активности", color = TextTertiary, fontSize = 13.sp)
                } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    userList.forEach { u -> UserStatRow(u) }
                }

                Spacer(Modifier.height(14.dp))
                SectionHeader("Топ действий")
                Text(
                    "Самые частые действия в приложении за период. Справа — сколько раз " +
                        "такое действие произошло.",
                    color = TextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                Spacer(Modifier.height(6.dp))
                val events = d.optJSONArray("top_events")
                val eventList = buildList {
                    for (i in 0 until (events?.length() ?: 0)) events?.optJSONObject(i)?.let { add(it) }
                }.filter {
                    // §TZ-2.3.38 — прячем диагностические/неизвестные event_type'ы
                    // из топа. Пользовательская статистика должна содержать только
                    // действия юзера, а не внутренние сигналы.
                    isDisplayableEvent(it.optString("event_type"))
                }
                if (eventList.isEmpty()) {
                    Text("Нет данных", color = TextTertiary, fontSize = 13.sp)
                } else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    eventList.forEach { e -> EventStatRow(e) }
                }

                // ── Секция «Сеть» ─────────────────────────────────────────
                Spacer(Modifier.height(14.dp))
                NetworkStatsSection(sinceDays = sinceDays)
            }
            }
            // Правосторонний тонкий скроллбар (видим ТОЛЬКО во время
            // прокрутки или когда контент переполняет viewport). Canvas-
            // бейс — легковесный, без side-effects. Toggle-visible по
            // scrollState.maxValue > 0.
            VerticalScrollThumb(scrollState = scrollState)
        }
    }

    if (showErrorsDialog) {
        AppErrorsDialog(sinceDays = sinceDays, onDismiss = { showErrorsDialog = false })
    }
}

/**
 * Тонкий visual-only scrollbar для `Column.verticalScroll`. Показывает
 * thumb справа по центру viewport'а, прямо пропорциональный отношению
 * viewport/content. Скрывается если content вмещается целиком.
 *
 * НЕ touchable — это индикатор, не контрол. Жест прокрутки остаётся у
 * родителя (Column.verticalScroll).
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.VerticalScrollThumb(
    scrollState: androidx.compose.foundation.ScrollState,
) {
    val maxValue = scrollState.maxValue
    if (maxValue <= 0) return  // content помещается — thumb не нужен
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Track занимает всю доступную высоту родителя (ModalBottomSheet content),
    // не фикс 240dp. Раньше thumb путал — на длинном контенте он «обрывался»
    // на середине экрана, а ниже был ещё контент. Теперь track идёт от самого
    // верха до самого низа sheet'а, thumb всегда правильно показывает позицию.
    Box(
        modifier = Modifier
            // §TZ-2.3.6 — бОльший отступ от правого края (раньше был 2dp,
            // юзер жаловался что значения в правых колонках перекрывают thumb).
            .align(Alignment.CenterEnd)
            .padding(end = 6.dp, top = 8.dp, bottom = 8.dp)
            .width(3.dp)
            .fillMaxHeight()
    ) {
        val thumbColor = TextTertiary  // capture in composable scope before DrawScope
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val trackHeightPx = size.height
            val viewportHeightPx = trackHeightPx  // viewport ≈ track height
            val contentHeightPx = viewportHeightPx + maxValue
            val thumbHeightPx = (trackHeightPx * viewportHeightPx / contentHeightPx)
                .coerceAtLeast(with(density) { 24.dp.toPx() })
            val scrollFraction = scrollState.value.toFloat() / maxValue.toFloat()
            val thumbOffsetPx = (trackHeightPx - thumbHeightPx) * scrollFraction.coerceIn(0f, 1f)
            drawRoundRect(
                color = thumbColor.copy(alpha = 0.12f),
                topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                size = androidx.compose.ui.geometry.Size(size.width, trackHeightPx),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f),
            )
            drawRoundRect(
                color = thumbColor.copy(alpha = 0.55f),
                topLeft = androidx.compose.ui.geometry.Offset(0f, thumbOffsetPx),
                size = androidx.compose.ui.geometry.Size(size.width, thumbHeightPx),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f),
            )
        }
    }
}

// ── Network observability section (2026-04) ────────────────────────────────

@Composable
private fun NetworkStatsSection(sinceDays: Int) {
    var stats by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sinceDays) {
        loading = true
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.getNetworkStats(sinceDays) }
                if (response.optBoolean("ok", false)) {
                    stats = response.optJSONObject("data")
                }
            } catch (_: Exception) { /* empty → показываем placeholder */ }
            finally { loading = false }
        }
    }

    SectionHeader("Сеть")
    Text(
        "Скорость и ошибки HTTP-запросов за выбранный период. Среднее — " +
            "ориентир на «как юзер чувствует». Медленные (>2с) — кандидаты на оптимизацию.",
        color = TextTertiary,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
    Spacer(Modifier.height(8.dp))

    when {
        loading -> Box(Modifier.fillMaxWidth().height(60.dp)) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(20.dp),
                color = Accent,
                strokeWidth = 2.dp,
            )
        }
        stats == null -> Text("Нет данных", color = TextTertiary, fontSize = 13.sp)
        else -> stats?.let { d ->
            // Summary: total + error rate
            val total = d.optInt("total_requests")
            val errorRate = d.optDouble("error_rate", 0.0)
            val slowUsers = d.optJSONArray("slow_users")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetSummaryChip("Запросов", total.toString(), Modifier.weight(1f))
                NetSummaryChip(
                    "Ошибок",
                    if (total > 0) "${(errorRate * 100).toInt()}%" else "—",
                    Modifier.weight(1f),
                    valueColor = if (errorRate > 0.05) StatusErrorBorder else TextPrimary,
                )
            }

            if (slowUsers != null && slowUsers.length() > 0) {
                Spacer(Modifier.height(6.dp))
                val logins = buildList {
                    for (i in 0 until slowUsers.length()) add(slowUsers.optString(i))
                }.filter { it.isNotBlank() }
                if (logins.isNotEmpty()) {
                    Text(
                        "Тормозит у: ${logins.joinToString(", ")}",
                        color = StatusErrorBorder,
                        fontSize = 11.sp,
                    )
                }
            }

            // Per-action breakdown
            Spacer(Modifier.height(8.dp))
            val byAction = d.optJSONArray("by_action")
            val actionList = buildList {
                for (i in 0 until (byAction?.length() ?: 0)) byAction?.optJSONObject(i)?.let { add(it) }
            }
            if (actionList.isEmpty()) {
                Text("Нет запросов за период", color = TextTertiary, fontSize = 12.sp)
            } else {
                // §TZ-2.3.5+ — LazyColumn внутри с ограниченной высотой, но
                // «изолированный» от parent'а: когда юзер скроллит внутри блока
                // и доходит до конца, impulse НЕ передаётся наружу (иначе
                // родительский диалог начинает лететь вверх-вниз). Достигли
                // края — остановились. Jet в parent только когда юзер реально
                // взял диалог за заголовок/стабильный участок.
                val isolatedFromParent = remember {
                    object : NestedScrollConnection {
                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource,
                        ): Offset = available
                        override suspend fun onPostFling(
                            consumed: Velocity,
                            available: Velocity,
                        ): Velocity = available
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .nestedScroll(isolatedFromParent),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(actionList, key = { it.optString("action") }) { a -> NetActionRow(a) }
                }
            }
        }
    }
}

@Composable
private fun NetSummaryChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BgCard)
            .border(0.5.dp, BorderDivider, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(value, color = valueColor, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(label, color = TextTertiary, fontSize = 11.sp)
    }
}

@Composable
private fun NetActionRow(a: JSONObject) {
    val action = actionLabel(a.optString("action"))
    val requests = a.optInt("requests")
    val errors = a.optInt("errors")
    val avgMs = a.optInt("avg_ms")
    val maxMs = a.optInt("max_ms")
    val slow = a.optInt("slow_count")
    val avgColor = when {
        avgMs > 3000 -> StatusErrorBorder
        avgMs > 1500 -> Accent
        else -> TextPrimary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(action, color = TextPrimary, fontSize = 13.sp, maxLines = 1)
            Text(
                buildString {
                    append("$requests запр.")
                    if (errors > 0) append(" · $errors ошиб.")
                    if (slow > 0) append(" · $slow медл.")
                },
                color = TextTertiary,
                fontSize = 11.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${avgMs}мс", color = avgColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (maxMs > 0 && maxMs != avgMs) {
                Text("max ${maxMs}", color = TextTertiary, fontSize = 10.sp)
            }
        }
    }
}

// ── Errors popup ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppErrorsDialog(sinceDays: Int, onDismiss: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sinceDays) {
        loading = true
        errorMessage = ""
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.getAppErrors(sinceDays, 80)
                }
                if (response.optBoolean("ok", false)) {
                    val arr: JSONArray = response.optJSONObject("data")?.optJSONArray("errors")
                        ?: JSONArray()
                    errors = buildList {
                        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(it) }
                    }
                } else {
                    errorMessage = response.optString("error", "Не удалось загрузить")
                }
            } catch (_: Exception) {
                errorMessage = "Нет соединения"
            } finally { loading = false }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgElevated,
        dragHandle = { DialogDragHandle() },
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(StatusErrorBorder.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.BugReport,
                        contentDescription = null,
                        tint = StatusErrorBorder,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Ошибки приложения",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "За последние $sinceDays дн.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            when {
                loading -> Box(Modifier.fillMaxWidth().height(120.dp)) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Accent,
                    )
                }
                errorMessage.isNotEmpty() -> Text(
                    errorMessage,
                    color = StatusErrorBorder,
                    fontSize = 13.sp,
                )
                errors.isEmpty() -> Text(
                    "Ошибок нет — всё ровно.",
                    color = TextTertiary,
                    fontSize = 13.sp,
                )
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(errors) { err -> ErrorRow(err) }
                }
            }
        }
    }
}

@Composable
private fun ErrorRow(e: JSONObject) {
    val user = e.optString("user_login").ifBlank { "—" }
    val version = e.optString("app_version").ifBlank { "—" }
    val errorClass = e.optString("error_class").substringAfterLast('.')
    val errorMsg = e.optString("error_message").take(200)
    val device = e.optString("device_model").ifBlank { "" }
    val os = e.optString("os_version").ifBlank { "" }
    val occurredAt = prettyLastSeen(e.optString("occurred_at"))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgCard)
            .border(0.5.dp, BorderDivider, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                errorClass.ifBlank { "Ошибка" },
                color = StatusErrorBorder,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                occurredAt,
                color = TextTertiary,
                fontSize = 11.sp,
            )
        }
        if (errorMsg.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                errorMsg,
                color = TextPrimary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        val deviceLine = buildString {
            append("$user · v$version")
            if (device.isNotBlank()) append(" · $device")
            if (os.isNotBlank()) append(" · $os")
        }
        Text(
            deviceLine,
            color = TextTertiary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = TextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * Вертикальный список «Аккаунтов / Сообщений / Ошибок» — вместо мелких
 * горизонтальных блоков. Ошибки кликабельны: открывают popup со списком
 * конкретных крашей.
 */
@Composable
private fun StatsList(
    totalUsers: Int,
    totalMessages: Int,
    totalErrors: Int,
    onErrorsClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatRow(
            icon = Icons.Outlined.People,
            iconTint = Accent,
            label = "Аккаунтов",
            value = totalUsers.toString(),
        )
        StatRow(
            icon = Icons.AutoMirrored.Outlined.Chat,
            iconTint = Accent,
            label = "Сообщений",
            value = totalMessages.toString(),
        )
        StatRow(
            icon = Icons.Default.BugReport,
            iconTint = if (totalErrors > 0) StatusErrorBorder else TextTertiary,
            label = "Ошибок",
            value = totalErrors.toString(),
            chevron = totalErrors > 0,
            onClick = if (totalErrors > 0) onErrorsClick else null,
            valueColor = if (totalErrors > 0) StatusErrorBorder else TextPrimary,
        )
    }
}

@Composable
private fun StatRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    valueColor: Color = TextPrimary,
) {
    val clickable = onClick != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCard)
            .border(0.5.dp, BorderDivider, RoundedCornerShape(12.dp))
            .then(if (clickable) Modifier.clickable(onClick = onClick!!) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun UserStatRow(u: JSONObject) {
    val role = roleLabel(u.optString("role"))
    val lastSeen = prettyLastSeen(u.optString("last_seen"))
    val events = u.optInt("events")
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BgCard)
            .border(0.5.dp, BorderDivider, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                u.optString("full_name").ifBlank { u.optString("login") },
                color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
            Text(
                "$role · был(а) $lastSeen",
                color = TextTertiary, fontSize = 11.sp, maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("$events", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("действий", color = TextTertiary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun EventStatRow(e: JSONObject) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(eventLabel(e.optString("event_type")), color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(e.optInt("count").toString(), color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Labels ──────────────────────────────────────────────────────────────────

private fun roleLabel(raw: String): String = when (raw.lowercase()) {
    "developer", "superadmin" -> "Разработчик"
    "admin" -> "Администратор"
    "user" -> "Пользователь"
    else -> raw.ifBlank { "—" }
}

/**
 * Русские имена для `action` из таблицы `network_metrics` — это имена
 * серверных endpoints. Раньше показывались raw строкой («heartbeat»,
 * «base_version», «get_news») — юзер жаловался что «список метрик на
 * английском». Единый язык UI — русский для dev-статы.
 */
private fun actionLabel(raw: String): String = when (raw) {
    "heartbeat" -> "Сердцебиение"
    "me" -> "Профиль"
    "login" -> "Вход"
    "logout" -> "Выход"
    "app_status" -> "Проверка версии"
    "base_version" -> "Справочник: версия"
    "base_download" -> "Справочник: загрузка"
    "base_download_url" -> "Справочник: ссылка"
    "base_find" -> "Справочник: поиск"
    "get_news" -> "Новости"
    "get_admin_messages" -> "Сообщения админа"
    "get_user_chat" -> "Чат пользователя"
    "get_admin_chat" -> "Чат с админом"
    "get_unread_counts" -> "Счётчики непрочитанного"
    "get_users" -> "Список пользователей"
    "send_message" -> "Отправка сообщения"
    "send_news" -> "Отправка новости"
    "mark_message_read" -> "Отметить прочитанным"
    "add_reaction" -> "Добавить реакцию"
    "remove_reaction" -> "Снять реакцию"
    "get_reactions" -> "Реакции"
    "create_news_poll" -> "Создать опрос"
    "vote_news_poll" -> "Голос в опросе"
    "log_activity" -> "Телеметрия: активность"
    "log_errors" -> "Телеметрия: ошибки"
    "register_push_token" -> "Регистрация push"
    "set_avatar" -> "Смена аватара"
    "change_password" -> "Смена пароля"
    "get_network_stats" -> "Сетевая статистика"
    "get_app_errors" -> "Клиентские ошибки"
    "get_news_readers" -> "Кто прочитал новость"
    "pin_message" -> "Закрепить сообщение"
    "unpin_message" -> "Открепить сообщение"
    "edit_message" -> "Правка сообщения"
    "soft_delete_message" -> "Удалить сообщение"
    "undelete_message" -> "Восстановить сообщение"
    "mute_contact" -> "Отключить уведомления"
    "unmute_contact" -> "Включить уведомления"
    "get_muted_contacts" -> "Список заглушённых"
    "set_dnd_schedule" -> "Тихие часы"
    "get_app_stats" -> "Статистика приложения"
    "get_audit_log" -> "Журнал действий"
    "save_draft" -> "Сохранить черновик"
    "load_draft" -> "Загрузить черновик"
    "list_drafts" -> "Список черновиков"
    "schedule_message" -> "Отложить сообщение"
    "list_scheduled" -> "Список запланированных"
    "cancel_scheduled" -> "Отменить запланированное"
    "rebroadcast_base" -> "Рассылка: справочник"
    "broadcast_app_version" -> "Рассылка: версия"
    "client_debug" -> "Отладка клиента"
    else -> raw.ifBlank { "—" }
}

private val KNOWN_EVENT_LABELS = mapOf(
    "screen_open" to "Открытие экрана",
    "search_query" to "Поиск по базе",
    "message_sent" to "Отправка сообщения",
    "message_edited" to "Правка сообщения",
    "message_deleted" to "Удаление сообщения",
    "poll_voted" to "Голос в опросе",
    "reaction_added" to "Реакция поставлена",
    "reaction_removed" to "Реакция снята",
    "news_read" to "Прочтение новости",
    "attachment_opened" to "Открытие вложения",
    "login" to "Вход в приложение",
    "logout" to "Выход из аккаунта",
    "password_changed" to "Смена пароля",
    "avatar_uploaded" to "Смена аватара",
    "app_resume" to "Возврат в приложение",
    "app_pause" to "Сворачивание приложения",
    "app_update_banner" to "Показ уведомления об обновлении",
    "heartbeat" to "Сердцебиение сессии",
    "slow_action" to "Медленный запрос",
    "security_posture" to "Проверка безопасности",
)

/**
 * §TZ-2.3.38 — решает показывать ли event в «Топе действий». Прячем всё что
 * не в whitelist'е (диагностические `enc_blob_*`, `blob_*`, unknown типы) —
 * они захламляют список пользовательской статистики.
 */
private fun isDisplayableEvent(raw: String): Boolean =
    raw.isNotBlank() && KNOWN_EVENT_LABELS.containsKey(raw)

private fun eventLabel(raw: String): String =
    KNOWN_EVENT_LABELS[raw] ?: raw.ifBlank { "—" }

// ── Time formatting ─────────────────────────────────────────────────────────

private val yekZone = ZoneId.of("Asia/Yekaterinburg")
private val lastSeenSameDay = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val lastSeenOtherDay = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.forLanguageTag("ru"))

/**
 * Human "last seen" — 12-hour, AM/PM, Yekaterinburg zone. Same-day events
 * drop the date prefix; anything older prepends "d MMM" in Russian so the
 * picker reads naturally.
 */
private fun prettyLastSeen(raw: String): String {
    if (raw.isBlank()) return "давно"
    return try {
        val iso = if (raw.contains('T')) {
            if (raw.endsWith("Z") || raw.contains('+')) raw else "${raw}Z"
        } else {
            "${raw.replace(' ', 'T')}Z"
        }
        val zdt = ZonedDateTime.parse(iso).withZoneSameInstant(yekZone)
        val today = ZonedDateTime.now(yekZone).toLocalDate()
        if (zdt.toLocalDate() == today) zdt.format(lastSeenSameDay)
        else zdt.format(lastSeenOtherDay)
    } catch (_: Exception) {
        raw.take(16)
    }
}
