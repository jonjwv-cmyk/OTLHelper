package com.example.otlhelper.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Фасад над [BaseSyncWorker]. Даёт два метода:
 *  - [enqueue] — ставит задачу (идемпотентно, `ExistingWorkPolicy.KEEP`).
 *  - [status]  — Flow статуса для UI (splash progress + Settings).
 *
 * WorkManager сам retrime'ит на network loss (constraint `CONNECTED`) +
 * exponential backoff на `Result.retry()` → прилетит в лог только после
 * серии попыток, а не с первой же ошибки.
 */
@Singleton
class BaseSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: BaseSyncPrefs,
) {
    private val wm get() = WorkManager.getInstance(context)

    fun enqueue() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<BaseSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(BaseSyncWorker.UNIQUE_NAME)
            .build()
        // KEEP — если уже в очереди или выполняется, не трогаем. Это
        // предотвращает дубликат от двойного вызова (init + Settings-кнопка).
        wm.enqueueUniqueWork(
            BaseSyncWorker.UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Failsafe-канал трёхслойного дизайна надёжности §RELIABLE-BASE:
     *   1. FCM push от сервера — мгновенная доставка (см. `OtlFirebaseMessagingService`)
     *   2. Heartbeat piggyback — ~25с granularity когда app open (см. `AppController`)
     *   3. **PeriodicWork 15min** — эта функция; ловит кейс «FCM не доставился
     *      И app закрыт» (Huawei без GMS, агрессивный doze).
     *
     * Почему 15 мин: нижняя граница Android WorkManager PeriodicWorkRequest.
     * Меньше нельзя — OS проигнорирует и округлит. Но 15 мин — достаточный
     * резерв если FCM сработал (фронт-канал срабатывает за секунды).
     *
     * `KEEP` — идемпотентно. Вызываем из `OtlApp.onCreate` на каждом запуске
     * приложения; если задача уже зарегистрирована — no-op, иначе регистрируем.
     */
    fun enqueuePeriodic() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<BaseSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(BaseSyncWorker.UNIQUE_NAME_PERIODIC)
            .build()
        wm.enqueueUniquePeriodicWork(
            BaseSyncWorker.UNIQUE_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Принудительный перезапуск — нужен когда юзер жмёт «Обновить сейчас»
     * и мы хотим отменить любую текущую попытку (в т.ч. в состоянии retry)
     * и начать с чистого листа.
     *
     * Также сбрасывает прогресс (offset/chunkSize/pendingVersion) — важно
     * после обновления APK, где изменились DEFAULT_CHUNK и другие константы:
     * иначе стохранённый в prefs старый chunkSize (например 500 от v2.2.9)
     * перекроет новый DEFAULT_CHUNK=2000 в v2.3.0+, и фикс не сработает.
     */
    fun force() {
        prefs.resetProgress()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<BaseSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(BaseSyncWorker.UNIQUE_NAME)
            .build()
        wm.enqueueUniqueWork(
            BaseSyncWorker.UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun status(): Flow<BaseSyncStatus> =
        wm.getWorkInfosForUniqueWorkFlow(BaseSyncWorker.UNIQUE_NAME).map { list ->
            val info = list.firstOrNull() ?: return@map BaseSyncStatus.Idle
            when (info.state) {
                WorkInfo.State.ENQUEUED   -> BaseSyncStatus.Queued
                WorkInfo.State.RUNNING    -> {
                    val loaded = info.progress.getInt(BaseSyncWorker.KEY_LOADED, 0)
                    val total = info.progress.getInt(BaseSyncWorker.KEY_TOTAL, 0)
                    BaseSyncStatus.Running(loaded, total)
                }
                WorkInfo.State.SUCCEEDED  -> {
                    // freshly_downloaded=true → что-то реально скачали; =false
                    // → версия совпала, тихий hit. UI celebration показываем
                    // только на первое, на втором сразу текущую версию.
                    val freshly = info.outputData.getBoolean(
                        BaseSyncWorker.KEY_FRESHLY_DOWNLOADED, false,
                    )
                    BaseSyncStatus.Success(freshlyDownloaded = freshly)
                }
                WorkInfo.State.FAILED     -> BaseSyncStatus.Failed
                WorkInfo.State.CANCELLED  -> BaseSyncStatus.Idle
                WorkInfo.State.BLOCKED    -> BaseSyncStatus.Queued
            }
        }
}

sealed interface BaseSyncStatus {
    data object Idle : BaseSyncStatus
    data object Queued : BaseSyncStatus
    data class Running(val loaded: Int, val total: Int) : BaseSyncStatus {
        val percent: Int get() = if (total > 0) (loaded * 100 / total) else 0
    }
    data class Success(val freshlyDownloaded: Boolean) : BaseSyncStatus
    data object Failed : BaseSyncStatus
}
