package com.example.otlhelper.presentation.home.internal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.otlhelper.presentation.home.HomeDialogsState
import com.example.otlhelper.presentation.home.HomeTab
import com.example.otlhelper.presentation.home.HomeUiState
import com.example.otlhelper.presentation.home.HomeViewModel
import com.example.otlhelper.presentation.home.tabs.AdminConversationTab
import com.example.otlhelper.presentation.home.tabs.AdminInboxTab
import com.example.otlhelper.presentation.home.tabs.NewsTab
import com.example.otlhelper.presentation.home.tabs.SearchTab
import com.example.otlhelper.presentation.home.tabs.UserChatTab

/**
 * §TZ-CLEANUP-2026-04-26 — extracted from HomeScreen.kt.
 *
 * Большой `when`-блок выбора активного tab'а:
 *  - SEARCH → SearchTab
 *  - NEWS → NewsTab
 *  - MONITORING → AdminInboxTab / AdminConversationTab / UserChatTab
 *    (по hasAdminAccess + adminConversationOpen)
 *
 * Все колбэки прокинуты — этот composable behaviour-preserving wrap'ер.
 */
@Composable
internal fun HomeTabHost(
    state: HomeUiState,
    viewModel: HomeViewModel,
    dialogs: HomeDialogsState,
    newsListState: LazyListState,
    monitoringListState: LazyListState,
    conversationListState: LazyListState,
    searchListState: LazyListState,
    searchPillExpanded: Boolean,
    onSearchPillToggle: () -> Unit,
    onSearchPillReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.activeTab == HomeTab.SEARCH -> SearchTab(
            query = state.searchQuery,
            onQueryChanged = {
                // Reset pill expanded state on any new query — prevents
                // "stale expanded pill" sticking from a previous warehouse result
                onSearchPillReset()
                viewModel.onSearchQueryChanged(it)
            },
            results = state.searchResults,
            isLoading = state.searchLoading,
            pinnedWarehouse = state.pinnedWarehouse,
            listState = searchListState,
            pillExpanded = searchPillExpanded,
            onPillToggle = onSearchPillToggle,
            searchMode = state.searchMode,
            isAdminOrDev = viewModel.hasAdminAccess(),
            modifier = modifier.fillMaxSize()
        )
        state.activeTab == HomeTab.NEWS -> NewsTab(
            items = state.feedItems,
            isLoading = state.feedLoading,
            fromCache = state.feedFromCache,
            pinnedItems = state.pinnedFeedItems,
            myLogin = viewModel.getLogin(),
            role = viewModel.role,
            listState = newsListState,
            onPollVoteConfirm = { item, ids -> dialogs.confirmVoteTarget = item to ids },
            onPollLongClick = { item -> dialogs.pollStatsTarget = item },
            onPollOverflowClick = { item -> dialogs.pollStatsTarget = item },
            onNewsOverflowClick = { item -> dialogs.newsReadersTarget = item },
            onUnpinItem = { item ->
                val id = item.optLong("id", 0L)
                if (id > 0L) viewModel.togglePin(id, pin = false) { _, _ -> }
            },
            // Phase 12a: long-press открывает Edit/Delete sheet, если есть права
            // (автор или admin). Иначе — null (long-press не реагирует для чужих).
            onItemLongClick = { item ->
                val senderLogin = item.optString("sender_login", "")
                val isAuthor = senderLogin == viewModel.getLogin()
                val canManage = isAuthor || viewModel.hasAdminAccess()
                if (canManage) dialogs.editDeleteTarget = item
            },
            onReactionToggle = { item, emoji, already ->
                val id = item.optLong("id", 0L)
                if (id > 0L) viewModel.toggleReaction(id, emoji, already)
            },
            onReactionLongPress = if (viewModel.isDeveloper()) { item, emoji ->
                val id = item.optLong("id", 0L)
                if (id > 0L) dialogs.reactionVotersTarget = id to emoji
            } else null,
            // §TZ-2.3.38 — для новостей/опросов используем markNewsRead
            // с безусловным декрементом бейджа (чинит залипание).
            onMarkRead = { viewModel.markNewsRead(it) },
            modifier = modifier.fillMaxSize()
        )
        state.activeTab == HomeTab.MONITORING -> {
            if (viewModel.hasAdminAccess()) {
                if (state.adminConversationOpen) {
                    AdminConversationTab(
                        items = state.feedItems,
                        isLoading = state.feedLoading,
                        myLogin = viewModel.getLogin(),
                        userName = state.selectedAdminUserName ?: state.selectedAdminUserLogin ?: "",
                        peerLogin = state.selectedAdminUserLogin.orEmpty(),
                        peerAvatarUrl = state.selectedAdminUserAvatarUrl.orEmpty(),
                        typingFromLogins = state.typingFromLogins,
                        listState = conversationListState,
                        onMarkRead = { viewModel.markMessageRead(it) },
                        onBack = { viewModel.closeAdminConversation() },
                        onReactionToggle = { item, emoji, already ->
                            val id = item.optLong("id", 0L)
                            if (id > 0L) viewModel.toggleReaction(id, emoji, already)
                        },
                        onMessageLongPress = { item, bounds -> dialogs.chatActionTarget = item to bounds },
                        modifier = modifier.fillMaxSize()
                    )
                } else {
                    AdminInboxTab(
                        items = state.feedItems,
                        isLoading = state.feedLoading,
                        myLogin = viewModel.getLogin(),
                        fromCache = state.feedFromCache,
                        listState = monitoringListState,
                        onContactClick = { login, name ->
                            viewModel.openAdminConversation(login, name)
                        },
                        modifier = modifier.fillMaxSize()
                    )
                }
            } else {
                UserChatTab(
                    items = state.feedItems,
                    isLoading = state.feedLoading,
                    myLogin = viewModel.getLogin(),
                    typingFromLogins = state.typingFromLogins,
                    listState = monitoringListState,
                    onReactionToggle = { item, emoji, already ->
                        val id = item.optLong("id", 0L)
                        if (id > 0L) viewModel.toggleReaction(id, emoji, already)
                    },
                    onMessageLongPress = { item, bounds -> dialogs.chatActionTarget = item to bounds },
                    onMarkRead = { viewModel.markMessageRead(it) },
                    modifier = modifier.fillMaxSize()
                )
            }
        }
    }
}
