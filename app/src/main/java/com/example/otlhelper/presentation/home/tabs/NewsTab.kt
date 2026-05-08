package com.example.otlhelper.presentation.home.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.example.otlhelper.core.ui.animations.AppMotion
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.*
import com.example.otlhelper.core.theme.*
import com.example.otlhelper.core.ui.NewsCard
import com.example.otlhelper.core.ui.PinnedPills
import com.example.otlhelper.core.ui.PollCard
import com.example.otlhelper.core.ui.components.NewsListSkeleton
import com.example.otlhelper.domain.model.FeedDisplayType
import com.example.otlhelper.domain.model.Role
import com.example.otlhelper.domain.policy.FeedViewPolicy
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Лента новостей+опросов.
 *
 * UI не принимает решений о ролях — все флаги приходят через [FeedViewPolicy.toView],
 * который получает [role] один раз и строит готовый view per-item.
 *
 * [pinnedItems] — закреплённые сверху (до [com.example.otlhelper.domain.limits.Limits.MAX_PINNED]).
 * Рендерит горизонтальные плашки сверху; тап открывает [PinnedItemSheet] (§3.5).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NewsTab(
    items: List<JSONObject>,
    isLoading: Boolean,
    fromCache: Boolean,
    pinnedItems: List<JSONObject>,
    myLogin: String,
    role: Role,
    listState: LazyListState = rememberLazyListState(),
    onPollVoteConfirm: (JSONObject, List<Long>) -> Unit,
    onPollLongClick: (JSONObject) -> Unit,
    onPollOverflowClick: (JSONObject) -> Unit,
    onNewsOverflowClick: (JSONObject) -> Unit,
    onUnpinItem: (JSONObject) -> Unit,
    /** Phase 12a — длинный тап на карточке: Edit/Delete sheet (для автора или admin). null = long-press disabled. */
    onItemLongClick: ((JSONObject) -> Unit)? = null,
    /** Phase 12b — toggle реакции (messageId, emoji, alreadyReacted). */
    onReactionToggle: ((JSONObject, String, Boolean) -> Unit)? = null,
    /** Dev-only: long-press on a reaction chip → show voter list. */
    onReactionLongPress: ((JSONObject, String) -> Unit)? = null,
    onMarkRead: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (pinnedItems.isNotEmpty()) {
                PinnedPills(
                    items = pinnedItems,
                    role = role,
                    myLogin = myLogin,
                    // Center tap → scroll the news list to this item
                    onPillClick = { target ->
                        val targetId = target.optLong("id", -1L)
                        val targetLocal = target.optString("local_item_id", "")
                        val idx = items.indexOfFirst {
                            (targetId > 0 && it.optLong("id", -2L) == targetId) ||
                                (targetLocal.isNotBlank() && it.optString("local_item_id", "") == targetLocal)
                        }
                        if (idx >= 0) {
                            scope.launch {
                                listState.animateScrollToItem(idx)
                            }
                        }
                    },
                    onPollVoteConfirm = onPollVoteConfirm,
                    onOpenStats = { item ->
                        val view = FeedViewPolicy.toView(item, role)
                        if (view.displayType == FeedDisplayType.POLL) onPollOverflowClick(item)
                        else onNewsOverflowClick(item)
                    }
                )
            }

            // Data-first rule, cache-first rendering:
            //   — Have items (cache or fresh) → always show the list.
            //   — No items but a fetch is in flight AND we've never loaded
            //     before → skeleton (true cold start only).
            //   — No items, fetch settled → empty-state.
            // rememberSaveable survives tab-switch so returning to News
            // with a cached list never flashes a skeleton on top of it.
            var hasLoadedOnce by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(isLoading, items) {
                if (!isLoading) hasLoadedOnce = true
            }

            if (items.isEmpty() && isLoading && !hasLoadedOnce) {
                NewsListSkeleton(modifier = Modifier.fillMaxSize())
            } else if (items.isEmpty() && hasLoadedOnce) {
                EmptyFeedState()
            } else if (items.isNotEmpty()) {
                // §TZ-2.3.7 — scroll-tick haptic. Тикает только при реальном
                // изменении firstVisibleItemIndex во время scroll'а. Даёт
                // premium-feel как в iOS-контейнерах.
                val newsFeedback = com.example.otlhelper.core.feedback.LocalFeedback.current
                val newsHost = androidx.compose.ui.platform.LocalView.current
                androidx.compose.runtime.LaunchedEffect(listState) {
                    androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
                        .collect { _ ->
                            if (listState.isScrollInProgress) newsFeedback?.tick(newsHost)
                        }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items, key = { com.example.otlhelper.core.ui.stableFeedKey(it) }) { item ->
                        // Всё «что показывать» сведено в одной политике:
                        // display_type, заголовок, флаги по роли — единым объектом.
                        val view = FeedViewPolicy.toView(item, role)

                        // Mark as read the first time this news/poll card actually
                        // becomes visible on screen (not when the tab loads a
                        // whole list off-screen).
                        val itemId = item.optLong("id", 0L)
                        val isUnread = item.optInt("is_read", 1) == 0
                        val sender = item.optString("sender_login", "")
                        if (isUnread && itemId > 0L && sender != myLogin) {
                            LaunchedEffect(itemId) { onMarkRead(itemId) }
                        }

                        val itemMod = Modifier
                            .fillMaxWidth()
                            // §TZ-2.3.5++ — fadeIn=null, так же как в AdminInbox.
                            // При переходе skeleton→LazyColumn все items считались
                            // «новыми» → каждый fadeIn индивидуально → визуально
                            // прогрузочка. Теперь список появляется целиком.
                            // Placement-spring остаётся для bump нового поста.
                            .animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = null,
                                placementSpec = AppMotion.SpringStandardOffset,
                            )
                        // @Immutable-обёртка для стабильной recomposition'a.
                        // Пересоздаётся при изменении ссылки на JSONObject,
                        // но equals по content-hash → Compose пропускает
                        // recompose когда контент не менялся.
                        val stableItem = remember(item) { com.example.otlhelper.core.ui.StableJsonItem(item) }
                        when (view.displayType) {
                            FeedDisplayType.POLL -> PollCard(
                                item = stableItem,
                                view = view,
                                myLogin = myLogin,
                                onVoteConfirm = { ids -> onPollVoteConfirm(item, ids) },
                                onLongClick = {
                                    if (onItemLongClick != null) onItemLongClick(item)
                                    else onPollLongClick(item)
                                },
                                onOverflowClick = { onPollOverflowClick(item) },
                                onReactionToggle = onReactionToggle?.let { cb ->
                                    { emoji, already -> cb(item, emoji, already) }
                                },
                                onReactionLongPress = onReactionLongPress?.let { cb ->
                                    { emoji -> cb(item, emoji) }
                                },
                                modifier = itemMod
                            )
                            FeedDisplayType.NEWS,
                            FeedDisplayType.UNKNOWN -> NewsCard(
                                item = stableItem,
                                view = view,
                                myLogin = myLogin,
                                onOverflowClick = { onNewsOverflowClick(item) },
                                onLongClick = onItemLongClick?.let { cb -> { cb(item) } },
                                onReactionToggle = onReactionToggle?.let { cb ->
                                    { emoji, already -> cb(item, emoji, already) }
                                },
                                onReactionLongPress = onReactionLongPress?.let { cb ->
                                    { emoji -> cb(item, emoji) }
                                },
                                modifier = itemMod
                            )
                        }
                    }
                }
            }
        }

        // Cache indicator
        if (fromCache && items.isNotEmpty()) {
            Text(
                "Оффлайн",
                color = TextTertiary,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            )
        }
    }

    // Pin expansion is now inline inside PinnedPills — modal sheet removed.
}

@Composable
private fun EmptyFeedState() {
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset("otter_sad.json"))
    val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LottieAnimation(composition = composition, progress = { progress }, modifier = Modifier.size(120.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                "Пока нет новостей",
                color = TextSecondary,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Новости появятся здесь",
                color = TextTertiary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
