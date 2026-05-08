package com.example.otlhelper.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.PresencePaused
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.core.theme.UnreadGreen
import org.json.JSONObject

/**
 * Flat, @Immutable view-model for one inbox row. Compose can skip
 * recomposition of [AdminInboxItem] cleanly when the same values flow
 * through on a refresh — which `JSONObject` never lets it do because
 * `JSONObject` has no value-equality.
 */
@Immutable
data class AdminInboxRow(
    val senderLogin: String,
    val senderName: String,
    val senderAvatarUrl: String,
    val senderPresence: String,
    val lastText: String,
    val unreadCount: Int,
    val createdAt: String,
    /**
     * Latest message id seen from this contact. Used by the client-side
     * stable sort in AdminInboxTab: a contact only rises to the top when
     * this value INCREASES (a genuinely new message arrived). Equal or
     * lower value keeps the row at its current position, even if the
     * server happens to reshuffle its response.
     */
    val lastMessageId: Long,
    /** §TZ-2.3.38 — роль собеседника (user/client/…). Для админа/dev мы
     * визуально группируем: users сверху, clients ниже разделительной линии. */
    val senderRole: String,
)

/** Project a server JSONObject row into the stable view-model. */
fun JSONObject.toAdminInboxRow(): AdminInboxRow {
    val login = optString("sender_login", "")
    val text = optString("text", "")
    return AdminInboxRow(
        senderLogin = login,
        senderName = optString("sender_name", login.ifBlank { "?" }),
        senderAvatarUrl = com.example.otlhelper.core.security.blobAwareUrl(this, "sender_avatar_url"),
        senderPresence = optString("sender_presence_status", "offline"),
        lastText = if (text.length > 60) text.take(57) + "…" else text,
        unreadCount = optInt("unread_count", 0),
        createdAt = optString("created_at", ""),
        lastMessageId = optLong("id", 0L),
        senderRole = optString("sender_role", ""),
    )
}

/**
 * Inbox row used in AdminInboxTab. Avatar + name + last message preview,
 * plus a pill unread badge that scale-in/fade-outs as counts change
 * (admin reads a thread → optimistic decrement).
 *
 * Takes a pre-projected [AdminInboxRow] rather than the raw JSONObject so
 * Compose can short-circuit recomposition when the underlying row did not
 * actually change. This is what keeps the list stable on tab re-enter and
 * the 5-second background refresh — same rows in, no visible redraw.
 */
@Composable
fun AdminInboxItem(
    row: AdminInboxRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(BgCard)
            .border(0.5.dp, BorderDivider, CardShape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            avatarUrl = row.senderAvatarUrl,
            name = row.senderName,
            presenceStatus = row.senderPresence,
            size = 44.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.senderName, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            if (row.lastText.isNotBlank()) {
                Text(row.lastText, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                val (presenceLabel, presenceColor) = when (row.senderPresence) {
                    "online" -> "онлайн" to UnreadGreen
                    "paused" -> "был(а) недавно" to PresencePaused
                    else -> "оффлайн" to TextTertiary
                }
                Text(presenceLabel, color = presenceColor, fontSize = 12.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatDate(row.createdAt), color = TextTertiary, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            AnimatedVisibility(
                visible = row.unreadCount > 0,
                enter = scaleIn(
                    initialScale = 0.6f,
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = 380f)
                ) + fadeIn(),
                exit = scaleOut(targetScale = 0.7f) + fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                        .clip(RoundedCornerShape(50))
                        .background(UnreadGreen)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (row.unreadCount > 99) "99+" else row.unreadCount.toString(),
                        color = Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 14.sp,
                    )
                }
            }
        }
    }
}
