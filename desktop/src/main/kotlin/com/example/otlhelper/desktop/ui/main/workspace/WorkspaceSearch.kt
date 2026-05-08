package com.example.otlhelper.desktop.ui.main.workspace

import com.example.otlhelper.desktop.data.chat.InboxRepository
import com.example.otlhelper.desktop.data.feed.NewsRepository
import com.example.otlhelper.desktop.ui.palette.matchesQuery
import com.example.otlhelper.desktop.ui.palette.newsHaystack

/**
 * §TZ-DESKTOP 0.3.3 — client-side фильтрация inbox/news по [searchQuery].
 *
 * **Инвариант**: сервер ничего не знает про этот фильтр (никаких запросов).
 * Search применяется как substring-AND-match (см. [matchesQuery]) к
 * `senderName + login + lastText` (inbox) и к полному haystack новости
 * (см. [newsHaystack]).
 */
internal fun filterInbox(
    state: InboxRepository.State,
    searchQuery: String,
): InboxRepository.State {
    if (searchQuery.isBlank()) return state
    return state.copy(
        rows = state.rows.filter { row ->
            matchesQuery(
                haystack = "${row.senderName} ${row.senderLogin} ${row.lastText}",
                query = searchQuery,
            )
        },
    )
}

internal fun filterNews(
    state: NewsRepository.State,
    searchQuery: String,
): NewsRepository.State {
    if (searchQuery.isBlank()) return state
    return state.copy(
        items = state.items.filter { item ->
            matchesQuery(haystack = newsHaystack(item), query = searchQuery)
        },
        pinnedItems = state.pinnedItems.filter { item ->
            matchesQuery(haystack = newsHaystack(item), query = searchQuery)
        },
    )
}
