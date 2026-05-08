package com.example.otlhelper.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.otlhelper.data.db.entity.PendingActionEntity

@Dao
interface PendingActionDao {

    @Query(
        """
        SELECT * FROM pending_actions
        WHERE status = 'pending'
        ORDER BY created_at ASC, id ASC
        """
    )
    suspend fun getPending(): List<PendingActionEntity>

    @Query(
        """
        SELECT id FROM pending_actions
        WHERE action_type = :actionType
          AND entity_key = :entityKey
          AND payload_json = :payloadJson
          AND status = 'pending'
        ORDER BY id DESC
        LIMIT 1
        """
    )
    suspend fun findExisting(actionType: String, entityKey: String, payloadJson: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(action: PendingActionEntity): Long

    @Query("DELETE FROM pending_actions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        UPDATE pending_actions
        SET retry_count = retry_count + 1, updated_at = :now
        WHERE id = :id
        """
    )
    suspend fun incrementRetry(id: Long, now: String)
}
