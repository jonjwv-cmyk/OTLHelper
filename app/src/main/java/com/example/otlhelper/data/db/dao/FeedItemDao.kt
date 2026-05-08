package com.example.otlhelper.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.otlhelper.data.db.entity.FeedItemEntity

@Dao
interface FeedItemDao {

    @Query(
        """
        SELECT * FROM cached_feed_items
        WHERE scope = :scope
        ORDER BY sort_key ASC, id ASC
        """
    )
    suspend fun getByScope(scope: String): List<FeedItemEntity>

    @Query("DELETE FROM cached_feed_items WHERE scope = :scope")
    suspend fun deleteByScope(scope: String)

    /**
     * §TZ-2.3.36 retention policy — удаляет кеш feed-items с updated_at < cutoff.
     * Снижает объём локального кеша на устройстве (forensics-resistance: меньше
     * хранится → меньше можно извлечь при компрометации).
     */
    @Query("DELETE FROM cached_feed_items WHERE updated_at < :cutoffIso")
    suspend fun deleteOlderThan(cutoffIso: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<FeedItemEntity>)
}
