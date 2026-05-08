package com.example.otlhelper.data.pending

import com.example.otlhelper.data.db.dao.PendingActionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SF-2026 §4.2: единый loop обработки оффлайн-очереди.
 *
 * Заменяет switch-по-строкам внутри ViewModel'ей: читает все ряды со статусом
 * `pending`, пересоздаёт [PendingAction] из записи и вызывает `execute()`.
 *
 * Контракт вызовов:
 * - [onActionDone] вызывается после каждого успешно отправленного действия.
 *   ViewModel использует это для решения «нужна ли перезагрузка ленты / чата».
 * - Запросы выполняются **последовательно** (FIFO по `created_at`) — сохраняется
 *   порядок событий, важный для чатов и голосований.
 * - Неудача → увеличение retry_count, запись остаётся в очереди.
 * - `Unknown` actionType (устаревший тип) → моментально дропается, чтобы не
 *   спамить retry навсегда (§3.15.a.З graceful degradation).
 */
@Singleton
class PendingActionFlusher @Inject constructor(
    private val pendingActionDao: PendingActionDao
) {

    data class FlushResult(
        val done: Int,
        val failed: Int,
        val dropped: Int,
        val total: Int
    )

    suspend fun flush(onActionDone: (PendingAction) -> Unit = {}): FlushResult {
        val entities = pendingActionDao.getPending()
        if (entities.isEmpty()) return FlushResult(0, 0, 0, 0)

        var done = 0
        var failed = 0
        var dropped = 0

        for (entity in entities) {
            val payload = try {
                JSONObject(entity.payloadJson)
            } catch (e: Exception) {
                // Corrupted JSON — drop. Retry бессмысленен. Однако сам факт
                // коррупции — это баг: значит кто-то при enqueue записал
                // битый JSON. Логируем action + первые 80 символов payload
                // для диагностики. Приватность: до `take(80)` чтобы точно
                // не утащить весь текст сообщения.
                android.util.Log.w(
                    "PendingFlusher",
                    "corrupted payload action=${entity.actionType} id=${entity.id} " +
                        "prefix=${entity.payloadJson.take(80)}",
                    e,
                )
                pendingActionDao.deleteById(entity.id)
                dropped++
                continue
            }

            val action = PendingAction.fromRecord(entity.actionType, payload)
            if (action is PendingAction.Unknown) {
                // Неизвестный action — дроп, не спамим retry.
                pendingActionDao.deleteById(entity.id)
                dropped++
                continue
            }

            try {
                val result = withContext(Dispatchers.IO) { action.execute() }
                if (result.optBoolean("ok", false)) {
                    pendingActionDao.deleteById(entity.id)
                    onActionDone(action)
                    done++
                } else if (result.optString("error", "") == "replay_detected") {
                    // §TZ-2.4.0 — server already accepted the same signature
                    // (we duplicated, or first attempt succeeded server-side
                    // but socket dropped before response). Drop, never retry.
                    // Без этого PendingFlusher крутит log_activity бесконечно
                    // (наблюдалось у Egor_L: 40 replay_detected за неделю).
                    android.util.Log.i(
                        "PendingFlusher",
                        "drop action=${entity.actionType} id=${entity.id} reason=replay_detected"
                    )
                    pendingActionDao.deleteById(entity.id)
                    dropped++
                } else {
                    pendingActionDao.incrementRetry(entity.id, nowDb())
                    failed++
                }
            } catch (_: Exception) {
                pendingActionDao.incrementRetry(entity.id, nowDb())
                failed++
            }
        }
        return FlushResult(done = done, failed = failed, dropped = dropped, total = entities.size)
    }

    private fun nowDb(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
