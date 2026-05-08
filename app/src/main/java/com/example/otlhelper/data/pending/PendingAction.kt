package com.example.otlhelper.data.pending

import com.example.otlhelper.ApiClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * SF-2026 §4.2: унифицированное представление действия, которое может быть выполнено
 * сейчас (если онлайн) или позже (оффлайн-очередь → `PendingActionFlusher`).
 *
 * **Принцип паспорта:** добавить новое оффлайн-действие = **один новый sealed-вариант**,
 * без правок в куче мест. Flusher узнаёт о новом action автоматически через
 * [fromRecord] в `companion object`.
 *
 * **Сериализация:** каждый вариант обязан реализовать [toJson] и dispatcher в
 * [fromRecord] — чтобы запись Room `(actionType, payloadJson)` полностью переживала
 * рестарт процесса и установку новой версии приложения.
 *
 * **Время отправки:** `execute()` делает актуальный запрос к [ApiClient] —
 * сервер проставляет `created_at` по моменту прихода, а не по моменту enqueue (§3.10).
 */
sealed class PendingAction {

    /** Серверное имя действия (wire-format). Совпадает с `action=` в JSON-запросе. */
    abstract val actionType: String

    /**
     * Дедуп-ключ при enqueue. Две enqueue с одинаковым
     * `(actionType, entityKey, payloadJson)` → один и тот же ряд в `pending_actions`.
     * Пустая строка допустима — в этом случае дедуп происходит по тому же триплету,
     * но entityKey не выделяет действие в отдельную «категорию».
     */
    open val entityKey: String = ""

    /** Сериализация полезной нагрузки в JSON для хранения в Room. */
    abstract fun toJson(): JSONObject

    /**
     * Выполнить действие синхронно через [ApiClient]. Возвращает сырой JSON-ответ
     * сервера (клиент проверяет `ok` + `error`). Вызывается уже на IO-диспатчере
     * из [PendingActionFlusher].
     */
    abstract fun execute(): JSONObject

    // ── Варианты SF-2026 ────────────────────────────────────────────────────

    data class SendNews(
        val text: String,
        val localItemId: String = ""
    ) : PendingAction() {
        override val actionType: String = Types.SEND_NEWS
        override fun toJson(): JSONObject = JSONObject().apply {
            put("text", text)
            put("local_item_id", localItemId)
        }
        override fun execute(): JSONObject = ApiClient.sendNews(text)
    }

    data class SendMessage(
        val text: String,
        val receiverLogin: String? = null,
        val localItemId: String = ""
    ) : PendingAction() {
        override val actionType: String = Types.SEND_MESSAGE
        override val entityKey: String = receiverLogin.orEmpty()
        override fun toJson(): JSONObject = JSONObject().apply {
            put("text", text)
            put("receiver_login", receiverLogin.orEmpty())
            put("local_item_id", localItemId)
        }
        override fun execute(): JSONObject =
            ApiClient.sendMessage(text, receiverLogin?.takeIf { it.isNotBlank() })
    }

    data class VotePoll(
        val pollId: Long,
        val optionIds: List<Long>,
        val localItemId: String = ""
    ) : PendingAction() {
        override val actionType: String = Types.VOTE_NEWS_POLL
        override val entityKey: String = pollId.toString()
        override fun toJson(): JSONObject = JSONObject().apply {
            put("poll_id", pollId)
            val arr = JSONArray(); optionIds.forEach { arr.put(it) }
            put("option_ids", arr)
            put("local_item_id", localItemId)
        }
        override fun execute(): JSONObject = ApiClient.voteNewsPoll(pollId, optionIds)
    }

    data class CreatePoll(
        val title: String,
        val description: String,
        val options: List<String>,
        val selectionMode: String = "single",
        val allowRevoting: Boolean = false,
        val includeAdmins: Boolean = true,
        val localItemId: String = ""
    ) : PendingAction() {
        override val actionType: String = Types.CREATE_NEWS_POLL
        override fun toJson(): JSONObject = JSONObject().apply {
            put("title", title)
            put("description", description)
            put("selection_mode", selectionMode)
            put("allow_revoting", allowRevoting)
            put("include_admins", includeAdmins)
            put("local_item_id", localItemId)
            val arr = JSONArray(); options.forEach { arr.put(it) }
            put("options", arr)
        }
        override fun execute(): JSONObject = ApiClient.createNewsPoll(
            title = title,
            description = description,
            options = options,
            selectionMode = selectionMode,
            allowRevoting = allowRevoting,
            includeAdmins = includeAdmins
        )
    }

