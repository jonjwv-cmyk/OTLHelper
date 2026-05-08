package com.example.otlhelper.desktop.core.session

import com.example.otlhelper.desktop.data.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * §TZ-0.10.5 — Управление PC-сессией: countdown + расширения + блокировка.
 *
 * - Стартует когда юзер залогинился (login через QR или password fallback).
 * - Раз в 30 сек запрашивает [ApiClient.meSessionInfo] → обновляет state.
 * - Если remainingMs <= 5 мин и extensions_remaining > 0 — выставляет
 *   [State.shouldShowExtensionPrompt] = true.
 * - Если remainingMs == 0 → выставляет [State.locked] = true → UI должен
 *   показать LockOverlay.
 * - Если получили 401 'session_expired_window' — мгновенно lock.
 *
 * Используется через композитный hook'инг в App.kt:
 *   val mgr = remember { SessionLifecycleManager(scope) }
 *   mgr.start()  при login, mgr.stop() при logout.
 */
class SessionLifecycleManager(
    private val scope: CoroutineScope,
) {

    data class State(
        val sessionKind: String = "",      // 'pc_qr' / 'pc_password' / 'standard' / ...
        val isPc: Boolean = false,         // false = no time-window enforcement
        val expiresAt: String = "",        // server ISO 'YYYY-MM-DD HH:MM:SS' (UTC)
        val remainingMs: Long = 0L,
        val extensionsUsed: Int = 0,
        val extensionsRemaining: Int = 0,
        val deviceLabel: String = "",
        val yekHm: String = "",            // "17:00" / "15:45" — when expiry hits
        val locked: Boolean = false,       // session_expired_window OR remainingMs <= 0
        val shouldShowExtensionPrompt: Boolean = false,  // <= 5 min from expiry, ext available
        val lastError: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var localTickJob: Job? = null

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
        // Local 1s tick — uniform countdown без дёрганий между server-poll'ами.
        localTickJob = scope.launch {
            while (true) {
                tickLocal()
                delay(1_000L)
            }
        }
        // §TZ-0.10.8 — instant lock через ApiClient.onAuthFailure hook на любой
        // 401 errror (не ждём 30-секундный polling). Главный сценарий — single-PC
        // revoke: юзер зашёл с другого ПК, старая сессия revoked, текущий Mac
        // на следующем /api получает 401 → мгновенно LockOverlay. Используем
        // отдельный hook (не onActionLatency, который занят NetworkMetricsBuffer).
        ApiClient.onAuthFailure = { errorCode, _ ->
            if (!_state.value.locked) {
                _state.value = _state.value.copy(locked = true, lastError = errorCode.ifBlank { "auth_lost" })
            }
        }
    }

    fun stop() {
        pollJob?.cancel(); pollJob = null
        localTickJob?.cancel(); localTickJob = null
        ApiClient.onAuthFailure = null
        _state.value = State()
    }

    /** Server-driven refresh — синхронизация с D1 truth. */
    suspend fun refresh() {
        try {
            val resp = ApiClient.meSessionInfo()
            if (!resp.optBoolean("ok", false)) {
                val err = resp.optString("error", "")
                // §TZ-0.10.8 — lock на любую dead-token ошибку. Раньше lock
                // случался только на session_expired_window — но при single-PC
                // revoke (юзер зашёл с другого ПК → старая сессия revoked) Mac
                // получал token_revoked и просто продолжал «работать» с битым
                // токеном (Sheets webview не зависит от нашего api, но всё
                // что через /api — молчало). Теперь любой dead-token ⇒ lock.
                if (err in DEAD_TOKEN_ERRORS) {
                    _state.value = _state.value.copy(locked = true, lastError = err)
                } else {
                    _state.value = _state.value.copy(lastError = err)
                }
                return
            }
            val s = resp.optJSONObject("session") ?: return
            val expiresAt = s.optString("expires_at")
            val remaining = s.optLong("remaining_ms", 0L)
            val isPc = s.optBoolean("is_pc", false)
            val locked = isPc && remaining <= 0L
            _state.value = State(
                sessionKind = s.optString("session_kind"),
                isPc = isPc,
                expiresAt = expiresAt,
                remainingMs = remaining,
                extensionsUsed = s.optInt("extensions_used", 0),
                extensionsRemaining = s.optInt("extensions_remaining", 0),
                deviceLabel = s.optString("device_label"),
                yekHm = s.optString("yek_hm"),
                locked = locked,
                shouldShowExtensionPrompt = isPc && !locked
                    && remaining in 1..(EXTENSION_PROMPT_MS),
                lastError = "",
            )
        } catch (_: Exception) {
            // network blip — keep old state
        }
    }

    /** Локальный countdown между server polls — обновляет remainingMs/yekHm. */
    private fun tickLocal() {
        val cur = _state.value
        if (!cur.isPc || cur.expiresAt.isBlank()) return
        val expiryMs = parseDbIsoToMs(cur.expiresAt) ?: return
        val nowMs = System.currentTimeMillis()
        val remain = (expiryMs - nowMs).coerceAtLeast(0L)
        val locked = cur.isPc && remain <= 0L
        if (remain != cur.remainingMs || locked != cur.locked) {
            _state.value = cur.copy(
                remainingMs = remain,
                locked = locked,
                shouldShowExtensionPrompt = cur.isPc && !locked
                    && remain in 1..(EXTENSION_PROMPT_MS) && cur.extensionsRemaining > 0,
            )
        }
    }

    /** Юзер нажал «Продлить +30 мин». Возвращает true если успешно. */
    suspend fun extend(): Boolean {
        return try {
            val resp = ApiClient.extendSession()
            if (resp.optBoolean("ok", false)) {
                refresh()
                true
            } else {
                _state.value = _state.value.copy(lastError = resp.optString("error", ""))
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Скрыть extension prompt (юзер нажал "Не сейчас"). Не отменяет lifecycle, просто UI. */
    fun dismissExtensionPrompt() {
        _state.value = _state.value.copy(shouldShowExtensionPrompt = false)
    }

    /** Принудительный lock (например ApiClient получил 401 session_expired_window). */
    fun lockNow(reason: String = "session_expired_window") {
        _state.value = _state.value.copy(locked = true, lastError = reason)
    }

    /** Принудительный unlock после re-QR (новый login). */
    fun unlock() {
        _state.value = _state.value.copy(locked = false, lastError = "")
        scope.launch { refresh() }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 30_000L
        private const val EXTENSION_PROMPT_MS = 5 * 60 * 1000L  // 5 мин

        // §TZ-0.10.8 — все ошибки которые означают «токен мёртв» → надо
        // показать LockOverlay и заставить юзера re-QR. Покрывает single-PC
        // revoke (token_revoked), expired (token_expired), auth_required,
        // password_reset (после смены пароля старая сессия dead).
        private val DEAD_TOKEN_ERRORS = setOf(
            "session_expired_window",
            "token_revoked",
            "token_expired",
            "invalid_token",
            "auth_required",
            "password_reset",
            "user_inactive",
            "user_suspended",
        )

        private val yekZone = TimeZone.getTimeZone("Asia/Yekaterinburg").toZoneId()

        /** "YYYY-MM-DD HH:MM:SS" UTC → ms. */
        private fun parseDbIsoToMs(iso: String): Long? {
            if (iso.isBlank()) return null
            return try {
                val withT = iso.replace(' ', 'T') + "Z"
                ZonedDateTime.parse(withT, DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli()
            } catch (_: Exception) { null }
        }

        /** Pretty 'через 2ч 15мин' / 'через 28 сек'. */
        fun formatRemaining(remainMs: Long): String {
            if (remainMs <= 0L) return "истекло"
            val totalSec = (remainMs / 1000L).toInt()
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return when {
                h > 0 -> "через ${h}ч ${m}мин"
                m > 0 -> "через ${m}мин"
                else -> "через ${s} сек"
            }
        }

        /** Pretty Yek 'до 17:00'. yekHm от сервера или пустая. */
        fun formatExpiryYek(yekHm: String): String {
            return if (yekHm.isNotBlank()) "до $yekHm" else ""
        }
    }
}
