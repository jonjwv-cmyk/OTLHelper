package com.example.otlhelper.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "base_records")
data class MolRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "remote_id") val remoteId: Long = 0,
    @ColumnInfo(name = "warehouse_id") val warehouseId: String = "",
    @ColumnInfo(name = "warehouse_name") val warehouseName: String = "",
    @ColumnInfo(name = "warehouse_desc") val warehouseDesc: String = "",
    @ColumnInfo(name = "warehouse_mark") val warehouseMark: String = "",
    @ColumnInfo(name = "warehouse_keeper") val warehouseKeeper: String = "",
    @ColumnInfo(name = "warehouse_work_phones") val warehouseWorkPhones: String = "",
    @ColumnInfo(name = "fio") val fio: String = "",
    @ColumnInfo(name = "status") val status: String = "",
    @ColumnInfo(name = "position") val position: String = "",
    @ColumnInfo(name = "mobile") val mobile: String = "",
    @ColumnInfo(name = "work") val work: String = "",
    @ColumnInfo(name = "mail") val mail: String = "",
    @ColumnInfo(name = "tab") val tab: String = "",
    @ColumnInfo(name = "search_text") val searchText: String = "",
    @ColumnInfo(name = "created_at") val createdAt: String = ""
)