    data class MarkRead(
        val messageId: Long
    ) : PendingAction() {
        override val actionType: String = Types.MARK_MESSAGE_READ
        override val entityKey: String = messageId.toString()
        override fun toJson(): JSONObject = JSONObject().apply { put("id", messageId) }
        override fun execute(): JSONObject = ApiClient.markMessageRead(messageId)
    }

    /**
     * Phase 11 (§3.14): один крэш / неперехваченная ошибка. Батчится в
     * `errors: [...]` и отправляется через action=log_errors.
     * Каждый enqueue добавляет ОДИН ряд (сервер сам агрегирует если получил
     * больше, чем один пакет за раз).
     */
    data class LogError(
        val occurredAt: String,
        val errorClass: String,
        val errorMessage: String,
        val stackTrace: String,
        val platform: String,
        val appVersion: String,
        val osVersion: String,
        val deviceModel: String,
        val deviceAbi: String
    ) : PendingAction() {
        override val actionType: String = Types.LOG_ERRORS
        override fun toJson(): JSONObject = JSONObject().apply {
            put("occurred_at", occurredAt)
            put("error_class", errorClass)
            put("error_message", errorMessage)
            put("stack_trace", stackTrace)
            put("platform", platform)
            put("app_version", appVersion)
            put("os_version", osVersion)
            put("device_model", deviceModel)
            put("device_abi", deviceAbi)
        }
        override fun execute(): JSONObject {
            // log_errors принимает массив; отправляем single-element batch.
            val arr = JSONArray().put(toJson())
            return ApiClient.logErrors(arr)
        }
    }

    /**
     * Phase 11 (§3.14): одно событие активности (screen_open, search_query,
     * message_sent, poll_voted, …). Батчится аналогично LogError.
     */
    data class LogActivity(
        val occurredAt: String,
        val eventType: String,
        val deviceId: String,
        val payload: JSONObject
    ) : PendingAction() {
        override val actionType: String = Types.LOG_ACTIVITY
        override fun toJson(): JSONObject = JSONObject().apply {
            put("occurred_at", occurredAt)
            put("event_type", eventType)
            put("device_id", deviceId)
            put("payload", payload)
        }
        override fun execute(): JSONObject {
            val arr = JSONArray().put(toJson())
            return ApiClient.logActivity(arr)
        }
    }

    /**
     * Phase 12a (§3.15.a.А): правка текста сообщения/новости/опроса.
     * entityKey = id — дедуп повторных enqueue'ев одной и той же правки.
     */
    data class EditMessage(
        val messageId: Long,
        val newText: String
    ) : PendingAction() {
        override val actionType: String = Types.EDIT_MESSAGE
        override val entityKey: String = messageId.toString()
        override fun toJson(): JSONObject = JSONObject().apply {
            put("id", messageId); put("text", newText)
        }
        override fun execute(): JSONObject = ApiClient.editMessage(messageId, newText)
    }

    /** Phase 12a: soft-delete сообщения. */
    data class SoftDeleteMessage(
        val messageId: Long
    ) : PendingAction() {
        override val actionType: String = Types.SOFT_DELETE_MESSAGE
        override val entityKey: String = messageId.toString()
        override fun toJson(): JSONObject = JSONObject().apply { put("id", messageId) }
        override fun execute(): JSONObject = ApiClient.softDeleteMessage(messageId)
    }

    /** Phase 12a: отмена soft-delete. */
    data class UndeleteMessage(
        val messageId: Long
    ) : PendingAction() {
        override val actionType: String = Types.UNDELETE_MESSAGE
        override val entityKey: String = messageId.toString()
        override fun toJson(): JSONObject = JSONObject().apply { put("id", messageId) }
        override fun execute(): JSONObject = ApiClient.undeleteMessage(messageId)
    }

    /**
     * Fallback для незнакомого `actionType` из старой БД-записи (например если
     * откатили клиент с новым типом действия). [PendingActionFlusher] такие
     * записи дропает, чтобы не спамить retry навсегда.
     */
    data class Unknown(
        val rawType: String,
        val rawPayload: JSONObject
    ) : PendingAction() {
        override val actionType: String = rawType
        override fun toJson(): JSONObject = rawPayload
        override fun execute(): JSONObject = JSONObject().apply {
            put("ok", false); put("error", "unknown_action")
        }
    }

