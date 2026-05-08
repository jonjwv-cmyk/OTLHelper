package com.example.otlhelper.data.sync

import android.content.Context
import androidx.core.content.edit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Персистентное состояние загрузки справочника МОЛ.
 *
 * Зачем: если пользователь закрыл приложение или сеть упала посередине
 * скачивания, хотим продолжить с того же места при следующей попытке —
 * а не грузить всю базу заново.
 *
 * SharedPreferences — хватит, данных мало и schema-проще некуда.
 */
@Singleton
class BaseSyncPrefs @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val context: Context,
) {
    private val prefs get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Offset на котором остановилась предыдущая загрузка (0 = с нуля). */
    var pendingOffset: Int
        get() = prefs.getInt(KEY_OFFSET, 0)
        set(v) = prefs.edit { putInt(KEY_OFFSET, v) }

    /** Размер чанка, на котором сейчас работаем (адаптивно уменьшается). */
    var chunkSize: Int
        get() = prefs.getInt(KEY_CHUNK, DEFAULT_CHUNK)
        set(v) = prefs.edit { putInt(KEY_CHUNK, v) }

    /** Версия, которую сейчас тянем (чтобы отловить рассинхрон на пол-пути). */
    var pendingVersion: String
        get() = prefs.getString(KEY_PENDING_VERSION, "").orEmpty()
        set(v) = prefs.edit { putString(KEY_PENDING_VERSION, v) }

    /** Total на старте (для прогресса). */
    var expectedTotal: Int
        get() = prefs.getInt(KEY_TOTAL, 0)
        set(v) = prefs.edit { putInt(KEY_TOTAL, v) }

    /**
     * DownloadManager ID текущего качающегося снэпшота. -1 = нет активной
     * загрузки. С 2.3.1+ база качается через системный DownloadManager;
     * здесь храним id чтобы BaseDownloadReceiver мог сверить какой именно
     * download завершился (их может быть несколько параллельно).
     */
    var pendingDownloadId: Long
        get() = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        set(v) = prefs.edit { putLong(KEY_DOWNLOAD_ID, v) }

    /** Сбросить весь прогресс (после успешного завершения или при смене версии). */
    fun resetProgress() {
        prefs.edit {
            remove(KEY_OFFSET)
            remove(KEY_CHUNK)
            remove(KEY_PENDING_VERSION)
            remove(KEY_TOTAL)
            remove(KEY_DOWNLOAD_ID)
        }
    }

    companion object {
        // Legacy chunked-download defaults — сохранены для fallback-пути
        // (`base_download` с offset/limit), который остаётся на сервере и
        // может вызываться старыми клиентами. Новые клиенты 2.3.1+ используют
        // DownloadManager + R2 snapshot, где чанкование не нужно.
        const val DEFAULT_CHUNK = 2000
        const val MIN_CHUNK = 50
        private const val FILE = "otl_base_sync_prefs"
        private const val KEY_OFFSET = "pending_offset"
        private const val KEY_CHUNK = "chunk_size"
        private const val KEY_PENDING_VERSION = "pending_version"
        private const val KEY_TOTAL = "expected_total"
        private const val KEY_DOWNLOAD_ID = "pending_download_id"
    }
}
