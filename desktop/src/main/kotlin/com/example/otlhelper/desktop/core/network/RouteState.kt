package com.example.otlhelper.desktop.core.network

import com.example.otlhelper.desktop.data.security.Secrets
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * §TZ-2.4.0 — desktop mirror of `app/.../core/network/RouteState.kt`.
 *
 * Хранит preferred VPS endpoint. Primary IP читается из обфусцированного
 * [Secrets.VPS_HOST_IP]. Backup IP пока `null` — когда юзер выпустит второй
 * VPS, добавить XOR-обфусцированную константу в Secrets.
 *
 * DNS lookup в [HttpClientFactory] читает [orderedIps] и отдаёт OkHttp;
 * тот при ConnectException автоматически перебирает по списку.
 *
 * Pre-flight ping ([warmRoute]) запускается асинхронно в Main.kt при старте.
 */
object RouteState {

    /** Backup VPS host (Cloud.ru / Selectel). null = backup не настроен. */
    private val BACKUP_HOST: String? = null

    enum class Route { PRIMARY, BACKUP }

    private val preferred: AtomicReference<Route> = AtomicReference(Route.PRIMARY)

    private val primaryIp: InetAddress by lazy { InetAddress.getByName(Secrets.VPS_HOST_IP) }
    private val backupIp: InetAddress? by lazy {
        BACKUP_HOST?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
    }

    fun orderedIps(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        when (preferred.get() ?: Route.PRIMARY) {
            Route.PRIMARY -> {
                list += primaryIp
                backupIp?.let { list += it }
            }
            Route.BACKUP -> {
                backupIp?.let { list += it }
                list += primaryIp
            }
        }
        return list
    }

    fun current(): Route = preferred.get() ?: Route.PRIMARY

    /**
     * Параллельно ping primary :443 + backup :80 за 3s. Здорового — первым.
     * No-op если backup не настроен (preferred остаётся PRIMARY).
     */
    suspend fun warmRoute() {
        val primaryHost = Secrets.VPS_HOST_IP
        val primaryAlive = pingTcp(primaryHost, 443, timeoutMs = 3_000)
        val backupAlive = BACKUP_HOST?.let { pingTcp(it, 80, timeoutMs = 3_000) } ?: false
        val newPref = when {
            primaryAlive -> Route.PRIMARY
            backupAlive -> Route.BACKUP
            else -> Route.PRIMARY
        }
        preferred.set(newPref)
    }

    fun markPrimaryDown() {
        if (BACKUP_HOST == null) return
        preferred.set(Route.BACKUP)
    }

    fun markPrimaryUp() {
        preferred.set(Route.PRIMARY)
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
