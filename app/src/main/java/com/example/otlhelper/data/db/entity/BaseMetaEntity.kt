package com.example.otlhelper.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "base_meta_local")
data class BaseMetaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "base_version") val baseVersion: String = "",
    @ColumnInfo(name = "base_updated_at") val baseUpdatedAt: String = ""
)
