package com.example.otlhelper.desktop.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.desktop.model.Role
import com.example.otlhelper.desktop.theme.Accent
import com.example.otlhelper.desktop.theme.BgCard
import com.example.otlhelper.desktop.theme.BorderDivider
import com.example.otlhelper.desktop.theme.StatusOkBorder
import com.example.otlhelper.desktop.theme.TextPrimary
import com.example.otlhelper.desktop.theme.TextSecondary
import com.example.otlhelper.desktop.theme.TextTertiary

// §TZ-DESKTOP-0.1.0 — точная копия PollStatsDialog / NewsReadersDialog с Android.

data class ReaderMock(val name: String, val readAt: String)
data class ReadersData(val read: List<ReaderMock>, val unread: List<String>)

/** Точная копия app/.../NewsReadersDialog.kt. */
@Composable
fun NewsReadersDialog(
    data: ReadersData,
    isAdmin: Boolean,
    isPinnedInitial: Boolean = false,
    onPinToggle: (pin: Boolean) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var pinned by remember { mutableStateOf(isPinnedInitial) }

    BottomSheetShell(onDismiss = onDismiss) {
        Text("Статистика", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = Accent, thickness = 1.dp)
        Spacer(Modifier.height(12.dp))

        Text(
            "Прочитали: ${data.read.size}, не прочитали: ${data.unread.size}",
            color = TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))

        if (data.read.isNotEmpty()) {
            Text("✅ Прочитали", color = StatusOkBorder, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            data.read.forEach { r ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("• ${r.name}", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (r.readAt.isNotBlank()) {
                        Text(r.readAt, color = TextTertiary, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (data.unread.isNotEmpty()) {
            Text("⬜ Не прочитали", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            data.unread.forEach { name ->
                Text(
                    "• $name",
                    color = TextTertiary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
                )
            }
        }

        if (isAdmin) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = BorderDivider)
            Spacer(Modifier.height(12.dp))
            PinToggleRow(pinned = pinned, onToggle = { pinned = it; onPinToggle(it) })
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextPrimary),
        ) { Text("Закрыть") }
    }
}

// ── PollStatsDialog ──

data class PollStatsOption(
    val text: String,
    val votesCount: Int,
)
data class PollVoter(val name: String, val votedAt: String, val selected: List<String>)
data class PollStatsData(
    val totalVoters: Int,
    val totalSelections: Int,
    val totalAudience: Int,
    val options: List<PollStatsOption>,
    val voters: List<PollVoter>,
    val nonVoters: List<String>,
)

@Composable
fun PollStatsDialog(
    data: PollStatsData,
    isAdmin: Boolean,
    isPinnedInitial: Boolean = false,
    onPinToggle: (pin: Boolean) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var pinned by remember { mutableStateOf(isPinnedInitial) }

    BottomSheetShell(onDismiss = onDismiss) {
        Text("Результаты", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = Accent, thickness = 1.dp)
        Spacer(Modifier.height(12.dp))

        Text(
            buildString {
                append("Проголосовало ${data.totalVoters} из ${data.totalAudience}")
                if (data.totalSelections > data.totalVoters) append(" · ${data.totalSelections} отметок")
            },
            color = TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))

        // Per-option bar chart
        data.options.forEach { opt ->
            val pct = if (data.totalVoters > 0) (opt.votesCount * 100f / data.totalVoters) else 0f
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(opt.text, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${opt.votesCount} (${pct.toInt()}%)", color = TextSecondary, fontSize = 13.sp)
                }
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Accent,
                    trackColor = BgCard,
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        if (data.voters.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("✅ Проголосовали", color = StatusOkBorder, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            data.voters.forEach { v ->
                Column(modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("• ${v.name}", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (v.votedAt.isNotBlank()) {
                            Text(v.votedAt, color = TextTertiary, fontSize = 11.sp)
                        }
                    }
                    if (v.selected.isNotEmpty()) {
                        Text(
                            "→ ${v.selected.joinToString(", ")}",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 10.dp, top = 1.dp),
                        )
                    }
                }
            }
        }

        if (data.nonVoters.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("⬜ Не проголосовали", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            data.nonVoters.forEach { n ->
                Text(
                    "• $n",
                    color = TextTertiary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
                )
            }
        }

        if (isAdmin) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = BorderDivider)
            Spacer(Modifier.height(12.dp))
            PinToggleRow(pinned = pinned, onToggle = { pinned = it; onPinToggle(it) })
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextPrimary),
        ) { Text("Закрыть") }
    }
}

// ── ReactionVotersDialog (developer-only) ──

data class ReactionVoter(val name: String, val emoji: String, val time: String)

@Composable
fun ReactionVotersDialog(
    voters: List<ReactionVoter>,
    onDismiss: () -> Unit,
) {
    BottomSheetShell(onDismiss = onDismiss) {
        Text("Реакции", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = Accent, thickness = 1.dp)
        Spacer(Modifier.height(12.dp))

        if (voters.isEmpty()) {
            Text(
                "Никто ещё не отреагировал",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 20.dp),
            )
            return@BottomSheetShell
        }

        val byEmoji = voters.groupBy { it.emoji }
        byEmoji.forEach { (emoji, list) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
            ) {
                Text(emoji, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${list.size}",
                    color = Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(
                            com.example.otlhelper.desktop.theme.AccentSubtle,
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            list.forEach { v ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("• ${v.name}", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(v.time, color = TextTertiary, fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextPrimary),
        ) { Text("Закрыть") }
    }
}

// ── shared UI ──

@Composable
private fun PinToggleRow(pinned: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = pinned,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = BgCard,
                uncheckedBorderColor = BorderDivider,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Text(if (pinned) "Закреплено" else "Закрепить", color = TextPrimary, fontSize = 14.sp)
    }
}

/**
 * §TZ-DESKTOP-0.1.0 — шторка SF2026: либо drag-handle (модалка), либо
 * SF-NavBar с «← Назад» + title (меню-вкладка). Переключается через [title].
 */
@Composable
fun BottomSheetShell(
    onDismiss: () -> Unit,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    dismissOnOutsideClick: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    // §TZ-DESKTOP-0.1.0 — tap-gesture detection вместо `.clickable`:
    // `.clickable(enabled=false)` на inner Column не абсорбирует tap gesture
    // надёжно — Compose Desktop Multiplatform тап на TextField внутри sheet'а
    // пропускал до outer `.clickable { onDismiss }` → sheet закрывался при
    // первом клике в поле ввода (юзерская жалоба «выкидывает при печати
    // опроса»). `pointerInput + detectTapGestures` формирует полноценную
    // tap-обработку — inner absorber перехватывает tap до того как outer
    // его увидит, onDismiss срабатывает только при клике в scrim.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .then(
                if (dismissOnOutsideClick) Modifier.pointerInput(onDismiss) {
                    detectTapGestures(onTap = { onDismiss() })
                } else Modifier
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(com.example.otlhelper.desktop.theme.BgElevated)
                .border(0.5.dp, BorderDivider, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .pointerInput(Unit) {
                    // Абсорб — детектит tap но ничего не делает. Блокирует
                    // всплытие tap-gesture к outer (→ onDismiss).
                    detectTapGestures { /* absorb */ }
                },
        ) {
            if (title != null) {
                SheetNavBar(title = title, onBack = onBack ?: onDismiss)
            } else {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(34.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(BorderDivider),
                )
                Spacer(Modifier.height(12.dp))
            }
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
            }
        }
    }
}

/**
 * SF-2026-style nav-bar для шторки:
 *   [← Назад]     Заголовок     [  ]
 * С тонкой bottom-линией. Back-кнопка имеет SF-style chevron.
 */
@Composable
private fun SheetNavBar(title: String, onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    Icons.AutoMirrored.Filled.ArrowBackIos,
                    contentDescription = "Назад",
                    tint = Accent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "Назад",
                    color = Accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                title,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            // Симметрия (ширина "← Назад" примерно)
            Spacer(Modifier.width(72.dp))
        }
        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(BorderDivider))
    }
}
