package com.aai.steel.objecthunt.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PigeonDao {

    @Query("SELECT * FROM saved_pigeons ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<PigeonEntity>>

    @Query("SELECT * FROM saved_pigeons ORDER BY timestamp DESC")
    suspend fun getAll(): List<PigeonEntity>

    @Query("SELECT COUNT(*) FROM saved_pigeons")
    suspend fun count(): Int

    @Query("SELECT * FROM saved_pigeons ORDER BY timestamp ASC LIMIT 1")
    suspend fun getOldest(): PigeonEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PigeonEntity): Long

    @Query("DELETE FROM saved_pigeons WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM saved_pigeons")
    suspend fun deleteAll()

    @Query("SELECT * FROM saved_pigeons WHERE imageHash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): PigeonEntity?

    /**
     * Delete N oldest entries based on timestamp ASC
     */
    @Query("DELETE FROM saved_pigeons WHERE id IN (SELECT id FROM saved_pigeons ORDER BY timestamp ASC LIMIT :n)")
    suspend fun deleteOldestN(n: Int)

    /**
     * Insert with limit enforcement: max 20, delete oldest if exceeds
     */
    @Transaction
    suspend fun insertWithLimit(entity: PigeonEntity, limit: Int = 20): Long {
        val cnt = count()
        if (cnt >= limit) {
            // Make room for 1 new entry
            val toDelete = cnt - limit + 1
            deleteOldestN(toDelete)
        }
        return insert(entity)
    }
}
