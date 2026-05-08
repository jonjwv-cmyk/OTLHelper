package com.example.otlhelper.presentation.home.internal

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Shared feed helpers used by ChatController and FeedController.
//
// These were inlined inside the old monolithic HomeViewModel; they are now
// extracted so both controllers can reuse identical merge semantics.

internal fun JSONArray.toJsonList(): List<JSONObject> = buildList {
    for (i in 0 until length()) optJSONObject(i)?.let { add(it) }
}

/**
 * Merge a fresh server list with optimistic `is_pending=true` items from the
 * previous feed that the server has not yet committed.
 *
 * Match strategy: same sender_login + same text. Timestamps are unreliable
 * (client and server clocks/timezones drift). If a pending row has a matching
 * row on the server, the server row is authoritative and the optimistic one
 * is dropped (no duplicate). Otherwise the pending is preserved so a just-sent
 * message does not vanish between send-success and the next auto-refresh.
 */
internal fun mergeWithPending(
    serverList: List<JSONObject>,
    previousFeed: List<JSONObject>,
): List<JSONObject> {
    val unconfirmedPendings = previousFeed.filter { pending ->
        if (!pending.optBoolean("is_pending", false)) return@filter false
        if (pending.optString("local_item_id", "").isEmpty()) return@filter false
        val sender = pending.optString("sender_login", "")
        val text = pending.optString("text", "")
        val matchedOnServer = serverList.any { server ->
            server.optString("sender_login", "") == sender &&
                server.optString("text", "") == text
        }
        !matchedOnServer
    }
    return if (unconfirmedPendings.isEmpty()) serverList
    else serverList + unconfirmedPendings
}

/**
 * Sort strictly chronologically (oldest → newest). Pinned items are NOT
 * pulled to the top — they keep their timeline position; the pinned-pills
 * panel surfaces them separately so chronology stays intact.
 */
internal fun sortFeedItems(items: List<JSONObject>): List<JSONObject> =
    items.sortedBy { it.optString("created_at", "") }

/**
 * ISO-8601 UTC timestamp. Server stores created_at in UTC; optimistic rows
 * must match that convention or timestamp-based matching / sorting breaks
 * for any user outside UTC (e.g. UTC+5 device produced "22:30Z" for a local
 * 22:30 — the pending appeared newer than any server row for 5h and never
 * fell out of the merge filter, causing duplicate bubbles).
 */
internal fun nowUtcIso(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
