package com.aai.steel.objecthunt.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.aai.steel.objecthunt.PigeonDetectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Repository for saved pigeon hunts - handles conversion from UI state to Entity
 * and enforces max 20 limit via DAO.
 * Handles concurrency via Mutex to prevent race on count+delete+insert.
 * ioDispatcher is injectable so tests can run work on TestDispatcher.
 */
class SavedPigeonRepository(
    private val dao: PigeonDao,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    private val saveMutex = kotlinx.coroutines.sync.Mutex()

    fun getSavedPigeonsFlow(): Flow<List<PigeonEntity>> = dao.getAllFlow()

    suspend fun getSavedPigeons(): List<PigeonEntity> = withContext(ioDispatcher) { dao.getAll() }

    suspend fun getCount(): Int = withContext(ioDispatcher) { dao.count() }

    suspend fun deleteById(id: Long) = withContext(ioDispatcher) { dao.deleteById(id) }

    suspend fun deleteAll() = withContext(ioDispatcher) { dao.deleteAll() }

    suspend fun getByHash(hash: String): PigeonEntity? = withContext(ioDispatcher) { dao.getByHash(hash) }

    sealed class SaveResult {
        data class Saved(val id: Long) : SaveResult()
        data class AlreadyExists(val existingId: Long) : SaveResult()
    }

    /**
     * Save current hunt: bitmap + detection result + city
     * Returns Saved with new id, or AlreadyExists if same image already saved
     * Duplicate detection via SHA-256 hash of imageBytes
     * Thread-safe via Mutex to prevent double-save race and limit overrun
     */
    suspend fun savePigeon(
        bitmap: Bitmap,
        result: PigeonDetectionResult?,
        city: String?
    ): SaveResult = withContext(ioDispatcher) {
        saveMutex.withLock {
            val imageBytes = bitmapToByteArray(bitmap)
            val hash = sha256(imageBytes)

            // Check duplicate - inside mutex so concurrent double-tap can't insert twice
            val existing = dao.getByHash(hash)
            if (existing != null) {
                Log.d("SavedPigeonRepo", "Duplicate image detected, hash=$hash, existingId=${existing.id}")
                return@withLock SaveResult.AlreadyExists(existing.id)
            }

            val entity = PigeonEntity(
                timestamp = System.currentTimeMillis(),
                pigeonType = result?.pigeonType,
                confidence = result?.confidence ?: 0f,
                features = result?.features,
                pigeonLocationInImage = result?.location,
                city = city,
                description = result?.description ?: "No analysis",
                rawResponse = result?.rawResponse ?: "",
                imageBytes = imageBytes,
                imageHash = hash
            )

            // Enforces max 20 - deletes oldest if needed (atomic via @Transaction + Mutex)
            val insertedId = dao.insertWithLimit(entity, limit = 20)
            Log.d("SavedPigeonRepo", "Saved pigeon id=$insertedId, hash=$hash, city=$city, type=${result?.pigeonType}, total=${dao.count()}")
            SaveResult.Saved(insertedId)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Reuse same resize logic as PigeonRepository: max 1024px, JPEG 80%
     */
    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val maxDimension = 1024
        val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val ratio = minOf(
                maxDimension.toFloat() / bitmap.width,
                maxDimension.toFloat() / bitmap.height
            )
            val newWidth = (bitmap.width * ratio).toInt()
            val newHeight = (bitmap.height * ratio).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return outputStream.toByteArray()
    }

    companion object {
        fun fromContext(context: Context): SavedPigeonRepository {
            val db = PigeonDatabase.getInstance(context.applicationContext)
            return SavedPigeonRepository(db.pigeonDao())
        }

        fun inMemoryForTest(context: Context): SavedPigeonRepository {
            val db = PigeonDatabase.getInMemoryInstance(context)
            return SavedPigeonRepository(db.pigeonDao())
        }
    }
}
