package com.example.otlhelper.presentation.home.internal

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.example.otlhelper.presentation.home.HomeTab
import com.example.otlhelper.presentation.home.HomeUiState
import com.example.otlhelper.presentation.home.HomeViewModel
import kotlinx.coroutines.delay

/**
 * §TZ-CLEANUP-2026-04-26 — оркестрирует scroll-position persistence
 * + restoration на смене tab'а + auto-scroll-to-bottom для новых
 * сообщений в admin-conv.
 *
 * Раньше HomeScreen.kt держал 4 inline LaunchedEffect-блока — теперь
 * они собраны здесь и HomeScreen вызывает один composable.
 */
@Composable
internal fun HomeScrollRestoration(
    state: HomeUiState,
    viewModel: HomeViewModel,
    newsListState: LazyListState,
    monitoringListState: LazyListState,
    conversationListState: LazyListState,
) {
    // ── Restore scroll position when the relevant feed becomes ready ──────────
    // delay(50) даёт layout-engine завершить первый measure (AsyncImage в
    // карточках дозамеряется асинхронно). Без этого scrollToItem срабатывает
    // на ещё-не-померянных элементах — видимый прыжок. coerceIn защищает от
    // race когда feedItems подменяется между network/push и моментом scroll.
    LaunchedEffect(state.activeTab, state.feedItems.isNotEmpty()) {
        if (state.feedItems.isEmpty()) return@LaunchedEffect
        delay(50)
        val size = state.feedItems.size
        if (size == 0) return@LaunchedEffect
        when (state.activeTab) {
            HomeTab.NEWS -> viewModel.loadScrollPosition(HomeTab.NEWS)?.let { (idx, offset) ->
                val safeIdx = idx.coerceIn(0, size - 1)
                runCatching { newsListState.scrollToItem(safeIdx, offset) }
            }
            HomeTab.MONITORING -> viewModel.loadScrollPosition(HomeTab.MONITORING)?.let { (idx, offset) ->
                val safeIdx = idx.coerceIn(0, size - 1)
                runCatching { monitoringListState.scrollToItem(safeIdx, offset) }
            }
            else -> {}
        }
    }

    // ── Persist scroll position whenever the user pauses scrolling ────────────
    LaunchedEffect(newsListState) {
        snapshotFlow {
            newsListState.firstVisibleItemIndex to newsListState.firstVisibleItemScrollOffset
        }.collect { (idx, offset) ->
            if (state.activeTab == HomeTab.NEWS) {
                viewModel.saveScrollPosition(HomeTab.NEWS, idx, offset)
            }
        }
    }
    LaunchedEffect(monitoringListState) {
        snapshotFlow {
            monitoringListState.firstVisibleItemIndex to monitoringListState.firstVisibleItemScrollOffset
        }.collect { (idx, offset) ->
            if (state.activeTab == HomeTab.MONITORING) {
                viewModel.saveScrollPosition(HomeTab.MONITORING, idx, offset)
            }
        }
    }

    // ── Scroll to bottom when new items arrive (admin conv only) ──────────────
    LaunchedEffect(state.feedItems.size) {
        if (state.feedItems.isNotEmpty() &&
            state.activeTab == HomeTab.MONITORING &&
            state.adminConversationOpen
        ) {
            conversationListState.scrollToBottom()
        }
    }
}
