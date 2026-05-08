package com.example.otlhelper.core.network

import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * §TZ-2.4.0 — Route management для Android client.
 *
 * Хранит preferred VPS endpoint (PRIMARY = 45.12.239.5, опционально BACKUP =
 * второй VPS как fallback). DNS lookup в [com.example.otlhelper.data.network.HttpClientFactory]
 * читает [orderedIps] и отдаёт в OkHttp — тот нативно перебирает по списку
 * при ConnectException.
 *
 * Pre-flight ping ([warmRoute]) запускается из [OtlApp.onCreate] асинхронно:
 * параллельно тыкает оба IP, ставит здорового первым.
 *
 * Минимальная версия без circuit breaker — этого достаточно для базового
 * fallback (OkHttp сам перебирает IPs при connection fail). Circuit breaker
 * с success/failure счётчиками — опциональный sprint.
 *
 * BACKUP_VPS_IP захардкожен как `null` пока юзер не дал реальный IP второго
 * VPS. Когда даст — заменить на строковый литерал; всё остальное работает.
 */
object RouteState {

    private const val TAG = "RouteState"
    private const val PRIMARY_HOST = "45.12.239.5"

    /** Backup VPS IP (Cloud.ru / Selectel / Oracle Free). null = backup не настроен. */
    private val BACKUP_HOST: String? = null

    enum class Route { PRIMARY, BACKUP }

    private val preferred: AtomicReference<Route> = AtomicReference(Route.PRIMARY)

    private val primaryIp: InetAddress by lazy { InetAddress.getByName(PRIMARY_HOST) }
    private val backupIp: InetAddress? by lazy {
        BACKUP_HOST?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
    }

    /**
     * Список IP в порядке предпочтения. OkHttp при connection fail на первом
     * автоматически попробует следующий. Если backup не настроен —
     * возвращает только primary.
     */
    fun orderedIps(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        when (preferred.get()) {
            Route.PRIMARY -> {
                list += primaryIp
                backupIp?.let { list += it }
            }
            Route.BACKUP -> {
                backupIp?.let { list += it }
                list += primaryIp
            }
            null -> list += primaryIp
        }
        return list
    }

    /** Текущее предпочтение (для observability). */
    fun current(): Route = preferred.get() ?: Route.PRIMARY

    /**
     * Pre-flight TCP ping обоих VPS параллельно. Здорового ставит первым.
     * 3-секундный TCP connect (без TLS — слишком долго для startup).
     * Не критично если оба fail — preferred остаётся PRIMARY (default).
     */
    suspend fun warmRoute() {
        val primaryAlive = pingTcp(PRIMARY_HOST, 443, timeoutMs = 3_000)
        val backupAlive = BACKUP_HOST?.let { pingTcp(it, 80, timeoutMs = 3_000) } ?: false
        val newPref = when {
            primaryAlive -> Route.PRIMARY
            backupAlive -> Route.BACKUP
            else -> Route.PRIMARY // ничего не алайв — оставляем default, OkHttp сам разберётся
        }
        val old = preferred.getAndSet(newPref)
        if (old != newPref) {
            Log.i(TAG, "warm route=$newPref primary=$primaryAlive backup=$backupAlive")
        }
    }

    /**
     * Сообщает что primary IP не отвечает — переключение на backup.
     * Вызывается из interceptor'а при ConnectException/SocketTimeout.
     * No-op если backup не настроен или мы уже на backup.
     */
    fun markPrimaryDown() {
        if (BACKUP_HOST == null) return
        val old = preferred.getAndSet(Route.BACKUP)
        if (old != Route.BACKUP) {
            Log.i(TAG, "switch to BACKUP (primary unreachable)")
        }
    }

    /** Restore primary после успешного health check. */
    fun markPrimaryUp() {
        val old = preferred.getAndSet(Route.PRIMARY)
        if (old != Route.PRIMARY) {
            Log.i(TAG, "switch back to PRIMARY (health restored)")
        }
    }

    private fun pingTcp(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }
}
