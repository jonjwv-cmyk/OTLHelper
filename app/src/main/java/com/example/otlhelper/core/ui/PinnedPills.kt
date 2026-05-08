package com.example.otlhelper.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.otlhelper.core.theme.Accent
import com.example.otlhelper.core.theme.AccentSubtle
import com.example.otlhelper.core.theme.BgCard
import com.example.otlhelper.core.theme.BorderDivider
import com.example.otlhelper.core.ui.components.ThinDivider
import com.example.otlhelper.core.theme.TextPrimary
import com.example.otlhelper.core.theme.TextSecondary
import com.example.otlhelper.core.ui.animations.AppMotion
import com.example.otlhelper.domain.model.FeedItemView
import com.example.otlhelper.domain.model.Role
import com.example.otlhelper.domain.policy.FeedViewPolicy
import org.json.JSONObject

/**
 * Vertical stack of pinned pills at the top of the NEWS feed.
 *
 * Each pill:
 *  · LEFT area (text + icon) — tap scrolls the news feed to the pinned item
 *  · RIGHT chevron — toggles inline expansion of full content within the pill
 *
 * This replaces the previous horizontal strip + modal sheet. The inline
 * expansion keeps the user's scroll context intact.
 */
@Composable
fun PinnedPills(
    items: List<JSONObject>,
    role: Role,
    onPillClick: (JSONObject) -> Unit,
    myLogin: String = "",
    onPollVoteConfirm: ((JSONObject, List<Long>) -> Unit)? = null,
    onOpenStats: ((JSONObject) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BgCard)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (item in items) {
            val view = FeedViewPolicy.toView(item, role)
            PinnedPillRow(
                item = item,
                view = view,
                myLogin = myLogin,
                onTapCenter = { onPillClick(item) },
                onPollVoteConfirm = onPollVoteConfirm,
                onOpenStats = onOpenStats
            )
        }
    }

    ThinDivider()
}

@Composable
private fun PinnedPillRow(
    item: JSONObject,
    view: FeedItemView,
    myLogin: String,
    onTapCenter: () -> Unit,
    onPollVoteConfirm: ((JSONObject, List<Long>) -> Unit)?,
    onOpenStats: ((JSONObject) -> Unit)?
) {
    var expanded by remember(view.createdAt) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val previewText = item.optString("text", "").ifBlank {
        item.optJSONObject("poll")?.optString("description", "") ?: ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AccentSubtle, shape)
            .border(0.5.dp, Accent.copy(alpha = 0.28f), shape)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left (tappable) — scrolls to the item in the feed
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onTapCenter)
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
            ) {
                Text(
                    text = view.headerLabel,
                    color = Accent,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (previewText.isNotBlank()) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = previewText,
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Right chevron (tappable) — inline expand/collapse.
            // §TZ-2.3.24 — tap haptic (как остальные expand/collapse контейнеры).
            val pillFeedback = com.example.otlhelper.core.feedback.LocalFeedback.current
            val pillView = androidx.compose.ui.platform.LocalView.current
            Box(
                modifier = Modifier
                    .clickable {
                        pillFeedback?.tap(pillView)
                        expanded = !expanded
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Свернуть" else "Раскрыть",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = AppMotion.SpringStandardSize) +
                fadeIn(animationSpec = AppMotion.SpringStandard),
            exit = shrinkVertically(animationSpec = AppMotion.SpringStandardSize) +
                fadeOut(animationSpec = AppMotion.SpringStandard)
        ) {
            Column(modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .height(0.5.dp)
                        .background(Accent.copy(alpha = 0.18f))
                )
                Spacer(Modifier.height(6.dp))
                val stableItem = remember(item) { StableJsonItem(item) }
                when (view.displayType) {
                    com.example.otlhelper.domain.model.FeedDisplayType.POLL ->
                        PollCard(
                            item = stableItem,
                            view = view,
                            myLogin = myLogin,
                            onVoteConfirm = { ids -> onPollVoteConfirm?.invoke(item, ids) },
                            onLongClick = {},
                            onOverflowClick = { onOpenStats?.invoke(item) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    com.example.otlhelper.domain.model.FeedDisplayType.NEWS,
                    com.example.otlhelper.domain.model.FeedDisplayType.UNKNOWN ->
                        NewsCard(
                            item = stableItem,
                            view = view,
                            myLogin = myLogin,
                            onOverflowClick = { onOpenStats?.invoke(item) },
                            // §TZ-2.3.38 — внутри раскрытой закреплённой пилюли
                            // pinned-badge дублируется самоочевидным expand'ом,
                            // а под клавиатурой ещё и обрезается. Прячем.
                            hidePinnedBadge = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                }
            }
        }
    }
}
