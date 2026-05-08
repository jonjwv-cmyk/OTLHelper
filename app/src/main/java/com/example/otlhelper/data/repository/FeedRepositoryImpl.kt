package com.example.otlhelper.data.repository

import com.example.otlhelper.data.db.dao.FeedItemDao
import com.example.otlhelper.data.db.dao.PendingActionDao
import com.example.otlhelper.data.db.entity.FeedItemEntity
import com.example.otlhelper.data.db.entity.PendingActionEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class FeedRepositoryImpl @Inject constructor(
    private val feedItemDao: FeedItemDao,
    private val pendingActionDao: PendingActionDao
) : FeedRepository {

    private fun nowDb(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    override suspend fun getCachedFeed(scope: String): JSONArray {
        val items = feedItemDao.getByScope(scope)
        val array = JSONArray()
        items.forEach { entity ->
            try {
                array.put(JSONObject(entity.payloadJson))
            } catch (e: Exception) {
                // Битый payload в Room — элемент просто не попадёт в
                // feed. Это indicates bug at cacheFeed()-write-time,
                // поэтому логируем с минимальным prefix (без PII).
                android.util.Log.w(
                    "FeedRepo",
                    "skipped corrupted feed row scope=$scope id=${entity.id} " +
                        "prefix=${entity.payloadJson.take(60)}",
                    e,
                )
            }
        }
        return array
    }

    override suspend fun hasCachedFeed(scope: String): Boolean =
        feedItemDao.getByScope(scope).isNotEmpty()

    override suspend fun cacheFeed(scope: String, items: JSONArray) {
        val normalizedScope = scope.trim().ifBlank { return }
        feedItemDao.deleteByScope(normalizedScope)
        val now = nowDb()
        val entities = buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val itemId = nextItemId(normalizedScope, item, i)
                val sortKey = item.optString("created_at", now)
                val kind = item.optString("kind", item.optString("type", "")).trim()
                add(
                    FeedItemEntity(
                        scope = normalizedScope,
                        itemId = itemId,
                        kind = kind,
                        sortKey = sortKey,
                        payloadJson = item.toString(),
                        updatedAt = now
                    )
                )
            }
        }
        feedItemDao.insertAll(entities)
    }

    private fun nextItemId(scope: String, item: JSONObject, index: Int): String {
        val explicit = item.optString("local_item_id").trim()
        if (explicit.isNotBlank()) return explicit
        // §TZ-2.3.5 final — `id=0` на сервере означает «контакт без сообщений»
        // (синтетический row из parallelDecorateChat с includeAllUsers). Если
        // таких несколько — все получают itemId="0" и Room коллапсит их в одну
        // строку через UNIQUE(scope, item_id) + REPLACE. Результат: кеш теряет
        // всех кроме последнего контакта без сообщений → при cold-start они
        // «догружаются» после сети. Делаем уникальный fallback по sender_login.
        val baseIdRaw = item.optString("id").ifBlank {
            item.optString("message_id").ifBlank { item.optString("poll_id") }
        }.trim()
        val baseId = if (baseIdRaw.isNotBlank() && baseIdRaw != "0") baseIdRaw else ""
        if (baseId.isNotBlank()) return baseId
        val senderLogin = item.optString("sender_login", "").trim()
        if (senderLogin.isNotBlank()) return "contact:$senderLogin"
        val createdAt = item.optString("created_at", "").trim()
        val kind = item.optString("kind", item.optString("type", "")).trim()
        val text = item.optString("text", "").trim()
        return listOf(scope, kind, senderLogin, createdAt, text, index.toString()).joinToString("|").ifBlank { "local_$index" }
    }

    override suspend fun enqueuePendingAction(actionType: String, payload: JSONObject, entityKey: String): Long {
        val normalizedAction = actionType.trim().ifBlank { return -1L }
        val normalizedKey = entityKey.trim()
        val payloadJson = payload.toString()

        val existingId = pendingActionDao.findExisting(normalizedAction, normalizedKey, payloadJson)
        if (existingId != null && existingId > 0L) return existingId

        val now = nowDb()
        return pendingActionDao.insert(
            PendingActionEntity(
                actionType = normalizedAction,
                entityKey = normalizedKey,
                payloadJson = payloadJson,
                status = "pending",
                retryCount = 0,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    override suspend fun getPendingActions(): List<JSONObject> {
        return pendingActionDao.getPending().map { entity ->
            val payload = try { JSONObject(entity.payloadJson) } catch (_: Exception) { JSONObject() }
            JSONObject().apply {
                put("id", entity.id)
                put("action_type", entity.actionType)
                put("entity_key", entity.entityKey)
                put("payload", payload)
                put("retry_count", entity.retryCount)
                put("created_at", entity.createdAt)
                put("updated_at", entity.updatedAt)
            }
        }
    }

    override suspend fun markPendingDone(id: Long) {
        if (id > 0L) pendingActionDao.deleteById(id)
    }

    override suspend fun markPendingRetry(id: Long) {
        if (id > 0L) pendingActionDao.incrementRetry(id, nowDb())
    }

    override suspend fun enqueuePendingVote(pollId: Long, optionIds: List<Long>, localItemId: String): Long {
        val payload = JSONObject().apply {
            put("poll_id", pollId)
            val ids = JSONArray(); optionIds.forEach { ids.put(it) }
            put("option_ids", ids)
            put("local_item_id", localItemId)
        }
        return enqueuePendingAction("vote_news_poll", payload, pollId.toString())
    }

    override suspend fun enqueuePendingNews(login: String, text: String, localItemId: String): Long {
        val payload = JSONObject().apply {
            put("login", login); put("text", text); put("local_item_id", localItemId)
        }
        return enqueuePendingAction("send_news", payload, login)
    }

    override suspend fun enqueuePendingMessage(login: String, text: String, receiverLogin: String?, localItemId: String): Long {
        val payload = JSONObject().apply {
            put("login", login)
            put("text", text)
            put("receiver_login", receiverLogin.orEmpty())
            put("local_item_id", localItemId)
        }
        return enqueuePendingAction("send_message", payload, receiverLogin.orEmpty().ifBlank { login })
    }

    override suspend fun enqueuePendingCreatePoll(
        login: String, title: String, description: String,
        selectionMode: String, allowRevoting: Boolean, includeAdmins: Boolean,
        options: List<String>, localItemId: String
    ): Long {
        val payload = JSONObject().apply {
            put("login", login); put("title", title); put("description", description)
            put("selection_mode", selectionMode); put("allow_revoting", allowRevoting)
            put("include_admins", includeAdmins); put("local_item_id", localItemId)
            val optionsJson = JSONArray(); options.forEach { optionsJson.put(it) }
            put("options", optionsJson)
        }
        return enqueuePendingAction("create_news_poll", payload, login)
    }

    override suspend fun enqueuePendingMarkRead(messageId: Long): Long {
        if (messageId <= 0L) return -1L
        val payload = JSONObject().apply { put("id", messageId) }
        // entityKey = id — dedupes if the same read is queued twice before flush
        return enqueuePendingAction("mark_message_read", payload, messageId.toString())
    }
}
