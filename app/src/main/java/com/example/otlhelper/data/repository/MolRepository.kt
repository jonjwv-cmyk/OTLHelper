package com.example.otlhelper.data.repository

import com.example.otlhelper.domain.model.MolRecord
import org.json.JSONArray
import org.json.JSONObject

interface MolRepository {
    suspend fun getLocalVersion(): String
    suspend fun getLocalUpdatedAt(): String
    suspend fun count(): Int
    suspend fun hasLocalBase(): Boolean
    suspend fun replaceAll(records: List<MolRecord>)

    /**
     * Атомарная замена только если `records.size >= minimumExpectedCount`.
     * Используется `BaseSyncWorker` — страхует от замены полной базы пустой
     * или куцей, если в середине что-то пошло не так и дошла только часть.
     * Возвращает true = заменили, false = proto-защитили.
     */
    suspend fun replaceAllIfComplete(records: List<MolRecord>, minimumExpectedCount: Int): Boolean

    /**
     * Incremental-режим для `BaseSyncWorker`:
     *  - [beginIncrementalSync] чистит таблицу один раз на старте (если юзер
     *    хочет — ниже фича-флаг).
     *  - [appendChunk] добавляет батч, поиск по этой частичной базе
     *    мгновенно начинает работать.
     * Так юзер не ждёт полной загрузки, а работает уже через пару секунд
     * с тем, что есть в первых чанках.
     */
    suspend fun beginIncrementalSync()
    suspend fun appendChunk(records: List<MolRecord>)

    suspend fun saveMeta(version: String, updatedAt: String)
    suspend fun search(query: String): List<MolRecord>
    suspend fun searchByWarehouse(query: String): List<MolRecord>
    suspend fun searchByPhone(query: String): List<MolRecord>
    suspend fun searchByMail(query: String): List<MolRecord>
    suspend fun searchByFio(query: String): List<MolRecord>
}
