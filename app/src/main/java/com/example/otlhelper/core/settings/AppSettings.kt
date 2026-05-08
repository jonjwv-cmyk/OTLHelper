package com.example.otlhelper.core.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight settings store backed by SharedPreferences.
 *
 * Everything here is user preference (UI toggles), not auth/session — that
 * lives in [com.example.otlhelper.SessionManager]. Kept deliberately simple:
 * Boolean toggles + an enum-ish String for theme/language.
 */
@Singleton
class AppSettings @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Notifications ────────────────────────────────────────────────────────
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    var notificationSound: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_SOUND, value).apply()

    var notificationVibration: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_VIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_VIBRATE, value).apply()

    // ── Behaviour ────────────────────────────────────────────────────────────
    /** Auto-mark messages as read when scrolled into view (default on). */
    var autoMarkRead: Boolean
        get() = prefs.getBoolean(KEY_AUTO_READ, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_READ, value).apply()

    /** Inline play videos in the feed (off saves data). */
    var autoplayVideos: Boolean
        get() = prefs.getBoolean(KEY_AUTOPLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOPLAY, value).apply()

    /**
     * §TZ-2.3.6 — тактильная обратная связь (виброотклик) на тапах, нажатиях,
     * сменах таба, границах прокрутки, подтверждениях действий. Unified SF-2026
     * feel — лёгкие pixel-secondary-тики, не навязчиво. Значения по умолчанию
     * включены, но выключаются в настройках для тех кто раздражается.
     */
    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    /**
     * §TZ-2.4.0 — E2E шифрование API-запросов (HPKE-like, X25519+HKDF+AES-GCM)
     * теперь **обязательно**. Сервер enforced'ит наличие `X-OTL-Crypto: v1`
     * (отказ HTTP 426 для plaintext). Setter оставлен для backward compat
     * call-sites, но фактическое значение всегда `true`.
     */
    val e2eeEnabled: Boolean get() = true

    /**
     * §TZ-2.3.36 — retention policy для локального Room-кеша. Варианты:
     *   30, 60, 90 — дней; 0 = без ограничения. Default 30.
     * Сервер держит полную историю; локально старое чистится для forensics-
     * resistance при компрометации устройства.
     */
    var cacheRetentionDays: Int
        get() = prefs.getInt(KEY_CACHE_RETENTION, 30)
        set(value) = prefs.edit().putInt(KEY_CACHE_RETENTION, value).apply()

    /** Timestamp последнего cleanup'а кеша. WorkManager/onCreate только раз в сутки. */
    var lastCacheCleanupMs: Long
        get() = prefs.getLong(KEY_LAST_CLEANUP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CLEANUP, value).apply()

    /**
     * §TZ-2.3.36 Phase 5 — биометрическая блокировка на resume из background.
     * `lockOnResumeSeconds`:
     *   0   = выкл (не запрашивать биометрию после возврата),
     *   1   = сразу при любом возврате,
     *   60  = если был в фоне >1 мин,
     *   300, 1800 — 5 мин / 30 мин.
     */
    var lockOnResumeSeconds: Int
        get() = prefs.getInt(KEY_LOCK_RESUME, 0)
        set(value) = prefs.edit().putInt(KEY_LOCK_RESUME, value).apply()

    /**
     * Тихие интерфейсные звуки. §TZ-2.3.22 — звук играется ТОЛЬКО на
     * send/receive в чате и send новости (см. `FeedbackService.messageSent`/
     * `.receive`). Остальные haptic-события (tap/confirm/warn/tick) — без
     * звука, только вибрация. Поэтому default = true: включённый toggle
     * не создаёт шум в интерфейсе, только озвучивает реальные события
     * общения. Юзер может отключить если мешает.
     */
    var uiSoundsEnabled: Boolean
        get() = prefs.getBoolean(KEY_UI_SOUNDS, true)
        set(value) = prefs.edit().putBoolean(KEY_UI_SOUNDS, value).apply()

    // ── Appearance ───────────────────────────────────────────────────────────
    var themeMode: com.example.otlhelper.core.theme.ThemeMode
        get() = try {
            com.example.otlhelper.core.theme.ThemeMode.valueOf(
                prefs.getString(KEY_THEME_MODE, com.example.otlhelper.core.theme.ThemeMode.STANDARD.name) ?: com.example.otlhelper.core.theme.ThemeMode.STANDARD.name
            )
        } catch (_: IllegalArgumentException) {
            com.example.otlhelper.core.theme.ThemeMode.STANDARD
        }
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.name).apply()

    // ── Localization (UI string for now — no resource swap yet) ──────────────
    var language: String
        get() = prefs.getString(KEY_LANG, "ru") ?: "ru"
        set(value) = prefs.edit().putString(KEY_LANG, value).apply()

    // ── Offline data cache ───────────────────────────────────────────────────
    // Lightweight write-through cache for data that should survive app
    // kill → relaunch and show instantly on next open, before the network
    // fetch lands. Values are raw server JSON so hydration is a parse,
    // never a schema migration.
    var cachedUsersListJson: String
        get() = prefs.getString(KEY_CACHE_USERS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CACHE_USERS, value).apply()

    /** Cached voters for a reaction — keyed by message_id so each message
     *  has its own snapshot. Shown instantly on re-open of the developer
     *  reaction-voters dialog; background fetch then refreshes silently. */
    fun cachedReactionVotersJson(messageId: Long): String =
        prefs.getString("$KEY_CACHE_REACTIONS_PREFIX$messageId", "") ?: ""
    fun saveCachedReactionVotersJson(messageId: Long, json: String) {
        prefs.edit().putString("$KEY_CACHE_REACTIONS_PREFIX$messageId", json).apply()
    }

    private companion object {
        const val PREFS = "app_settings"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_NOTIF_SOUND = "notif_sound"
        const val KEY_NOTIF_VIBRATE = "notif_vibrate"
        const val KEY_AUTO_READ = "auto_mark_read"
        const val KEY_AUTOPLAY = "autoplay_videos"
        const val KEY_HAPTICS = "haptics_enabled"
        const val KEY_UI_SOUNDS = "ui_sounds_enabled"
        const val KEY_E2EE = "e2ee_enabled"
        const val KEY_LANG = "language"
        const val KEY_CACHE_USERS = "cache_users_list_json"
        const val KEY_CACHE_REACTIONS_PREFIX = "cache_reactions_voters_"
        const val KEY_CACHE_RETENTION = "cache_retention_days"
        const val KEY_LAST_CLEANUP = "cache_last_cleanup_ms"
        const val KEY_LOCK_RESUME = "lock_on_resume_seconds"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
