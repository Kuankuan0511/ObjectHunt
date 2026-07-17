package com.aai.steel.objecthunt.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.aai.steel.objecthunt.PigeonDetectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Repository for saved pigeon hunts - handles conversion from UI state to Entity
 * and enforces max 20 limit via DAO.
 */
class SavedPigeonRepository(
    private val dao: PigeonDao
) {
    // Mutex to prevent concurrent saves racing past 20 limit - fixes concurrency bug
    private val saveMutex = Mutex()

    fun getSavedPigeonsFlow(): Flow<List<PigeonEntity>> = dao.getAllFlow()

    suspend fun getSavedPigeons(): List<PigeonEntity> = dao.getAll()

    suspend fun getCount(): Int = dao.count()

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()

    sealed class SaveResult {
        data class Saved(val id: Long) : SaveResult()
        data class AlreadyExists(val existingId: Long) : SaveResult()
    }

    /**
     * Save current hunt: bitmap + detection result + city
     * Returns Saved with new id, or AlreadyExists if same image already saved
     * Duplicate detection via SHA-256 hash of imageBytes
     * Thread-safe via Mutex to prevent race past 20 limit
     */
    suspend fun savePigeon(
        bitmap: Bitmap,
        result: PigeonDetectionResult?,
        city: String?
    ): SaveResult = saveMutex.withLock {
        withContext(Dispatchers.IO) {
            val imageBytes = bitmapToByteArray(bitmap)
            val hash = sha256(imageBytes)

            // Check duplicate
            val existing = dao.getByHash(hash)
            if (existing != null) {
                Log.d("SavedPigeonRepo", "Duplicate image detected, hash=$hash, existingId=${existing.id}")
                return@withContext SaveResult.AlreadyExists(existing.id)
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

            // Enforces max 20 - deletes oldest if needed
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
