package com.example.otlhelper.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.otlhelper.data.db.entity.MolRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MolRecordDao {

    @Query("SELECT COUNT(*) FROM base_records")
    suspend fun count(): Int

    @Query("DELETE FROM base_records")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<MolRecordEntity>)

    @Query(
        """
        SELECT * FROM base_records
        WHERE lower(search_text) LIKE :query
        ORDER BY fio ASC, warehouse_id ASC
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int = 50): List<MolRecordEntity>

    @Query(
        """
        SELECT * FROM base_records
        WHERE lower(warehouse_id) LIKE :query
        ORDER BY fio ASC, warehouse_id ASC
        LIMIT 50
        """
    )
    suspend fun searchByWarehouseId(query: String): List<MolRecordEntity>

    @Query(
        """
        SELECT * FROM base_records
        WHERE lower(mail) LIKE :query
        ORDER BY fio ASC, warehouse_id ASC
        LIMIT 50
        """
    )
    suspend fun searchByMail(query: String): List<MolRecordEntity>

    @Query(
        """
        SELECT * FROM base_records
        WHERE search_text LIKE :lowerQuery
           OR fio LIKE :upperQuery
           OR fio LIKE :rawQuery
        ORDER BY fio ASC, warehouse_id ASC
        LIMIT 50
        """
    )
    suspend fun searchByFio(lowerQuery: String, upperQuery: String, rawQuery: String): List<MolRecordEntity>

    @Query(
        """
        SELECT * FROM base_records
        WHERE (
            replace(replace(replace(replace(replace(lower(mobile), '+', ''), ' ', ''), '-', ''), '(', ''), ')', '') LIKE :q1
            OR replace(replace(replace(replace(replace(lower(mobile), '+', ''), ' ', ''), '-', ''), '(', ''), ')', '') LIKE :q2
            OR replace(replace(replace(replace(replace(lower(mobile), '+', ''), ' ', ''), '-', ''), '(', ''), ')', '') LIKE :q3
        ) OR (
            replace(replace(replace(replace(replace(lower(work), '+', ''), ' ', ''), '-', ''), '(', ''), ')', '') LIKE :q1
            OR replace(replace(replace(replace(replace(lower(work), '+', ''), ' ', ''), '-', ''), '(', ''), ')', '') LIKE :q2
            OR replace(replace(replace(replace(replace(lower(work), '+', ''), ' ', ''), '-', ''), '(', ''), ')', '') LIKE :q3
        ) OR (
            replace(replace(replace(replace(replace(lower(warehouse_work_phones), '+', ''), ' ', ''), '-', ''), '(', ''), ')', '') LIKE :q1
            OR replace(replace(replace(replace(replace(lower(warehouse_work_phones), '+', ''), ' ', ''), '-', ''), '(', ''), ')', '') LIKE :q2
            OR replace(replace(replace(replace(replace(lower(warehouse_work_phones), '+', ''), ' ', ''), '-', ''), '(', ''), ')', '') LIKE :q3
        )
        ORDER BY fio ASC, warehouse_id ASC
        LIMIT 50
        """
    )
    suspend fun searchByPhone(q1: String, q2: String, q3: String): List<MolRecordEntity>
}
