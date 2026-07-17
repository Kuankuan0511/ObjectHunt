package com.aai.steel.objecthunt.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QueuedDetectionDao {

    @Query("SELECT * FROM queued_detections ORDER BY timestamp ASC")
    fun getAllFlow(): Flow<List<QueuedDetectionEntity>>

    @Query("SELECT * FROM queued_detections ORDER BY timestamp ASC")
    suspend fun getAll(): List<QueuedDetectionEntity>

    @Query("SELECT * FROM queued_detections WHERE nextRetryAt <= :now AND status != 'FAILED' ORDER BY timestamp ASC")
    suspend fun getReadyToRetry(now: Long = System.currentTimeMillis()): List<QueuedDetectionEntity>

    @Query("SELECT COUNT(*) FROM queued_detections")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: QueuedDetectionEntity): Long

    @Query("DELETE FROM queued_detections WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM queued_detections")
    suspend fun deleteAll()

    @Query("UPDATE queued_detections SET retryCount = :retryCount, nextRetryAt = :nextRetryAt, status = :status WHERE id = :id")
    suspend fun updateRetry(id: Long, retryCount: Int, nextRetryAt: Long, status: String)
}
