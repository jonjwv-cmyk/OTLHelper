package com.example.otlhelper.core.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

/**
 * SF-2026 §3.15.a.Б — раздельные каналы уведомлений по приоритету.
 *
 * Три канала соответствуют трём уровням [com.example.otlhelper.domain.model.NewsPriority]:
 *  - `otl_unread_v2`   — Normal (совместим с существующим кодом).
 *  - `otl_important_v1` — Important: громче, с вибрацией, но не full-screen.
 *  - `otl_urgent_v1`    — Urgent: IMPORTANCE_MAX + SOUND + FULL_SCREEN intent
 *                         (пробивает DND).
 *
 * Пользователь в системных настройках может отдельно отключить любой канал
 * (§3.15.a.В «Канал "Новости" и канал "Чат" разделены»).
 */
object NotificationChannels {

    const val NORMAL_ID    = "otl_unread_v2"
    const val IMPORTANT_ID = "otl_important_v1"
    const val URGENT_ID    = "otl_urgent_v1"
    /** Тихий канал для foreground-работы: загрузка справочника МОЛ. */
    const val SYNC_ID      = "otl_sync_v1"
    const val SYNC_NOTIF_ID = 42_001

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val default = NotificationChannel(
            NORMAL_ID, "Уведомления",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Обычные сообщения и новости"
        }

        val important = NotificationChannel(
            IMPORTANT_ID, "Важные",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Важные новости — бейдж и звук"
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }

        val urgent = NotificationChannel(
            URGENT_ID, "Срочные",
            NotificationManager.IMPORTANCE_MAX,
        ).apply {
            description = "Критичные оповещения — full-screen, пробивают DND"
            enableVibration(true)
            setBypassDnd(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        // Тихий канал для фоновой синхронизации справочника — без звука,
        // без вибрации, минимально заметный в шторке (чтобы не раздражал).
        val sync = NotificationChannel(
            SYNC_ID, "Синхронизация",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Загрузка справочника МОЛ в фоне"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        nm.createNotificationChannels(listOf(default, important, urgent, sync))
    }
}
