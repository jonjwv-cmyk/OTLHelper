package com.example.otlhelper.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cached_feed_items",
    indices = [
        Index(value = ["scope", "item_id"], unique = true),
        Index(value = ["scope", "sort_key"])
    ]
)
data class FeedItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "scope") val scope: String = "",
    @ColumnInfo(name = "item_id") val itemId: String = "",
    @ColumnInfo(name = "kind") val kind: String = "",
    @ColumnInfo(name = "sort_key") val sortKey: String = "",
    @ColumnInfo(name = "payload_json") val payloadJson: String = "{}",
    @ColumnInfo(name = "updated_at") val updatedAt: String = ""
)
