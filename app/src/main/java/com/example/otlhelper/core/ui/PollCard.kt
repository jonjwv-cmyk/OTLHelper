package com.example.otlhelper.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.theme.PollOptionNormal
import com.example.otlhelper.core.theme.PollOptionSelected
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.theme.TextTertiary
import com.example.otlhelper.domain.model.FeedItemView
import org.json.JSONObject

/**
 * Poll card. Role-driven visibility via [view] (§4.2 passport v3).
 *
 * Behaviour per §3.4:
 *   - `view.showAuthor` → author+date always visible.
 *   - `view.canSeeVoteCounts=false` → vote counts hidden; if the user has
 *     voted, a "Вы выбрали: X" summary renders below the options instead.
 *   - `view.canOpenMenu=false` → overflow ⋮ hidden.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PollCard(
    item: StableJsonItem,
    view: FeedItemView,
    myLogin: String,
    onVoteConfirm: (List<Long>) -> Unit,
    onLongClick: () -> Unit,
    onOverflowClick: () -> Unit,
    onReactionToggle: ((emoji: String, alreadyReacted: Boolean) -> Unit)? = null,
    onReactionLongPress: ((emoji: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val raw = item.raw
    val poll = raw.optJSONObject("poll") ?: raw
    val description = poll.optString("description", poll.optString("text", raw.optString("text", "")))
    val optionsArray = poll.optJSONArray("options")
    val selectionMode = poll.optString("selection_mode", "single")
    val allowRevoting = poll.optBoolean("allow_revoting", false)
    val senderAvatarUrl = com.example.otlhelper.core.security.blobAwareUrl(raw, "sender_avatar_url")
    val senderPresence = raw.optString("sender_presence_status", "offline")
    val isPending = raw.optBoolean("is_pending", false)

    // remember ключ по content-hash обёртки — дешевле чем raw.toString()
    val myVotedIds = remember(item) {
        val myVotes = mutableSetOf<Long>()
        optionsArray?.let { arr ->
            for (i in 0 until arr.length()) {
                val opt = arr.optJSONObject(i) ?: continue
                if (opt.optBoolean("is_selected", false)) {
                    myVotes.add(opt.optLong("id", -1))
                }
            }
        }
        myVotes
    }

    val hasVoted = myVotedIds.isNotEmpty()
    val selectedIds = remember(myVotedIds) { mutableStateListOf<Long>().also { it.addAll(myVotedIds) } }

    val borderColor = if (view.isPinned) Accent else BorderDivider

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(BgCard, CardShape)
            .border(0.5.dp, borderColor, CardShape)
            .combinedClickable(onLongClick = onLongClick, onClick = {})
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(view.headerLabel, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (view.isEdited) {
                Spacer(Modifier.width(6.dp))
                Text("(изменено)", color = TextTertiary, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (view.showAuthor) {
                UserAvatar(
                    avatarUrl = senderAvatarUrl,
                    name = view.authorLabel,
                    presenceStatus = senderPresence,
                    size = 32.dp
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = view.authorLabel,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = formatDate(view.createdAt),
                        color = TextTertiary,
                        fontSize = 11.sp
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (view.canOpenMenu && !isPending) {
                // §TZ-2.3.24 — tap haptic на три точки.
                val overflowFeedback = com.example.otlhelper.core.feedback.LocalFeedback.current
                val overflowView = androidx.compose.ui.platform.LocalView.current
                IconButton(
                    onClick = {
                        overflowFeedback?.tap(overflowView)
                        onOverflowClick()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.MoreVert, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(description, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp)

        Spacer(Modifier.height(12.dp))

        val pollAttJson = (poll.optJSONArray("attachments") ?: raw.optJSONArray("attachments"))?.toString() ?: ""
        if (pollAttJson.isNotBlank() && pollAttJson != "null" && pollAttJson != "[]") {
            AttachmentsView(attachmentsJson = pollAttJson)
            Spacer(Modifier.height(12.dp))
        }

        if (optionsArray != null) {
            for (i in 0 until optionsArray.length()) {
                val opt = optionsArray.optJSONObject(i) ?: continue
                val optId = opt.optLong("id", -1)
                val optText = opt.optString("option_text", opt.optString("text", ""))
                val voteCount = opt.optInt("votes_count", opt.optInt("vote_count", 0))
                val isSelected = optId in selectedIds
                val canVote = !hasVoted || allowRevoting

                PollOptionRow(
                    text = optText,
                    voteCount = if (view.canSeeVoteCounts) voteCount else 0,
                    isSelected = isSelected,
                    canVote = canVote,
                    onClick = {
                        if (!canVote) return@PollOptionRow
                        if (selectionMode == "single") {
                            selectedIds.clear()
                            selectedIds.add(optId)
                        } else {
                            if (isSelected) selectedIds.remove(optId) else selectedIds.add(optId)
                        }
                    }
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        if (!view.canSeeVoteCounts && hasVoted && optionsArray != null) {
            val mySelectedTexts = buildList {
                for (i in 0 until optionsArray.length()) {
                    val opt = optionsArray.optJSONObject(i) ?: continue
                    if (opt.optLong("id", -1) in myVotedIds) {
                        add(opt.optString("option_text", opt.optString("text", "")))
                    }
                }
            }
            if (mySelectedTexts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Вы выбрали: ${mySelectedTexts.joinToString(", ")}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (onReactionToggle != null) {
            ReactionsBar(
                aggregate = view.reactionsAggregate,
                myReactions = view.myReactions,
                onToggle = { emoji -> onReactionToggle(emoji, emoji in view.myReactions) },
                onChipLongPress = onReactionLongPress,
            )
        }

        val canSubmit = selectedIds.isNotEmpty() && (selectedIds.toSet() != myVotedIds || !hasVoted)
        if (canSubmit) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onVoteConfirm(selectedIds.toList()) },
                colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Подтвердить выбор", fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PollOptionRow(
    text: String,
    voteCount: Int,
    isSelected: Boolean,
    canVote: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isSelected) PollOptionSelected else PollOptionNormal
    val border = if (isSelected) Accent else BorderDivider

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg, RoundedCornerShape(10.dp))
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable(enabled = canVote, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (voteCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text("$voteCount", color = TextSecondary, fontSize = 13.sp)
        }
    }
}
