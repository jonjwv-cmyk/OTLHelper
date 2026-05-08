package com.example.otlhelper.presentation.pc_login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * §TZ-0.10.5 — Список активных PC-сессий текущего юзера. Кнопка «Прервать»
 * на каждой → revoke_pc_session → desktop получит 401 на следующем /api → lock.
 */
@Composable
fun ActivePcSessionsScreen(onClose: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var sessions by remember { mutableStateOf<JSONArray>(JSONArray()) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        error = ""
        try {
            val resp = withContext(Dispatchers.IO) { ApiClient.listPcSessions() }
            if (resp.optBoolean("ok", false)) {
                sessions = resp.optJSONArray("sessions") ?: JSONArray()
            } else {
                error = "Ошибка: ${resp.optString("error", "unknown")}"
            }
        } catch (e: Exception) {
            error = "Сбой: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Активные PC-сессии",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))

            when {
                loading -> CircularProgressIndicator()
                error.isNotBlank() -> Text(error, color = MaterialTheme.colorScheme.error)
                sessions.length() == 0 -> Text(
                    "Нет активных PC-сессий",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
                else -> {
                    for (i in 0 until sessions.length()) {
                        val s = sessions.optJSONObject(i) ?: continue
                        SessionCard(
                            session = s,
                            onRevoke = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        ApiClient.revokePcSession(s.optString("session_id"))
                                    }
                                    reload()
                                }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { reload() } },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Обновить") }
                Button(
                    onClick = onClose,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Закрыть") }
            }
        }
    }
}

@Composable
private fun SessionCard(session: JSONObject, onRevoke: () -> Unit) {
    val device = session.optString("device_label").ifBlank { "Устройство" }
    val kind = when (session.optString("session_kind")) {
        "pc_qr" -> "QR"
        "pc_password" -> "пароль"
        else -> session.optString("session_kind")
    }
    val expiresAt = session.optString("expires_at")
    val extLeft = session.optInt("extensions_remaining", 0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(device, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            "Вход через $kind · до $expiresAt UTC · продлений осталось $extLeft",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onRevoke,
            modifier = Modifier.fillMaxWidth().height(36.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("Прервать", fontSize = 13.sp)
        }
    }
}
