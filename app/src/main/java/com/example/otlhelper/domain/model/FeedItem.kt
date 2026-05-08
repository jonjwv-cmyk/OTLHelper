package com.example.otlhelper.domain.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single item in the news/chat feed, wrapping the raw JSON payload from the API.
 * The JSON structure is preserved as-is to match the existing server contract.
 */
data class FeedItem(
    val id: String,
    val kind: FeedKind,
    val sortKey: String,
    val payload: JSONObject
) {
    enum class FeedKind {
        NEWS, POLL, USER_MESSAGE, ADMIN_MESSAGE, ADMIN_INBOX, UNKNOWN;

        companion object {
            fun from(raw: String): FeedKind = when (raw.lowercase()) {
                "news" -> NEWS
                "poll", "news_poll" -> POLL
                "message", "user_message" -> USER_MESSAGE
                "admin_message" -> ADMIN_MESSAGE
                "admin_inbox" -> ADMIN_INBOX
                else -> UNKNOWN
            }
        }
    }
}

/** Convert a list of JSONObjects (as returned by the DB / API) into FeedItems. */
fun List<JSONObject>.toFeedItems(): List<FeedItem> = mapIndexed { index, json ->
    val rawKind = json.optString("kind", json.optString("type", ""))
    val id = json.optString("local_item_id")
        .ifBlank { json.optString("id") }
        .ifBlank { json.optString("message_id") }
        .ifBlank { json.optString("poll_id") }
        .ifBlank { "$rawKind|$index" }
    val sortKey = json.optString("created_at", "")
    FeedItem(id = id, kind = FeedItem.FeedKind.from(rawKind), sortKey = sortKey, payload = json)
}

fun JSONArray.toFeedItems(): List<FeedItem> = buildList {
    for (i in 0 until length()) {
        optJSONObject(i)?.let { add(it) }
    }
}.toFeedItems()
