package com.example.otlhelper.data.repository

import org.json.JSONArray
import org.json.JSONObject

interface FeedRepository {
    suspend fun getCachedFeed(scope: String): JSONArray
    suspend fun hasCachedFeed(scope: String): Boolean
    suspend fun cacheFeed(scope: String, items: JSONArray)

    suspend fun enqueuePendingAction(
        actionType: String,
        payload: JSONObject,
        entityKey: String = ""
    ): Long

    suspend fun getPendingActions(): List<JSONObject>
    suspend fun markPendingDone(id: Long)
    suspend fun markPendingRetry(id: Long)

    /** Convenience enqueue helpers */
    suspend fun enqueuePendingVote(pollId: Long, optionIds: List<Long>, localItemId: String = ""): Long
    suspend fun enqueuePendingNews(login: String, text: String, localItemId: String = ""): Long
    suspend fun enqueuePendingMessage(login: String, text: String, receiverLogin: String? = null, localItemId: String = ""): Long
    suspend fun enqueuePendingCreatePoll(
        login: String,
        title: String,
        description: String,
        selectionMode: String,
        allowRevoting: Boolean,
        includeAdmins: Boolean,
        options: List<String>,
        localItemId: String = ""
    ): Long

    /** Queue a mark-as-read that fired while the device was offline. */
    suspend fun enqueuePendingMarkRead(messageId: Long): Long
}
