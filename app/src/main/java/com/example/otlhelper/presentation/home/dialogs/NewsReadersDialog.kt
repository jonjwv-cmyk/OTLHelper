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
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsReadersDialog(
    item: JSONObject,
    isAdmin: Boolean,
    onDismiss: () -> Unit,
    onLoadReaders: (messageId: Long, callback: (JSONObject?) -> Unit) -> Unit,
    onPinToggle: (msgId: Long, pin: Boolean, callback: (Boolean, String) -> Unit) -> Unit
) {
    val messageId = item.optLong("id", 0L)
    val isPinned = item.optInt("is_pinned", 0) != 0

    var readersData by remember { mutableStateOf<JSONObject?>(null) }
    var pinned by remember { mutableStateOf(isPinned) }
    var pinStatus by remember { mutableStateOf("") }

    LaunchedEffect(messageId) {
        if (messageId > 0L) onLoadReaders(messageId) { readersData = it }
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
            Text("Статистика", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = Accent, thickness = 1.dp)
            Spacer(Modifier.height(12.dp))

            if (readersData == null) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
                }
            } else {
                // Server returns: { ok, data: { read_users: [{user_login, full_name, role, read_at}], unread_users: [...] } }
                val dataObj = readersData!!.optJSONObject("data") ?: readersData!!
                val readers = dataObj.optJSONArray("read_users")
                val notReaders = dataObj.optJSONArray("unread_users")
                val totalReaders = readers?.length() ?: 0
                val totalNot = notReaders?.length() ?: 0

                Text(
                    "Прочитали: $totalReaders, не прочитали: $totalNot",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))

                if (readers != null && readers.length() > 0) {
                    Text("✅ Прочитали", color = StatusOkBorder, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    for (i in 0 until readers.length()) {
                        val obj = readers.optJSONObject(i) ?: continue
                        val name = obj.optString("full_name", "").ifBlank { obj.optString("user_login", "") }
                        val readAt = obj.optString("read_at", "")
                        if (name.isBlank()) continue
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("• $name", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            if (readAt.isNotBlank()) {
                                Text(formatDate(readAt), color = TextTertiary, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (notReaders != null && notReaders.length() > 0) {
                    Text("⬜ Не прочитали", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    for (i in 0 until notReaders.length()) {
                        val obj = notReaders.optJSONObject(i) ?: continue
                        val name = obj.optString("full_name", "").ifBlank { obj.optString("user_login", "") }
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
                    // §TZ-2.3.24 — optimistic toggle + haptic (как Settings).
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
                if (pinStatus.isNotBlank()) Text(pinStatus, color = StatusErrorBorder, fontSize = 13.sp)
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextPrimary)
            ) { Text("Закрыть") }
        }
    }
}
