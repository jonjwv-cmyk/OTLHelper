package com.example.otlhelper.desktop.data.network

import com.example.otlhelper.shared.api.ApiActions
import com.example.otlhelper.shared.api.ApiFields

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * §TZ-DESKTOP-0.1.0 этап 3 — action-based REST клиент. Минимум, нужный
 * для login-flow. Дальнейшие endpoint'ы (get_news, get_admin_messages, …)
 * добавляем по мере подключения UI к реальным данным (этап 4).
 */
object ApiClient {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var bearerToken: String = ""

    fun setToken(token: String) { bearerToken = token }
    fun clearToken() { bearerToken = "" }
    fun hasToken(): Boolean = bearerToken.isNotBlank()
    fun currentToken(): String = bearerToken

    /**
     * §TZ-2.4.0 — hook для observability. Wire'ится из `Main.kt` →
     * [com.example.otlhelper.desktop.core.metrics.NetworkMetricsBuffer.record].
     * Закрывает диагностический gap (Mac/Win не лили `network_metrics`).
     */
    @Volatile
    var onActionLatency: ((action: String, durationMs: Long, ok: Boolean, httpStatus: Int, errorCode: String) -> Unit)? = null

    /**
     * §TZ-0.10.8 — отдельный hook для auth-failure событий (401 + dead-token
     * errors). Не пересекается с [onActionLatency] (тот используется для
     * NetworkMetricsBuffer). Подписывается [SessionLifecycleManager] для
     * мгновенного LockOverlay при single-PC revoke / token_revoked / etc.
     */
    @Volatile
    var onAuthFailure: ((errorCode: String, httpStatus: Int) -> Unit)? = null

