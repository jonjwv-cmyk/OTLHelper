package com.example.otlhelper.presentation.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.otlhelper.MainActivity
import com.example.otlhelper.R

/**
 * Home-screen widget — defensive implementation.
 *
 * Uses only setTextViewText() calls (no setViewVisibility) so nothing can go
 * wrong during apply(). Empty slots just render as empty strings — layout
 * collapses naturally thanks to wrap_content heights.
 */
class OtlHomeWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) render(context, appWidgetManager, id)
    }

    companion object {
        const val PREFS_NAME = "otl_widget"
        const val KEY_NEWS_UNREAD = "news_unread"
        const val KEY_CHAT_UNREAD = "chat_unread"
        const val KEY_PIN1 = "pin1"
        const val KEY_PIN2 = "pin2"
        const val KEY_PIN3 = "pin3"
        const val KEY_UPDATE_AVAILABLE = "update_available"
        const val KEY_UPDATE_VERSION = "update_version"

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, OtlHomeWidget::class.java)
            val ids = manager.getAppWidgetIds(component) ?: return
            for (id in ids) render(context, manager, id)
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val news = prefs.getInt(KEY_NEWS_UNREAD, 0)
            val chat = prefs.getInt(KEY_CHAT_UNREAD, 0)
            val total = news + chat

            val pin1 = prefs.getString(KEY_PIN1, "") ?: ""
            val pin2 = prefs.getString(KEY_PIN2, "") ?: ""
            val pin3 = prefs.getString(KEY_PIN3, "") ?: ""
            val anyPinned = pin1.isNotBlank() || pin2.isNotBlank() || pin3.isNotBlank()

            val updateAvail = prefs.getBoolean(KEY_UPDATE_AVAILABLE, false)
            val updateVer = prefs.getString(KEY_UPDATE_VERSION, "") ?: ""

            val subtitle = when {
                total == 0 -> "Всё прочитано"
                news > 0 && chat > 0 -> "Новости · Чаты"
                news > 0 -> "Новости"
                else -> "Чаты"
            }

            val counterText = when {
                total == 0 -> ""
                total > 99 -> "99+"
                else -> total.toString()
            }

            val pinnedLabel = if (anyPinned) "ЗАКРЕПЛЕНО" else ""
            val updateText = when {
                !updateAvail -> ""
                updateVer.isNotBlank() -> "Доступно обновление $updateVer"
                else -> "Доступно обновление"
            }

            val views = RemoteViews(context.packageName, R.layout.widget_home).apply {
                setTextViewText(R.id.widget_title, "OTL Helper")
                setTextViewText(R.id.widget_subtitle, subtitle)
                setTextViewText(R.id.widget_counter_chip, counterText)
                setTextViewText(R.id.widget_pinned_label, pinnedLabel)
                setTextViewText(R.id.widget_pinned_1, formatPin(pin1))
                setTextViewText(R.id.widget_pinned_2, formatPin(pin2))
                setTextViewText(R.id.widget_pinned_3, formatPin(pin3))
                setTextViewText(R.id.widget_update_chip, updateText)
            }

            val launch = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context, 0, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, pending)

            manager.updateAppWidget(widgetId, views)
        }

        private fun formatPin(text: String): String =
            if (text.isBlank()) "" else "·  $text"
    }
}
