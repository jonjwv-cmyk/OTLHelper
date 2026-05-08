package com.example.otlhelper.data.network.api

import com.example.otlhelper.shared.api.ApiActions
import com.example.otlhelper.shared.api.ApiFields
import org.json.JSONArray
import org.json.JSONObject

/**
 * §TZ-CLEANUP-2026-04-25 — feed/items zone.
 *
 * Самая большая зона ApiClient. Объединяет всё что относится к
 * **interactive items** ленты и чатов:
 *
 *   • messaging:  sendMessage, sendNews, getNews, getUserChat, getAdminChat,
 *                 getAdminMessages, getUnreadCounts, markMessageRead
 *   • edit/delete: editMessage, softDeleteMessage, undeleteMessage
 *   • reactions:  addReaction, removeReaction, getReactions
 *   • polls:      createNewsPoll, voteNewsPoll, getPollStats
 *   • pin:        pinMessage, unpinMessage
 *   • readers:    getNewsReaders
 *   • drafts:     saveDraft, loadDraft, listDrafts
 *   • schedule:   scheduleMessage, listScheduled, cancelScheduled
 *
 * Реализация — в [FeedCallsImpl].
 */
interface FeedCalls {
    // ── Messaging ──────────────────────────────────────────────────────

    fun sendMessage(
        text: String,
        receiverLogin: String? = null,
        attachments: JSONArray = JSONArray(),
        replyToId: Long = 0L,
        localItemId: String = "",
    ): JSONObject

    fun sendNews(text: String, attachments: JSONArray = JSONArray()): JSONObject

    fun getNews(limit: Int = 20): JSONObject

    fun getUserChat(limit: Int = 100): JSONObject

    fun getAdminChat(userLogin: String, limit: Int = 100): JSONObject

    fun getAdminMessages(limit: Int = 100): JSONObject

    fun getUnreadCounts(): JSONObject

    fun markMessageRead(id: Long): JSONObject

    // ── Edit / delete (Phase 12a, 24h окно) ────────────────────────────

    fun editMessage(id: Long, newText: String): JSONObject

    fun softDeleteMessage(id: Long): JSONObject

    fun undeleteMessage(id: Long): JSONObject

    // ── Reactions (Phase 12b) ──────────────────────────────────────────

    fun addReaction(messageId: Long, emoji: String): JSONObject

    fun removeReaction(messageId: Long, emoji: String): JSONObject

    fun getReactions(messageId: Long): JSONObject

    // ── Polls ──────────────────────────────────────────────────────────

    fun createNewsPoll(
        title: String,
        description: String,
        options: List<String>,
        selectionMode: String = ApiFields.SELECTION_MODE_SINGLE,
        allowRevoting: Boolean = false,
        includeAdmins: Boolean = false,
        messageId: Long? = null,
        attachments: JSONArray = JSONArray(),
    ): JSONObject

    fun voteNewsPoll(pollId: Long, optionIds: List<Long>): JSONObject

    fun getPollStats(pollId: Long): JSONObject

    // ── Pin / readers ─────────────────────────────────────────────────

    fun pinMessage(messageId: Long): JSONObject

    fun unpinMessage(messageId: Long): JSONObject

    fun getNewsReaders(messageId: Long): JSONObject

    // ── Drafts (Phase 12c) ────────────────────────────────────────────

    fun saveDraft(scope: String, text: String): JSONObject

    fun loadDraft(scope: String): JSONObject

    fun listDrafts(): JSONObject

    // ── Scheduled (Phase 12c) ─────────────────────────────────────────

    fun scheduleMessage(kind: String, payload: JSONObject, sendAt: String): JSONObject

    fun listScheduled(): JSONObject

    fun cancelScheduled(id: Long): JSONObject
}

internal class FeedCallsImpl(private val gateway: ApiGateway) : FeedCalls {

    // ── Messaging ──────────────────────────────────────────────────────

    override fun sendMessage(
        text: String,
        receiverLogin: String?,
        attachments: JSONArray,
        replyToId: Long,
        localItemId: String,
    ): JSONObject = gateway.request(ApiActions.SEND_MESSAGE) {
        put(ApiFields.TEXT, text)
        if (!receiverLogin.isNullOrBlank()) put(ApiFields.RECEIVER_LOGIN, receiverLogin)
        if (attachments.length() > 0) put(ApiFields.ATTACHMENTS, attachments)
        if (replyToId > 0L) put(ApiFields.REPLY_TO_ID, replyToId)
        // §TZ-2.3.11 — idempotency key. Серверный UNIQUE index
        // (sender_login, local_item_id) + handleSendMessage первая
        // проверка: при retry после network timeout сервер вернёт
        // existing id вместо создания дубля.
        if (localItemId.isNotBlank()) put(ApiFields.LOCAL_ITEM_ID, localItemId)
    }

    override fun sendNews(text: String, attachments: JSONArray): JSONObject =
        gateway.request(ApiActions.SEND_NEWS) {
            put(ApiFields.TEXT, text)
            if (attachments.length() > 0) put(ApiFields.ATTACHMENTS, attachments)
        }

    override fun getNews(limit: Int): JSONObject =
        gateway.request(ApiActions.GET_NEWS) { put(ApiFields.LIMIT, limit) }

    override fun getUserChat(limit: Int): JSONObject =
        gateway.request(ApiActions.GET_USER_CHAT) { put(ApiFields.LIMIT, limit) }

