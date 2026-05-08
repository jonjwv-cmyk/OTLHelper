package com.example.otlhelper.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.otlhelper.data.db.entity.BaseMetaEntity

@Dao
interface BaseMetaDao {

    @Query("SELECT base_version FROM base_meta_local ORDER BY id DESC LIMIT 1")
    suspend fun getVersion(): String?

    @Query("SELECT base_updated_at FROM base_meta_local ORDER BY id DESC LIMIT 1")
    suspend fun getUpdatedAt(): String?

    @Query("DELETE FROM base_meta_local")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(meta: BaseMetaEntity)
}
