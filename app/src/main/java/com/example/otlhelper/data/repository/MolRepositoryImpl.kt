package com.example.otlhelper.data.repository

import com.example.otlhelper.data.db.dao.BaseMetaDao
import com.example.otlhelper.data.db.dao.MolRecordDao
import com.example.otlhelper.data.db.entity.BaseMetaEntity
import com.example.otlhelper.data.db.entity.MolRecordEntity
import com.example.otlhelper.domain.model.MolRecord
import javax.inject.Inject

class MolRepositoryImpl @Inject constructor(
    private val molRecordDao: MolRecordDao,
    private val baseMetaDao: BaseMetaDao
) : MolRepository {

    override suspend fun getLocalVersion(): String = baseMetaDao.getVersion() ?: ""
    override suspend fun getLocalUpdatedAt(): String = baseMetaDao.getUpdatedAt() ?: ""
    override suspend fun count(): Int = molRecordDao.count()
    override suspend fun hasLocalBase(): Boolean = count() > 0

    override suspend fun replaceAll(records: List<MolRecord>) {
        molRecordDao.deleteAll()
        molRecordDao.insertAll(records.map { it.toEntity() })
    }

    override suspend fun replaceAllIfComplete(
        records: List<MolRecord>,
        minimumExpectedCount: Int,
    ): Boolean {
        if (records.size < minimumExpectedCount) return false
        molRecordDao.deleteAll()
        molRecordDao.insertAll(records.map { it.toEntity() })
        return true
    }

    override suspend fun beginIncrementalSync() {
        molRecordDao.deleteAll()
    }

    override suspend fun appendChunk(records: List<MolRecord>) {
        if (records.isEmpty()) return
        molRecordDao.insertAll(records.map { it.toEntity() })
    }

    override suspend fun saveMeta(version: String, updatedAt: String) {
        baseMetaDao.deleteAll()
        baseMetaDao.insert(BaseMetaEntity(baseVersion = version, baseUpdatedAt = updatedAt))
    }

    override suspend fun search(query: String): List<MolRecord> =
        molRecordDao.search("%${query.lowercase()}%").map { it.toDomain() }

    override suspend fun searchByWarehouse(query: String): List<MolRecord> =
        molRecordDao.searchByWarehouseId("%${query.lowercase()}%").map { it.toDomain() }

    override suspend fun searchByPhone(query: String): List<MolRecord> {
        val digits = query.filter { it.isDigit() }
        if (digits.isBlank()) return emptyList()
        val variants = buildSet {
            add(digits)
            if (digits.length == 11 && digits.startsWith("8")) add(digits.drop(1))
            if (digits.length == 11 && digits.startsWith("7")) add(digits.drop(1))
            if (digits.length == 10) {
                add("8$digits")
                add("7$digits")
            }
        }.toList()

        // Pass up to 3 variants; pad with the first if fewer
        val q1 = "%${variants.getOrElse(0) { digits }}%"
        val q2 = "%${variants.getOrElse(1) { digits }}%"
        val q3 = "%${variants.getOrElse(2) { digits }}%"
        return molRecordDao.searchByPhone(q1, q2, q3).map { it.toDomain() }
    }

    override suspend fun searchByMail(query: String): List<MolRecord> =
        molRecordDao.searchByMail("%${query.lowercase()}%").map { it.toDomain() }

    override suspend fun searchByFio(query: String): List<MolRecord> =
        molRecordDao.searchByFio(
            lowerQuery = "%${query.lowercase()}%",
            upperQuery = "%${query.uppercase()}%",
            rawQuery = "%$query%"
        ).map { it.toDomain() }
}

private fun MolRecord.toEntity() = MolRecordEntity(
    remoteId = remoteId,
    warehouseId = warehouseId,
    warehouseName = warehouseName,
    warehouseDesc = warehouseDesc,
    warehouseMark = warehouseMark,
    warehouseKeeper = warehouseKeeper,
    warehouseWorkPhones = warehouseWorkPhones,
    fio = fio,
    status = status,
    position = position,
    mobile = mobile,
    work = work,
    mail = mail,
    tab = tab,
    searchText = searchText,
    createdAt = createdAt
)

private fun MolRecordEntity.toDomain() = MolRecord(
    remoteId = remoteId,
    warehouseId = warehouseId,
    warehouseName = warehouseName,
    warehouseDesc = warehouseDesc,
    warehouseMark = warehouseMark,
    warehouseKeeper = warehouseKeeper,
    warehouseWorkPhones = warehouseWorkPhones,
    fio = fio,
    status = status,
    position = position,
    mobile = mobile,
    work = work,
    mail = mail,
    tab = tab,
    searchText = searchText,
    createdAt = createdAt
)