    override fun getAdminChat(userLogin: String, limit: Int): JSONObject =
        gateway.request(ApiActions.GET_ADMIN_CHAT) {
            put(ApiFields.USER_LOGIN, userLogin)
            put(ApiFields.LIMIT, limit)
        }

    override fun getAdminMessages(limit: Int): JSONObject =
        gateway.request(ApiActions.GET_ADMIN_MESSAGES) { put(ApiFields.LIMIT, limit) }

    override fun getUnreadCounts(): JSONObject =
        gateway.request(ApiActions.GET_UNREAD_COUNTS)

    override fun markMessageRead(id: Long): JSONObject =
        gateway.request(ApiActions.MARK_MESSAGE_READ) { put(ApiFields.ID, id) }

    // ── Edit / delete ──────────────────────────────────────────────────

    override fun editMessage(id: Long, newText: String): JSONObject =
        gateway.request(ApiActions.EDIT_MESSAGE) {
            put(ApiFields.ID, id)
            put(ApiFields.TEXT, newText)
        }

    override fun softDeleteMessage(id: Long): JSONObject =
        gateway.request(ApiActions.SOFT_DELETE_MESSAGE) { put(ApiFields.ID, id) }

    override fun undeleteMessage(id: Long): JSONObject =
        gateway.request(ApiActions.UNDELETE_MESSAGE) { put(ApiFields.ID, id) }

    // ── Reactions ──────────────────────────────────────────────────────

    override fun addReaction(messageId: Long, emoji: String): JSONObject =
        gateway.request(ApiActions.ADD_REACTION) {
            put(ApiFields.MESSAGE_ID, messageId)
            put(ApiFields.EMOJI, emoji)
        }

    override fun removeReaction(messageId: Long, emoji: String): JSONObject =
        gateway.request(ApiActions.REMOVE_REACTION) {
            put(ApiFields.MESSAGE_ID, messageId)
            put(ApiFields.EMOJI, emoji)
        }

    override fun getReactions(messageId: Long): JSONObject =
        gateway.request(ApiActions.GET_REACTIONS) { put(ApiFields.MESSAGE_ID, messageId) }

    // ── Polls ──────────────────────────────────────────────────────────

    override fun createNewsPoll(
        title: String,
        description: String,
        options: List<String>,
        selectionMode: String,
        allowRevoting: Boolean,
        includeAdmins: Boolean,
        messageId: Long?,
        attachments: JSONArray,
    ): JSONObject = gateway.request(ApiActions.CREATE_NEWS_POLL) {
        if (messageId != null && messageId > 0L) put(ApiFields.MESSAGE_ID, messageId)
        put(ApiFields.TITLE, title)
        put(ApiFields.TEXT, description)
        put(ApiFields.DESCRIPTION, description)
        put(ApiFields.SELECTION_MODE, selectionMode)
        put(ApiFields.ALLOW_REVOTING, allowRevoting)
        put(ApiFields.INCLUDE_ADMINS, includeAdmins)
        put(ApiFields.OPTIONS, JSONArray(options))
        if (attachments.length() > 0) put(ApiFields.ATTACHMENTS, attachments)
    }

    override fun voteNewsPoll(pollId: Long, optionIds: List<Long>): JSONObject =
        gateway.request(ApiActions.VOTE_NEWS_POLL) {
            put(ApiFields.POLL_ID, pollId)
            put(ApiFields.OPTION_IDS, JSONArray(optionIds))
        }

    override fun getPollStats(pollId: Long): JSONObject =
        gateway.request(ApiActions.GET_POLL_STATS) { put(ApiFields.POLL_ID, pollId) }

    // ── Pin / readers ─────────────────────────────────────────────────

    override fun pinMessage(messageId: Long): JSONObject =
        gateway.request(ApiActions.PIN_MESSAGE) { put(ApiFields.MESSAGE_ID, messageId) }

    override fun unpinMessage(messageId: Long): JSONObject =
        gateway.request(ApiActions.UNPIN_MESSAGE) { put(ApiFields.MESSAGE_ID, messageId) }

    override fun getNewsReaders(messageId: Long): JSONObject =
        gateway.request(ApiActions.GET_NEWS_READERS) { put(ApiFields.MESSAGE_ID, messageId) }

    // ── Drafts ────────────────────────────────────────────────────────

    override fun saveDraft(scope: String, text: String): JSONObject =
        gateway.request(ApiActions.SAVE_DRAFT) {
            put(ApiFields.SCOPE, scope)
            put(ApiFields.TEXT, text)
        }

    override fun loadDraft(scope: String): JSONObject =
        gateway.request(ApiActions.LOAD_DRAFT) { put(ApiFields.SCOPE, scope) }

    override fun listDrafts(): JSONObject = gateway.request(ApiActions.LIST_DRAFTS)

    // ── Scheduled ─────────────────────────────────────────────────────

    override fun scheduleMessage(kind: String, payload: JSONObject, sendAt: String): JSONObject =
        gateway.request(ApiActions.SCHEDULE_MESSAGE) {
            put(ApiFields.KIND, kind)
            put(ApiFields.PAYLOAD, payload)
            put(ApiFields.SEND_AT, sendAt)
        }

    override fun listScheduled(): JSONObject = gateway.request(ApiActions.LIST_SCHEDULED)

    override fun cancelScheduled(id: Long): JSONObject =
        gateway.request(ApiActions.CANCEL_SCHEDULED) { put(ApiFields.ID, id) }
}
