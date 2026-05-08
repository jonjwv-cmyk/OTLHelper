package com.example.otlhelper.presentation.home.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.*
import com.example.otlhelper.core.ui.components.DialogDragHandle
import com.example.otlhelper.core.ui.formatDate
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PollStatsDialog(
    item: JSONObject,
    isAdmin: Boolean,
    onDismiss: () -> Unit,
    onLoadStats: (pollId: Long, callback: (JSONObject?) -> Unit) -> Unit,
    onPinToggle: (msgId: Long, pin: Boolean, callback: (Boolean, String) -> Unit) -> Unit
) {
    val poll = item.optJSONObject("poll") ?: item
    val pollId = poll.optLong("poll_id", poll.optLong("id", item.optLong("id", 0L)))
    val messageId = item.optLong("id", 0L)
    val isPinned = item.optInt("is_pinned", 0) != 0

    var statsData by remember { mutableStateOf<JSONObject?>(null) }
    var pinned by remember { mutableStateOf(isPinned) }
    var pinStatus by remember { mutableStateOf("") }

    LaunchedEffect(pollId) {
        if (pollId > 0L) onLoadStats(pollId) { statsData = it }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        dragHandle = { DialogDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Результаты", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = Accent, thickness = 1.dp)
            Spacer(Modifier.height(12.dp))

            if (statsData == null) {
                Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
                }
            } else {
                // Server payload: { ok, data: {
                //   poll, total_voters, total_selections,
                //   options: [{ id, option_text, votes_count, voters:[{user_login, created_at}] }],
                //   voters:     [{ user_login, full_name, role, voted_at, selected_option_texts, ... }],
                //   non_voters: [{ user_login, full_name, role, voted_at='', selected_option_texts=[] }]
                // }}
                val dataObj = statsData!!.optJSONObject("data") ?: statsData!!
                val options = dataObj.optJSONArray("options")
                val voters = dataObj.optJSONArray("voters")
                val nonVoters = dataObj.optJSONArray("non_voters")
                val totalVoters = dataObj.optInt("total_voters", 0)
                val totalSelections = dataObj.optInt("total_selections", 0)
                // Sum of voters + non_voters = the total audience that *could*
                // vote. Fall back to totalVoters when the server doesn't ship
                // non_voters (older builds).
                val totalAudience = totalVoters +
                    (nonVoters?.length() ?: 0)

                Text(
                    buildString {
                        append("Проголосовало $totalVoters из $totalAudience")
                        // Only mention "отметок" when multi-select made the
                        // count diverge from the voter count.
                        if (totalSelections > totalVoters) {
                            append(" · $totalSelections отметок")
                        }
                    },
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))

                // ── Per-option bar chart with counts ──────────────────────
                if (options != null) {
                    for (i in 0 until options.length()) {
                        val opt = options.optJSONObject(i) ?: continue
                        val text = opt.optString("option_text", opt.optString("text", ""))
                        val count = opt.optInt("votes_count", opt.optInt("vote_count", 0))
                        val pct = if (totalVoters > 0) (count * 100f / totalVoters) else 0f

                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text("$count (${pct.toInt()}%)", color = TextSecondary, fontSize = 13.sp)
                            }
                            LinearProgressIndicator(
                                progress = { pct / 100f },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = Accent,
                                trackColor = BgCard
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }

                // ── Who voted (name · when · selected options) ────────────
                if (voters != null && voters.length() > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("✅ Проголосовали", color = StatusOkBorder, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    for (j in 0 until voters.length()) {
                        val v = voters.optJSONObject(j) ?: continue
                        val name = v.optString("full_name", "").ifBlank { v.optString("user_login", "") }
                        val votedAt = v.optString("voted_at", "")
                        val chosen = joinOptionTexts(v.optJSONArray("selected_option_texts"))

                        Column(modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("• $name", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                if (votedAt.isNotBlank()) {
                                    Text(formatDate(votedAt), color = TextTertiary, fontSize = 11.sp)
                                }
                            }
                            if (chosen.isNotBlank()) {
                                Text(
                                    "→ $chosen",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 10.dp, top = 1.dp)
                                )
                            }
                        }
                    }
                }

                // ── Who didn't vote ───────────────────────────────────────
                if (nonVoters != null && nonVoters.length() > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text("⬜ Не проголосовали", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    for (k in 0 until nonVoters.length()) {
                        val nv = nonVoters.optJSONObject(k) ?: continue
                        val name = nv.optString("full_name", "").ifBlank { nv.optString("user_login", "") }
                        if (name.isBlank()) continue
                        Text(
                            "• $name",
                            color = TextTertiary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 8.dp, top = 3.dp, bottom = 3.dp)
                        )
                    }
                }
            }

            if (isAdmin && messageId > 0L) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = BorderDivider)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // §TZ-2.3.24 — optimistic toggle + haptic. Раньше `pinned`
                    // обновлялся ТОЛЬКО после ответа сервера → ползунок "застревал"
                    // на 200-500мс пока идёт round-trip (в Settings toggle мгновенный,
                    // там локальное state+prefs). Теперь обновляем сразу, при ошибке
                    // откатываем. Это стандартный optimistic UX.
                    val pinFeedback = com.example.otlhelper.core.feedback.LocalFeedback.current
                    val pinView = androidx.compose.ui.platform.LocalView.current
                    Switch(
                        checked = pinned,
                        onCheckedChange = { newVal ->
                            pinFeedback?.tap(pinView)
                            pinned = newVal  // optimistic
                            pinStatus = ""
                            onPinToggle(messageId, newVal) { ok, msg ->
                                if (!ok) {
                                    pinned = !newVal  // revert on failure
                                    pinStatus = msg
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = Accent,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = BgCard,
                            uncheckedBorderColor = BorderDivider,
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(if (pinned) "Закреплено" else "Закрепить", color = TextPrimary, fontSize = 14.sp)
                }
                if (pinStatus.isNotBlank()) {
                    Text(pinStatus, color = StatusErrorBorder, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextPrimary)
            ) { Text("Закрыть") }
        }
    }
}

/** Flatten `selected_option_texts` JSONArray into a comma-separated string. */
private fun joinOptionTexts(arr: JSONArray?): String {
    if (arr == null || arr.length() == 0) return ""
    val parts = mutableListOf<String>()
    for (i in 0 until arr.length()) {
        val s = arr.optString(i, "").trim()
        if (s.isNotBlank()) parts.add(s)
    }
    return parts.joinToString(", ")
}
