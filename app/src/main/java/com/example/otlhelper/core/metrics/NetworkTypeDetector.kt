package com.example.otlhelper.core.metrics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Определяет тип сети в моменте — чтобы метрики запросов могли быть
 * агрегированы по Wi-Fi vs Cellular и мы видели: тормозит ли именно в
 * мобильной сети, или везде.
 *
 * Вызывается на каждый ApiClient-запрос → должен быть дёшев. Внутри —
 * ConnectivityManager.getActiveNetwork + getCapabilities (системный кэш).
 */
@Singleton
class NetworkTypeDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cm: ConnectivityManager? by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    fun current(): String {
        val manager = cm ?: return UNKNOWN
        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@runCatching UNKNOWN
            val active = manager.activeNetwork ?: return@runCatching NONE
            val caps = manager.getNetworkCapabilities(active) ?: return@runCatching NONE
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> VPN
                else -> UNKNOWN
            }
        }.getOrDefault(UNKNOWN)
    }

    companion object {
        const val WIFI = "wifi"
        const val CELLULAR = "cellular"
        const val ETHERNET = "ethernet"
        const val VPN = "vpn"
        const val NONE = "none"
        const val UNKNOWN = "unknown"
    }
}
