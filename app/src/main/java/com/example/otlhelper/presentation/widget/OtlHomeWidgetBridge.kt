package com.example.otlhelper.presentation.widget

import android.content.Context

/**
 * One-way bridge app → widget. All widget state flows through here.
 */
object OtlHomeWidgetBridge {

    /** Unread counts — header chip + subtitle rely on these. */
    fun publish(context: Context, newsUnread: Int, chatUnread: Int) {
        val prefs = context.getSharedPreferences(OtlHomeWidget.PREFS_NAME, Context.MODE_PRIVATE)
        val prevNews = prefs.getInt(OtlHomeWidget.KEY_NEWS_UNREAD, -1)
        val prevChat = prefs.getInt(OtlHomeWidget.KEY_CHAT_UNREAD, -1)
        if (prevNews == newsUnread && prevChat == chatUnread) return
        prefs.edit()
            .putInt(OtlHomeWidget.KEY_NEWS_UNREAD, newsUnread.coerceAtLeast(0))
            .putInt(OtlHomeWidget.KEY_CHAT_UNREAD, chatUnread.coerceAtLeast(0))
            .apply()
        OtlHomeWidget.refreshAll(context)
    }

    /**
     * Pinned list — up to 3 short preview strings. Pass empty list to hide.
     * Each string is a single line (will be ellipsized in the widget).
     */
    fun publishPinnedList(context: Context, lines: List<String>) {
        val trimmed = lines.map { it.trim() }
            .filter { it.isNotBlank() }
            .map { if (it.length > 80) it.take(77) + "…" else it }
        val p1 = trimmed.getOrElse(0) { "" }
        val p2 = trimmed.getOrElse(1) { "" }
        val p3 = trimmed.getOrElse(2) { "" }
        val prefs = context.getSharedPreferences(OtlHomeWidget.PREFS_NAME, Context.MODE_PRIVATE)
        if (sameAs(prefs, OtlHomeWidget.KEY_PIN1, p1) &&
            sameAs(prefs, OtlHomeWidget.KEY_PIN2, p2) &&
            sameAs(prefs, OtlHomeWidget.KEY_PIN3, p3)) return
        prefs.edit()
            .putString(OtlHomeWidget.KEY_PIN1, p1)
            .putString(OtlHomeWidget.KEY_PIN2, p2)
            .putString(OtlHomeWidget.KEY_PIN3, p3)
            .apply()
        OtlHomeWidget.refreshAll(context)
    }

    /** App-update indicator — shows at bottom when available = true. */
    fun publishUpdate(context: Context, available: Boolean, version: String? = null) {
        val prefs = context.getSharedPreferences(OtlHomeWidget.PREFS_NAME, Context.MODE_PRIVATE)
        val v = (version ?: "").trim()
        if (prefs.getBoolean(OtlHomeWidget.KEY_UPDATE_AVAILABLE, false) == available &&
            sameAs(prefs, OtlHomeWidget.KEY_UPDATE_VERSION, v)) return
        prefs.edit()
            .putBoolean(OtlHomeWidget.KEY_UPDATE_AVAILABLE, available)
            .putString(OtlHomeWidget.KEY_UPDATE_VERSION, v)
            .apply()
        OtlHomeWidget.refreshAll(context)
    }

    private fun sameAs(prefs: android.content.SharedPreferences, key: String, value: String): Boolean =
        (prefs.getString(key, "") ?: "") == value
}
