package com.example.otlhelper.presentation.home.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.ApiClient
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AccentSubtle
import com.example.otlhelper.core.theme.BgElevated
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.ui.UserAvatar
import com.example.otlhelper.core.ui.components.DialogDragHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * Developer-only voter list for a reaction. Opens on long-press of a reaction
 * chip. Shows who placed which emoji and when (Telegram-style).
 *
 * Server `get_reactions` payload:
 *   { aggregate, voters: { <emoji>: [{ user_login, full_name, created_at }] } }
 *
 * `get_reactions` doesn't carry avatar_url or presence, so we fetch the users
 * list in parallel on open. Both futures must resolve before the dialog
 * paints its first frame — avoids the "initials flash → avatars pop in"
 * double-render we used to ship.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionVotersDialog(
    messageId: Long,
    highlightEmoji: String = "",
    cachedJson: String = "",
    persistJson: (String) -> Unit = {},
    fetch: suspend (Long) -> JSONObject?,
    onDismiss: () -> Unit,
) {
    // Hydrate from local cache BEFORE the spinner ever shows. The cached
    // JSON is the ENRICHED payload — avatars + presence already merged
    // into each voter record — so rehydration doesn't need the users map
    // and avatars don't "pop in" after the fact. That was the previous
    // cache bug: we stored the raw server response (no avatar_url in it)
    // and parseVoters with an empty metaByLogin fell back to initials,
    // then the network response replaced with real avatars a beat later.
    val initialVoters: Map<String, List<Voter>> = remember(cachedJson) {
        if (cachedJson.isBlank()) emptyMap()
        else runCatching { parseVoters(JSONObject(cachedJson), emptyMap()) }
            .getOrDefault(emptyMap())
    }
    var loading by remember { mutableStateOf(initialVoters.isEmpty()) }
    var voters by remember { mutableStateOf(initialVoters) }

    LaunchedEffect(messageId) {
        coroutineScope {
            val metaDeferred = async(Dispatchers.IO) {
                runCatching { fetchUserMeta() }.getOrDefault(emptyMap())
            }
            val votersDeferred = async(Dispatchers.IO) {
                runCatching { fetch(messageId) }.getOrNull()
            }
            val metaByLogin = metaDeferred.await()
            val resp = votersDeferred.await()
            if (resp != null && resp.optJSONObject("data") != null) {
                voters = parseVoters(resp, metaByLogin)
                // Enrich the raw server JSON with avatar_url + presence
                // per voter BEFORE caching — so next open's rehydration
                // already has avatars baked in.
                runCatching {
                    persistJson(enrichVotersJson(resp, metaByLogin).toString())
                }
            }
        }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgElevated,
        dragHandle = { DialogDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                "Реакции",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(10.dp))

            if (loading) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
                }
            } else if (voters.isEmpty()) {
                Text(
                    "Никто ещё не отреагировал",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            } else {
                val ordered = voters.entries.sortedByDescending {
                    if (it.key == highlightEmoji) 1 else 0
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for ((emoji, list) in ordered) {
                        item(key = "label_$emoji") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp, bottom = 4.dp)
                            ) {
                                Text(emoji, fontSize = 18.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${list.size}",
                                    color = Accent,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .background(AccentSubtle, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        for (v in list) {
                            item(key = "${emoji}_${v.login}_${v.createdAt}") {
                                VoterRow(v)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class Voter(
    val login: String,
    val fullName: String,
    val avatarUrl: String,
    val presence: String,
    val createdAt: String,
)

/** Meta we look up per user before painting the voter list. */
private data class UserMeta(val avatarUrl: String, val presence: String)

private suspend fun fetchUserMeta(): Map<String, UserMeta> = withContext(Dispatchers.IO) {
    val resp = ApiClient.getUsers()
    val arr = resp.optJSONArray("data")
        ?: resp.optJSONArray("users")
        ?: resp.optJSONArray("results")
        ?: return@withContext emptyMap()
    val map = HashMap<String, UserMeta>(arr.length())
    for (i in 0 until arr.length()) {
        val u = arr.optJSONObject(i) ?: continue
        val login = u.optString("login", "").trim()
        if (login.isBlank()) continue
        val url = com.example.otlhelper.core.security.blobAwareUrl(u, "avatar_url")
            .ifBlank { com.example.otlhelper.core.security.blobAwareUrl(u, "user_avatar_url") }
        val presence = u.optString("presence_status", "").ifBlank { "offline" }
        map[login] = UserMeta(avatarUrl = url, presence = presence)
    }
    map
}

@Composable
private fun VoterRow(v: Voter) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            avatarUrl = v.avatarUrl,
            name = v.fullName.ifBlank { v.login },
            presenceStatus = v.presence,
            size = 32.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                v.fullName.ifBlank { v.login },
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
            Text(
                v.login,
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
        Text(
            formatCreatedAt(v.createdAt),
            color = TextTertiary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Produce a copy of the server `get_reactions` payload with each voter
 * record enriched with `user_avatar_url` + `user_presence_status` pulled
 * from the users-map. The enriched JSON is what we persist to the local
 * cache, so a future hydration paints avatars + presence on the first
 * frame without needing the users-map again.
 */
private fun enrichVotersJson(
    resp: JSONObject,
    metaByLogin: Map<String, UserMeta>,
): JSONObject {
    val data = resp.optJSONObject("data") ?: return resp
    val votersJson = data.optJSONObject("voters") ?: return resp
    val keys = votersJson.keys()
    while (keys.hasNext()) {
        val emoji = keys.next()
        val arr = votersJson.optJSONArray(emoji) ?: continue
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val login = v.optString("user_login", "")
            val meta = metaByLogin[login] ?: continue
            if (v.optString("user_avatar_url", "").isBlank() && meta.avatarUrl.isNotBlank()) {
                v.put("user_avatar_url", meta.avatarUrl)
            }
            if (v.optString("user_presence_status", "").isBlank()) {
                v.put("user_presence_status", meta.presence)
            }
        }
    }
    return resp
}

private fun parseVoters(
    resp: JSONObject?,
    metaByLogin: Map<String, UserMeta>,
): Map<String, List<Voter>> {
    val data = resp?.optJSONObject("data") ?: return emptyMap()
    val votersJson = data.optJSONObject("voters") ?: return emptyMap()
    val out = linkedMapOf<String, MutableList<Voter>>()
    val keys = votersJson.keys()
    while (keys.hasNext()) {
        val emoji = keys.next()
        val arr = votersJson.optJSONArray(emoji) ?: continue
        val list = out.getOrPut(emoji) { mutableListOf() }
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val login = v.optString("user_login", "")
            val meta = metaByLogin[login]
            // Prefer any avatar_url the reaction response happens to carry;
            // otherwise fall back to the users-map we fetched on open.
            val avatarUrl = com.example.otlhelper.core.security.blobAwareUrl(v, "user_avatar_url")
                .ifBlank { com.example.otlhelper.core.security.blobAwareUrl(v, "avatar_url") }
                .ifBlank { meta?.avatarUrl.orEmpty() }
            val presence = v.optString("user_presence_status", "")
                .ifBlank { meta?.presence ?: "offline" }
            list.add(
                Voter(
                    login = login,
                    fullName = v.optString("full_name", ""),
                    avatarUrl = avatarUrl,
                    presence = presence,
                    createdAt = v.optString("created_at", ""),
                )
            )
        }
    }
    return out
}

private fun formatCreatedAt(raw: String): String {
    if (raw.isBlank()) return ""
    return try {
        val normalized = if (raw.contains('T')) {
            if (raw.endsWith("Z") || raw.contains('+')) raw else "${raw}Z"
        } else "${raw.replace(' ', 'T')}Z"
        val zdt = ZonedDateTime.parse(normalized)
        val yek = zdt.withZoneSameInstant(TimeZone.getTimeZone("Asia/Yekaterinburg").toZoneId())
        yek.format(DateTimeFormatter.ofPattern("dd.MM h:mm a", java.util.Locale.ENGLISH))
    } catch (_: Exception) {
        raw.take(16)
    }
}