    companion object {
        /**
         * Восстановить [PendingAction] из записи Room. Любая неизвестная строка
         * → [Unknown] (не бросает исключения).
         */
        fun fromRecord(actionType: String, payload: JSONObject): PendingAction = when (actionType) {
            Types.SEND_NEWS -> SendNews(
                text = payload.optString("text"),
                localItemId = payload.optString("local_item_id")
            )
            Types.SEND_MESSAGE -> SendMessage(
                text = payload.optString("text"),
                receiverLogin = payload.optString("receiver_login").takeIf { it.isNotBlank() },
                localItemId = payload.optString("local_item_id")
            )
            Types.VOTE_NEWS_POLL -> VotePoll(
                pollId = payload.optLong("poll_id"),
                optionIds = payload.optJSONArray("option_ids")?.let { arr ->
                    buildList { for (i in 0 until arr.length()) add(arr.optLong(i)) }
                } ?: emptyList(),
                localItemId = payload.optString("local_item_id")
            )
            Types.CREATE_NEWS_POLL -> CreatePoll(
                title = payload.optString("title"),
                description = payload.optString("description"),
                selectionMode = payload.optString("selection_mode", "single"),
                allowRevoting = payload.optBoolean("allow_revoting", false),
                includeAdmins = payload.optBoolean("include_admins", true),
                options = payload.optJSONArray("options")?.let { arr ->
                    buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
                } ?: emptyList(),
                localItemId = payload.optString("local_item_id")
            )
            Types.MARK_MESSAGE_READ -> MarkRead(payload.optLong("id", 0L))
            Types.LOG_ERRORS -> LogError(
                occurredAt   = payload.optString("occurred_at"),
                errorClass   = payload.optString("error_class"),
                errorMessage = payload.optString("error_message"),
                stackTrace   = payload.optString("stack_trace"),
                platform     = payload.optString("platform"),
                appVersion   = payload.optString("app_version"),
                osVersion    = payload.optString("os_version"),
                deviceModel  = payload.optString("device_model"),
                deviceAbi    = payload.optString("device_abi")
            )
            Types.LOG_ACTIVITY -> LogActivity(
                occurredAt = payload.optString("occurred_at"),
                eventType  = payload.optString("event_type"),
                deviceId   = payload.optString("device_id"),
                payload    = payload.optJSONObject("payload") ?: JSONObject()
            )
            Types.EDIT_MESSAGE -> EditMessage(
                messageId = payload.optLong("id", 0L),
                newText   = payload.optString("text")
            )
            Types.SOFT_DELETE_MESSAGE -> SoftDeleteMessage(payload.optLong("id", 0L))
            Types.UNDELETE_MESSAGE -> UndeleteMessage(payload.optLong("id", 0L))
            else -> Unknown(actionType, payload)
        }
    }

    /**
     * Wire-format имена action'ов. Единое место — проще заметить опечатку и добавить новый.
     */
    object Types {
        const val SEND_NEWS = "send_news"
        const val SEND_MESSAGE = "send_message"
        const val VOTE_NEWS_POLL = "vote_news_poll"
        const val CREATE_NEWS_POLL = "create_news_poll"
        const val MARK_MESSAGE_READ = "mark_message_read"
        const val LOG_ERRORS = "log_errors"
        const val LOG_ACTIVITY = "log_activity"
        const val EDIT_MESSAGE = "edit_message"
        const val SOFT_DELETE_MESSAGE = "soft_delete_message"
        const val UNDELETE_MESSAGE = "undelete_message"
        // Будущие (Phase 4 passport §3.10):
        // const val PIN_MESSAGE = "pin_message"
        // const val UNPIN_MESSAGE = "unpin_message"
        // const val UPLOAD_AVATAR = "upload_avatar"
        // const val CREATE_USER = "create_user"
        // const val UPDATE_USER = "update_user"
        // const val TOGGLE_USER = "toggle_user"
        // const val RESET_PASSWORD = "reset_password"
        // const val RENAME_USER = "rename_user"
        // const val CHANGE_PASSWORD = "change_password"
        // const val SET_APP_PAUSE = "set_app_pause"
        // const val CLEAR_APP_PAUSE = "clear_app_pause"
        // const val REGISTER_PUSH_TOKEN = "register_push_token"
        // const val UNREGISTER_PUSH_TOKEN = "unregister_push_token"
    }
}
