package com.example.otlhelper.core.cache

import com.example.otlhelper.core.settings.AppSettings
import com.example.otlhelper.data.db.dao.FeedItemDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §TZ-2.3.36 — retention policy для локального Room-кеша.
 *
 * Запускается из [com.example.otlhelper.OtlApp.onCreate] fire-and-forget.
 * Работает **раз в сутки** (guard через `AppSettings.lastCacheCleanupMs`).
 *
 * Удаляет cached_feed_items.updated_at < (now - retentionDays).
 * Если `cacheRetentionDays == 0` (forever) — skip.
 *
 * Почему это важно:
 *  - Сервер хранит полную историю (D1) → ничего не теряется.
 *  - Локальная Room-DB зашифрована SQLCipher (ключ в Keystore), но всё равно
 *    минимизация = меньше forensics surface при компрометации устройства.
 *  - 30 дней = обычный UX-объём; старые чаты юзер всё равно редко листает.
 */
@Singleton
class CacheRetentionRunner @Inject constructor(
    private val settings: AppSettings,
    private val feedItemDao: FeedItemDao,
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val oneDayMs: Long = 24L * 60 * 60 * 1000

    fun runIfDue() {
        val retention = settings.cacheRetentionDays
        if (retention <= 0) return  // forever = skip
        val now = System.currentTimeMillis()
        val lastRun = settings.lastCacheCleanupMs
        if (now - lastRun < oneDayMs) return  // запускаем не чаще раза в сутки

        scope.launch {
            try {
                val cutoffMs = now - retention * oneDayMs
                val cutoffIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(cutoffMs))
                feedItemDao.deleteOlderThan(cutoffIso)
                settings.lastCacheCleanupMs = now
            } catch (_: Throwable) {
                // Ошибка cleanup'а не критична — попробуем завтра.
            }
        }
    }
}
