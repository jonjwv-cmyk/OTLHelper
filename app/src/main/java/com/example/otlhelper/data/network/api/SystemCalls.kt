package com.example.otlhelper.data.network.api

import com.example.otlhelper.shared.api.ApiActions
import com.example.otlhelper.shared.api.ApiFields
import org.json.JSONObject

/**
 * §TZ-CLEANUP-2026-04-25 — system/base zone:
 *   • base МОЛ snapshot (`getBaseVersion`, `getBaseDownloadUrl`, `downloadBase`);
 *   • avatar upload (`uploadAvatar` → `set_avatar`).
 *
 * `getSystemState` / `setAppPause` / `clearAppPause` остаются пока в
 * facade — они тесно связаны с admin-zone (который ещё не вынесен) и
 * перейдут вместе с AdminCalls в следующей итерации.
 *
 * Реализация — в [SystemCallsImpl].
 */
interface SystemCalls {
    fun getBaseVersion(): JSONObject

    /**
     * Возвращает URL gzipped-снэпшота МОЛ в R2 (новый путь с 2.3.0).
     * Клиент качает через Android DownloadManager — системный сервис, который
     * OEM-killer'ы батареи не могут убить. URL непредсказуемый (random hex
     * token в имени файла).
     *
     * Ответ: `{ok, data: {url, version, updated_at}}`.
     */
    fun getBaseDownloadUrl(): JSONObject

    fun downloadBase(): JSONObject

    /**
     * Постраничная загрузка справочника МОЛ. Используется `BaseSyncWorker` для
     * надёжной загрузки через адаптивно-меньшающиеся чанки — лёгкий пакет
     * проходит даже через нестабильный VPN/прокси/Каспер, а мы потом
     * атомарно заменяем всё локально.
     *
     * Сервер возвращает `{ok, data[], total, has_more, version, updated_at}`.
     */
    fun downloadBase(offset: Int, limit: Int): JSONObject

    /**
     * Upload avatar image to R2.
     * @param dataUrl base64 data URL — "data:image/jpeg;base64,..."
     */
    fun uploadAvatar(dataUrl: String, mimeType: String, fileName: String): JSONObject
}

internal class SystemCallsImpl(private val gateway: ApiGateway) : SystemCalls {

    override fun getBaseVersion(): JSONObject =
        gateway.request(ApiActions.BASE_VERSION)

    override fun getBaseDownloadUrl(): JSONObject =
        gateway.request(ApiActions.BASE_DOWNLOAD_URL)

    override fun downloadBase(): JSONObject =
        gateway.request(ApiActions.BASE_DOWNLOAD)

    override fun downloadBase(offset: Int, limit: Int): JSONObject =
        gateway.request(ApiActions.BASE_DOWNLOAD) {
            put(ApiFields.OFFSET, offset)
            put(ApiFields.LIMIT, limit)
        }

    override fun uploadAvatar(dataUrl: String, mimeType: String, fileName: String): JSONObject =
        gateway.request(ApiActions.SET_AVATAR) {
            put(ApiFields.DATA_URL, dataUrl)
            put(ApiFields.MIME_TYPE, mimeType)
            put(ApiFields.FILE_NAME, fileName)
        }
}