    /**
     * Отправляет `action:<name>` с произвольным body. Возвращает ответ
     * как [JSONObject]. Сетевая работа на IO-диспетчере.
     *
     * §TZ-2.4.0 — token больше не идёт в Authorization header. Embed внутрь
     * body через [AuthSigningInterceptor] → encrypted через [E2EInterceptor]
     * → VPS не видит токена и payload'а.
     */
    suspend fun request(action: String, body: JSONObject.() -> Unit = {}): JSONObject =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put(ApiFields.ACTION, action)
                body()
            }
            val req = Request.Builder()
                .url(HttpClientFactory.API_URL)
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
            val startedAt = System.currentTimeMillis()
            try {
                HttpClientFactory.rest.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val parsed = try {
                        JSONObject(text.ifBlank { "{}" })
                    } catch (_: Exception) {
                        JSONObject().put(ApiFields.OK, false)
                            .put(ApiFields.ERROR, "http_${resp.code}")
                            .put(ApiFields.RAW, text.take(200))
                    }
                    val durationMs = System.currentTimeMillis() - startedAt
                    val ok = parsed.optBoolean(ApiFields.OK, false) && resp.isSuccessful
                    val errCode = parsed.optString(ApiFields.ERROR, "")
                    runCatching {
                        onActionLatency?.invoke(action, durationMs, ok, resp.code, errCode)
                    }
                    // §TZ-0.10.8 — instant lock on dead-token (single-PC revoke,
                    // expired window, password change, etc). Single-PC scenario:
                    // юзер зашёл с другого ПК → этот клиент на любом /api получает
                    // 401 token_revoked → SessionLifecycleManager triggers LockOverlay.
                    if (resp.code == 401) {
                        runCatching { onAuthFailure?.invoke(errCode, 401) }
                    }
                    parsed
                }
            } catch (e: Exception) {
                val durationMs = System.currentTimeMillis() - startedAt
                runCatching {
                    onActionLatency?.invoke(
                        action,
                        durationMs,
                        false,
                        0,
                        e.javaClass.simpleName.take(40),
                    )
                }
                throw e
            }
        }

    /**
     * Desktop-login. Сервер принимает `platform='desktop'` → role-guard
     * (admin/superadmin/developer only), иначе 403 `desktop_role_forbidden`.
     *
     * §TZ-DESKTOP-DIST — также передаём:
     *  • [appVersion] — текущая `BuildInfo.VERSION` (server сравнивает с
     *    current_version в whitelist'е `app_version`).
     *  • [binarySha] — `IntegrityCheck.selfSha256` self-вычисленный SHA
     *    running JAR. Server при mismatch (и совпадении app_version с
     *    current_version) возвращает 403 `binary_tampered`.
     *
     * Пустые значения сервер трактует как dev-сборку — пропускает enforcement.
     */
    suspend fun desktopLogin(
        login: String,
        password: String,
        deviceId: String,
        os: String,  // "mac" | "win"
        appVersion: String = "",
        binarySha: String = "",
    ): JSONObject = request(ApiActions.LOGIN) {
        put(ApiFields.LOGIN, login)
        put(ApiFields.PASSWORD, password)
        put(ApiFields.DEVICE_ID, deviceId)
        put(ApiFields.PLATFORM, ApiFields.PLATFORM_DESKTOP)
        put("desktop_os", os)
        if (appVersion.isNotBlank()) put(ApiFields.APP_VERSION, appVersion)
        if (binarySha.isNotBlank()) put("binary_sha", binarySha)
    }

    suspend fun logout(): JSONObject = request(ApiActions.LOGOUT) {
        put(ApiFields.PLATFORM, ApiFields.PLATFORM_DESKTOP)
    }

    /** Собственный профиль: full_name, avatar_url + blob keys, features. */
    suspend fun me(): JSONObject = request(ApiActions.ME) {}

    /**
     * §TZ-DESKTOP-DIST — version-check на старте, аналог Android `checkAppStatus`.
     *
     * [scope] = "desktop-mac" или "desktop-win" — раздельные D1-row'ы чтобы
     * релизить платформы независимо. Сервер вернёт current_version,
     * update_url (Worker /desktop/...), apk_sha256 (binary SHA-256 для
     * клиентской верификации), force_update.
     *
     * Endpoint публичный (без bearer'а тоже работает) — нужно чтобы сразу
     * после холодного старта до login'а можно было показать блокирующий
     * экран если min_version > installed.
     */
    suspend fun appStatus(
        scope: String,
        appVersion: String,
        binarySha: String = "",
    ): JSONObject = request(ApiActions.APP_STATUS) {
        put(ApiFields.APP_SCOPE, scope)
        put(ApiFields.APP_VERSION, appVersion)
        // Сервер логает SHA для observability (whitelist enforcement делает
        // только handleLogin, чтобы appStatus оставался безopas-public).
        if (binarySha.isNotBlank()) put("binary_sha", binarySha)
    }

    // ── §TZ-DESKTOP-0.1.0 этап 4b — чаты ──

    /** Inbox для админа/дева: per-sender last message + unread counts. */
    suspend fun getAdminMessages(limit: Int = 100): JSONObject = request(ApiActions.GET_ADMIN_MESSAGES) {
        put(ApiFields.LIMIT, limit)
    }

    /** Полная переписка админа с конкретным юзером (ASC by id). */
    suspend fun getAdminChat(userLogin: String, limit: Int = 200): JSONObject =
        request(ApiActions.GET_ADMIN_CHAT) {
            put(ApiFields.USER_LOGIN, userLogin)
            put(ApiFields.LIMIT, limit)
        }

    /** Отправка сообщения админа юзеру. Сервер `handleSendMessage` читает
     *  `receiver_login` (не `user_login` как в get_admin_chat).
     *  [attachments] — JSONArray с объектами {file_url, file_name, file_type, file_size}
     *  где file_url = "data:mime;base64,bytes" или уже загруженный R2 URL. */
    suspend fun sendMessageToUser(
        userLogin: String,
        text: String,
        replyToId: Long? = null,
        localItemId: String? = null,
        attachments: org.json.JSONArray = org.json.JSONArray(),
    ): JSONObject = request(ApiActions.SEND_MESSAGE) {
        put(ApiFields.RECEIVER_LOGIN, userLogin)
        put(ApiFields.TEXT, text)
        if (replyToId != null) put(ApiFields.REPLY_TO_ID, replyToId)
        if (!localItemId.isNullOrBlank()) put(ApiFields.LOCAL_ITEM_ID, localItemId)
        if (attachments.length() > 0) put(ApiFields.ATTACHMENTS, attachments)
    }

    /** Пометить сообщение прочитанным. */
    suspend fun markMessageRead(messageId: Long): JSONObject = request(ApiActions.MARK_MESSAGE_READ) {
        put(ApiFields.MESSAGE_ID, messageId)
    }

    /** Счётчики непрочитанных для BottomTabBar (news + admin_messages). */
    suspend fun getUnreadCounts(): JSONObject = request(ApiActions.GET_UNREAD_COUNTS) {}

    // ── §TZ-DESKTOP-0.1.0 этап 4c — реакции (edit/delete по политике НЕ делаем ни на Android, ни на desktop) ──

    /** Добавить реакцию (emoji из whitelist: 👍 ❤️ 😂 🎉 ✅). UNIQUE на сервере. */
    suspend fun addReaction(messageId: Long, emoji: String): JSONObject = request(ApiActions.ADD_REACTION) {
        put(ApiFields.MESSAGE_ID, messageId)
        put(ApiFields.EMOJI, emoji)
    }

    /** Убрать свою реакцию. */
    suspend fun removeReaction(messageId: Long, emoji: String): JSONObject = request(ApiActions.REMOVE_REACTION) {
        put(ApiFields.MESSAGE_ID, messageId)
        put(ApiFields.EMOJI, emoji)
    }

    /** Полный список реакций (aggregate + voters). developer видит voters+time. */
    suspend fun getReactions(messageId: Long): JSONObject = request(ApiActions.GET_REACTIONS) {
        put(ApiFields.MESSAGE_ID, messageId)
    }

    /** §TZ-DESKTOP-0.1.0 этап 5 — редактирование НОВОСТИ (автор или admin).
     *  Те же серверные endpoint'ы что и Android: edit_message / soft_delete_message.
     *  Для сообщений чата этими методами НЕ пользуемся — там политика запрещает.
     *  §TZ-DESKTOP-0.10.1 — server reads `body.id` (не `body.message_id`!) для
     *  edit_message и soft_delete_message. Раньше desktop отправлял `message_id` →
     *  server возвращал `{ok:false, error:"id_empty"}` → клиент тихо проглатывал
     *  в catch — UX «удаление не работает» / «редактирование не сохраняется».
     *  Android ВСЕГДА шлёт `ID` для этих двух actions (см. FeedCalls.kt). */
    suspend fun editNews(messageId: Long, newText: String): JSONObject = request(ApiActions.EDIT_MESSAGE) {
        put(ApiFields.ID, messageId)
        put(ApiFields.TEXT, newText)
    }

    suspend fun deleteNews(messageId: Long): JSONObject = request(ApiActions.SOFT_DELETE_MESSAGE) {
        put(ApiFields.ID, messageId)
    }

    // ── §TZ-DESKTOP-0.1.0 этап 5 — Новости + опросы + pin/stats ──

    suspend fun getNews(limit: Int = 50): JSONObject = request(ApiActions.GET_NEWS) {
        put(ApiFields.LIMIT, limit)
    }

    /** Создать новость. [attachments] — JSONArray объектов с file_url (data: или URL). */
    suspend fun sendNews(
        text: String,
        attachments: org.json.JSONArray = org.json.JSONArray(),
    ): JSONObject = request(ApiActions.SEND_NEWS) {
        put(ApiFields.TEXT, text)
        if (attachments.length() > 0) put(ApiFields.ATTACHMENTS, attachments)
    }

    /** Создать опрос (single-choice, include_admins=1 по серверной семантике). */
    suspend fun createNewsPoll(
        title: String,
        description: String,
        options: List<String>,
    ): JSONObject = request(ApiActions.CREATE_NEWS_POLL) {
        put(ApiFields.TITLE, title)
        put(ApiFields.TEXT, description)
        put(ApiFields.DESCRIPTION, description)
        put(ApiFields.SELECTION_MODE, ApiFields.SELECTION_MODE_SINGLE)
        put(ApiFields.ALLOW_REVOTING, false)
        put(ApiFields.INCLUDE_ADMINS, true)
        put(ApiFields.OPTIONS, org.json.JSONArray(options))
    }

    /** Проголосовать в опросе (single-choice — сервер берёт первый option_id). */
    suspend fun voteNewsPoll(pollId: Long, optionId: Long): JSONObject = request(ApiActions.VOTE_NEWS_POLL) {
        put(ApiFields.POLL_ID, pollId)
        put(ApiFields.OPTION_IDS, org.json.JSONArray().put(optionId))
    }

    /** Статистика прочтений для новости / опроса (admin+). */
    suspend fun getNewsReaders(messageId: Long): JSONObject = request(ApiActions.GET_NEWS_READERS) {
        put(ApiFields.MESSAGE_ID, messageId)
    }

    /** Статистика голосов опроса (admin+). */
    suspend fun getPollStats(pollId: Long): JSONObject = request(ApiActions.GET_POLL_STATS) {
        put(ApiFields.POLL_ID, pollId)
    }

    /** Закрепить новость/опрос (admin+). */
    suspend fun pinMessage(messageId: Long): JSONObject = request(ApiActions.PIN_MESSAGE) {
        put(ApiFields.MESSAGE_ID, messageId)
    }

    /** Открепить (admin+). */
    suspend fun unpinMessage(messageId: Long): JSONObject = request(ApiActions.UNPIN_MESSAGE) {
        put(ApiFields.MESSAGE_ID, messageId)
    }

    // ── §TZ-DESKTOP-0.1.0 этап 6 — Запланированные сообщения ──

    /** Запланировать новость (admin+). sendAtUtc в формате "yyyy-MM-dd HH:mm:ss" UTC. */
    suspend fun scheduleNews(text: String, sendAtUtc: String): JSONObject = request(ApiActions.SCHEDULE_MESSAGE) {
        put(ApiFields.KIND, "news")
        put(ApiFields.SEND_AT, sendAtUtc)
        put(ApiFields.PAYLOAD, JSONObject().put(ApiFields.TEXT, text))
    }

    /** Запланировать опрос (admin+). */
    suspend fun schedulePoll(
        title: String,
        description: String,
        options: List<String>,
        sendAtUtc: String,
    ): JSONObject = request(ApiActions.SCHEDULE_MESSAGE) {
        put(ApiFields.KIND, "poll")
        put(ApiFields.SEND_AT, sendAtUtc)
        put(
            "payload",
            JSONObject()
                .put(ApiFields.TITLE, title)
                .put(ApiFields.DESCRIPTION, description)
                .put(ApiFields.TEXT, description)
                .put(ApiFields.SELECTION_MODE, ApiFields.SELECTION_MODE_SINGLE)
                .put(ApiFields.ALLOW_REVOTING, false)
                .put(ApiFields.INCLUDE_ADMINS, true)
                .put(ApiFields.OPTIONS, org.json.JSONArray(options)),
        )
    }

    /** Список запланированных автора + sent/cancelled за 30 дней. */
    suspend fun listScheduled(): JSONObject = request(ApiActions.LIST_SCHEDULED) {}

    /** Отмена pending-scheduled (только автор). */
    suspend fun cancelScheduled(id: Long): JSONObject = request(ApiActions.CANCEL_SCHEDULED) {
        put(ApiFields.ID, id)
    }

    // ── §TZ-DESKTOP-0.1.0 — Account / User Management ──

    /** Смена собственного пароля. Сервер валидирует old_password, отзывает
     *  все остальные сессии кроме текущей при успехе. */
    suspend fun changePassword(oldPassword: String, newPassword: String): JSONObject =
        request(ApiActions.CHANGE_PASSWORD) {
            put(ApiFields.OLD_PASSWORD, oldPassword)
            put(ApiFields.NEW_PASSWORD, newPassword)
        }

    /** Список пользователей (admin+). Включает presence_status + avatar_url (с blob keys). */
    suspend fun getUsers(): JSONObject = request(ApiActions.GET_USERS) {}

    /** Создать пользователя (admin+; client и superadmin — только developer). */
    suspend fun createUser(
        login: String,
        fullName: String,
        password: String,
        role: String,
        mustChangePassword: Boolean = true,
    ): JSONObject = request(ApiActions.CREATE_USER) {
        put(ApiFields.NEW_LOGIN, login)
        put(ApiFields.FULL_NAME, fullName)
        put(ApiFields.PASSWORD, password)
        put(ApiFields.ROLE, role)
        put(ApiFields.MUST_CHANGE_PASSWORD, if (mustChangePassword) 1 else 0)
    }

    /** Сбросить пароль пользователю (superadmin only). Если newPassword blank — сервер ставит 1234. */
    suspend fun resetPassword(targetLogin: String, newPassword: String = ""): JSONObject =
        request(ApiActions.RESET_PASSWORD) {
            put(ApiFields.TARGET_LOGIN, targetLogin)
            if (newPassword.isNotBlank()) put(ApiFields.NEW_PASSWORD, newPassword)
        }

    /** Сменить роль (superadmin only). */
    suspend fun changeUserRole(targetLogin: String, newRole: String): JSONObject =
        request(ApiActions.CHANGE_ROLE) {
            put(ApiFields.TARGET_LOGIN, targetLogin)
            put(ApiFields.NEW_ROLE, newRole)
        }

    /** Сменить login пользователю (админ переименование). */
    suspend fun changeUserLogin(targetLogin: String, newLogin: String): JSONObject =
        request(ApiActions.CHANGE_LOGIN) {
            put(ApiFields.TARGET_LOGIN, targetLogin)
            put(ApiFields.NEW_LOGIN, newLogin)
        }

    /** §TZ-DESKTOP-0.10.2 — Переименовать (full_name) пользователя.
     *  В предыдущих версиях desktop'а отсутствовал — клик «Переименовать»
     *  в UserManagementSheet вызывал no-op. */
    suspend fun renameUser(targetLogin: String, fullName: String): JSONObject =
        request(ApiActions.RENAME_USER) {
            put(ApiFields.TARGET_LOGIN, targetLogin)
            put(ApiFields.FULL_NAME, fullName)
        }

    /** Удалить пользователя (superadmin only). */
    suspend fun deleteUser(targetLogin: String): JSONObject = request(ApiActions.DELETE_USER) {
        put(ApiFields.TARGET_LOGIN, targetLogin)
    }

    /** Загрузить аватар. [dataUrl] = "data:image/jpeg;base64,..." (сервер ограничение 500KB). */
    suspend fun setAvatar(dataUrl: String, mimeType: String, fileName: String): JSONObject =
        request(ApiActions.SET_AVATAR) {
            put(ApiFields.DATA_URL, dataUrl)
            put(ApiFields.MIME_TYPE, mimeType)
            put(ApiFields.FILE_NAME, fileName)
        }

    // ── §TZ-0.10.5 — QR PC login + scheduled session lifecycle ──

    /** Public (no auth). Создаёт challenge на сервере; возвращает qr_payload (JSON string for QR matrix). */
    suspend fun requestPcSessionQr(deviceLabel: String, desktopOs: String): JSONObject =
        request("request_pc_session_qr") {
            put("device_label", deviceLabel)
            put("desktop_os", desktopOs)
        }

    /** Public. Polls challenge: pending / redeemed / expired. */
    suspend fun checkPcSessionStatus(challenge: String): JSONObject =
        request("check_pc_session_status") {
            put("challenge", challenge)
        }

    /** Public. Emergency password fallback. customExpiryIso optional. */
    suspend fun passwordLoginPc(
        login: String,
        password: String,
        deviceLabel: String,
        desktopOs: String,
        customExpiryIso: String? = null,
    ): JSONObject = request("password_login_pc") {
        put("login", login)
        put("password", password)
        put("device_label", deviceLabel)
        put("desktop_os", desktopOs)
        if (!customExpiryIso.isNullOrBlank()) put("custom_expiry_iso", customExpiryIso)
    }

    /** Public. Counter '0/3' / '1/3' / ... для login screen. */
    suspend fun getPasswordCounter(login: String): JSONObject = request("get_password_counter") {
        put("login", login)
    }

    /** Auth required. Продлить сессию на 30 мин (max 3 продления). */
    suspend fun extendSession(): JSONObject = request("extend_session") {}

    /** Auth required. State моей PC сессии (для countdown UI). */
    suspend fun meSessionInfo(): JSONObject = request("me_session_info") {}

    /** Auth required. Список активных PC-сессий (для Android UI). */
    suspend fun listPcSessions(targetLogin: String? = null): JSONObject = request("list_pc_sessions") {
        if (!targetLogin.isNullOrBlank()) put("target_login", targetLogin)
    }

    /** Auth required. Прервать сессию (Android revoke с phone). */
    suspend fun revokePcSession(sessionId: String): JSONObject = request("revoke_pc_session") {
        put("session_id", sessionId)
    }

    /** Auth required. Сбросить лимит парольных входов (developer/superadmin). */
    suspend fun resetPasswordLoginCounter(targetLogin: String): JSONObject =
        request("reset_password_login_counter") {
            put("target_login", targetLogin)
        }
}
