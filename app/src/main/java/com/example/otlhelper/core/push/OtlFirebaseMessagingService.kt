package com.example.otlhelper.core.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.otlhelper.MainActivity
import com.example.otlhelper.R
import com.example.otlhelper.data.sync.BaseSyncManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Entry point for FCM pushes. Two responsibilities:
 *
 * 1. [onNewToken] — server-side registration whenever Firebase rotates the token.
 * 2. [onMessageReceived] — show a system notification when a push arrives while
 *    the app is backgrounded. Foreground messages are ignored here — the app's
 *    live data flow already updates the UI in that case.
 *
 * The backend (Cloudflare Worker `otl-api`) sends `data`-only payloads with
 * `title`, `body`, `type`, `count` — we render those manually below.
 */
@AndroidEntryPoint
class OtlFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var tokenManager: PushTokenManager

    @Inject
    lateinit var eventBus: PushEventBus

    @Inject
    lateinit var baseSyncManager: BaseSyncManager

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "onNewToken: len=${token.length}, prefix=${token.take(16)}...")
        tokenManager.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.i(TAG, "onMessageReceived: from=${message.from}, data=${message.data}, " +
            "notif=${message.notification?.title}/${message.notification?.body}")
        val data = message.data
        val notification = message.notification

        // Fan out to any live ViewModel so the UI (unread badge, chat list,
        // news feed) refreshes immediately instead of waiting for the next
        // auto-refresh tick (up to 8-30 s).
        val type = data["type"] ?: "unknown"
        val delivered = eventBus.tryEmit(PushEvent.Received(type, data.toMap()))
        Log.d(TAG, "eventBus.tryEmit(type=$type) delivered=$delivered")

        // §RELIABLE-BASE канал 1: сервер в `handleBaseImportFull` шлёт
        // `type=base_changed` всем активным клиентам сразу после того как
        // положил новый gzipped snapshot в R2. Мы запускаем `force()` —
        // REPLACE-политика, которая убивает любой WorkManager-backoff от
        // предыдущих попыток и стартует новый Worker немедленно. Системное
        // уведомление НЕ показываем — silent sync, юзеру знать не надо.
        if (type == "base_changed") {
            val newVersion = data["base_version"] ?: ""
            Log.i(TAG, "base_changed push (version=$newVersion) — triggering force sync")
            try { baseSyncManager.force() } catch (e: Exception) {
                Log.w(TAG, "baseSyncManager.force() failed: ${e.message}", e)
            }
            return
        }

        val title = data["title"]
            ?: notification?.title
            ?: getString(R.string.app_name)
        // Body is optional — some server broadcasts (e.g. app_version) ship
        // title-only, and a title-only notification is a perfectly valid
        // Android pattern. Returning early here was silently swallowing
        // every "Доступно обновление приложения" push when the user was in
        // the foreground; the server log showed push:sent but nothing ever
        // hit the shade.
        val body = data["body"]
            ?: notification?.body
            ?: ""

        val count = data["count"]?.toIntOrNull() ?: 0

        ensureChannel()

        // §TZ-2.5.1 — Передаём type+id в Activity, чтобы навигировать к
        // конкретному сообщению/новости. Сервер сейчас type шлёт всегда,
        // id — постепенно (по мере раскатки server-side patch). Если id
        // отсутствует — Activity открывает соответствующий tab (поведение
        // как было). PendingIntent уникален per-(type,id) чтобы Android не
        // схлопывал FLAG_UPDATE_CURRENT при разных пушах в одном слоте.
        val pushType = data["type"]
        val pushId = data["id"] ?: data["message_id"] ?: data["news_id"]
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (pushType != null) putExtra("push_type", pushType)
            if (pushId != null) putExtra("push_id", pushId)
        }
        // requestCode = hash чтобы каждое уведомление получало свой PendingIntent
        // (иначе нажатия на разные пуши открывали бы один и тот же Activity slot).
        val pendingRequestCode = (pushType.orEmpty() + ":" + pushId.orEmpty()).hashCode()
        val pendingIntent = PendingIntent.getActivity(
            this, pendingRequestCode, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        if (body.isNotBlank()) builder.setContentText(body)
        if (count > 0) builder.setNumber(count)

        val nm = getSystemService(NotificationManager::class.java)
        val enabled = nm?.areNotificationsEnabled() ?: false
        nm?.notify(PUSH_NOTIFICATION_ID, builder.build())
        Log.i(
            TAG,
            "notification posted: id=$PUSH_NOTIFICATION_ID channel=$CHANNEL_ID " +
                "enabled=$enabled title=\"$title\" body=\"${body.take(60)}\""
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Новые сообщения и новости",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления OTL Helper"
                enableLights(true)
                enableVibration(true)
            }
        )
    }

    companion object {
        /**
         * Must match the Cloudflare Worker push payload
         * (`android.notification.channel_id = "otl_unread_v2"`).
         * Android O+ silently drops a notification whose channel_id doesn't
         * exist — rename in lockstep with the server if you ever change it.
         */
        const val CHANNEL_ID = "otl_unread_v2"
        private const val PUSH_NOTIFICATION_ID = 2001
        private const val TAG = "FcmService"
    }
}
