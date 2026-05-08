package com.example.otlhelper.presentation.home.dialogs.audit_log

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.ApiClient
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AccentSubtle
import com.example.otlhelper.core.theme.BgElevated
import com.example.otlhelper.core.theme.StatusErrorBorder
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.ui.components.DialogDragHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Developer-only audit log of admin actions (§3.15.a.Ж).
 *
 * Picker is a horizontal strip of admin/developer-role users who actually
 * have rows in admin_actions_log (including orphans whose user record was
 * renamed or deleted). Tap = filter; no free-text input.
 *
 * Each row is rendered via [AuditRow] with localised verbs and a human
 * target label. Time uses Yekaterinburg zone, "d MMM, HH:mm" format.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditLogDialog(onDismiss: () -> Unit) {
    var actorFilter by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var pickerUsers by remember { mutableStateOf<List<PickerUser>>(emptyList()) }
    var fullNameByLogin by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(Unit) {
        try {
            val resp = withContext(Dispatchers.IO) { ApiClient.getUsers() }
            val arr = resp.optJSONArray("data")
                ?: resp.optJSONArray("users")
                ?: resp.optJSONArray("results")
            val map = mutableMapOf<String, String>()
            for (i in 0 until (arr?.length() ?: 0)) {
                val u = arr?.optJSONObject(i) ?: continue
                val login = u.optString("login", "").trim()
                if (login.isNotBlank()) {
                    map[login] = u.optString("full_name", login)
                }
            }
            fullNameByLogin = map
        } catch (_: Exception) { /* picker falls back to login as label */ }
    }

    // Reload when the picker selection changes. No debounce — picker is
    // discrete, one tap = one request.
    LaunchedEffect(actorFilter) {
        loading = true
        errorMessage = ""
        try {
            val response = withContext(Dispatchers.IO) {
                ApiClient.getAuditLog(actorLogin = actorFilter.trim(), limit = 200)
            }
            if (response.optBoolean("ok", false)) {
                val data = response.optJSONArray("data")
                val list = buildList {
                    for (i in 0 until (data?.length() ?: 0)) {
                        data?.optJSONObject(i)?.let { add(it) }
                    }
                }
                entries = list
                total = response.optInt("total", list.size)

                // Server only ships `actors` on the unfiltered first call —
                // exactly when we want to populate the picker. Filtered
                // requests omit it so the chip row stays stable.
                val actorsArr = response.optJSONArray("actors")
                if (actorsArr != null && actorsArr.length() > 0) {
                    pickerUsers = buildList {
                        for (i in 0 until actorsArr.length()) {
                            val a = actorsArr.optJSONObject(i) ?: continue
                            val login = a.optString("login", "").trim()
                            if (login.isBlank()) continue
                            add(
                                PickerUser(
                                    login = login,
                                    fullName = fullNameByLogin[login] ?: login,
                                    role = a.optString("role", "").lowercase(),
                                    count = a.optInt("count", 0),
                                )
                            )
                        }
                    }.sortedWith(compareBy({ roleSortKey(it.role) }, { -it.count }))
                }
            } else {
                val err = response.optString("error", "Не удалось загрузить аудит")
                errorMessage = when (err) {
                    "superadmin_only" -> "Доступ только у разработчика"
                    else -> err
                }
            }
        } catch (_: Exception) {
            errorMessage = "Нет соединения"
        } finally {
            loading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgElevated,
        dragHandle = { DialogDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentSubtle),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Аудит действий",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp
                    )
                    Text(
                        if (loading) "загрузка…" else "$total записей",
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (pickerUsers.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item(key = "__all__") {
                        ActorChip(
                            label = "Все",
                            sublabel = null,
                            selected = actorFilter.isBlank(),
                            onClick = { actorFilter = "" }
                        )
                    }
                    items(pickerUsers, key = { it.login }) { u ->
                        ActorChip(
                            label = u.fullName,
                            sublabel = "${u.count}",
                            selected = actorFilter.equals(u.login, ignoreCase = true),
                            onClick = {
                                actorFilter = if (actorFilter.equals(u.login, ignoreCase = true)) "" else u.login
                            }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            if (errorMessage.isNotEmpty()) {
                Text(errorMessage, color = StatusErrorBorder, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
            }

            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 520.dp)) {
                when {
                    loading && entries.isEmpty() ->
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center).size(28.dp),
                            color = Accent,
                            strokeWidth = 2.5.dp
                        )

                    entries.isEmpty() ->
                        Text(
                            if (actorFilter.isBlank()) "Записей нет"
                            else "По «${actorFilter.trim()}» ничего не найдено",
                            color = TextTertiary,
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )

                    else ->
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(entries, key = { it.optLong("id", 0L) }) { entry ->
                                AuditRow(
                                    entry = entry,
                                    onActorTap = { login -> actorFilter = login }
                                )
                            }
                        }
                }
            }
        }
    }
}

// ── Picker ───────────────────────────────────────────────────────────────────

internal data class PickerUser(
    val login: String,
    val fullName: String,
    val role: String,
    val count: Int = 0,
)

@Composable
private fun ActorChip(
    label: String,
    sublabel: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Accent else AccentSubtle)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = if (selected) BgElevated else Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            if (sublabel != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    "· $sublabel",
                    color = if (selected) BgElevated.copy(alpha = 0.7f)
                            else Accent.copy(alpha = 0.65f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

/** Sort key — admin-grade first, regular users last. */
private fun roleSortKey(role: String): Int = when (role) {
    "developer", "superadmin" -> 0
    "admin" -> 1
    "user" -> 2
    else -> 3
}
