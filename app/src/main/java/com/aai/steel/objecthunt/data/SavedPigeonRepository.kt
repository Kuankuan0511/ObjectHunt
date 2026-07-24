package com.aai.steel.objecthunt.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.aai.steel.objecthunt.PigeonDetectionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Repository for saved pigeon hunts - handles conversion and max 20 limit.
 * ioDispatcher injectable so tests can run on TestDispatcher (avoid real IO pool not seen by advanceUntilIdle).
 */
class SavedPigeonRepository(
    private val dao: PigeonDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val saveMutex = Mutex()

    fun getSavedPigeonsFlow(): Flow<List<PigeonEntity>> = dao.getAllFlow()
    suspend fun getSavedPigeons(): List<PigeonEntity> = dao.getAll()
    suspend fun getCount(): Int = dao.count()
    suspend fun deleteById(id: Long) = dao.deleteById(id)
    suspend fun deleteAll() = dao.deleteAll()
    suspend fun getByHash(hash: String): PigeonEntity? = dao.getByHash(hash)

    sealed class SaveResult {
        data class Saved(val id: Long) : SaveResult()
        data class AlreadyExists(val existingId: Long) : SaveResult()
    }

    /**
     * Save with duplicate check via SHA-256 hash.
     * Only bitmap compress/hash uses ioDispatcher (heavy), DAO calls are direct and main-safe via allowMainThreadQueries in tests.
     * withContext outer, withLock inner avoids holding mutex across dispatcher switch (deadlock with testDispatcher).
     */
    suspend fun savePigeon(
        bitmap: Bitmap,
        result: PigeonDetectionResult?,
        city: String?
    ): SaveResult = withContext(ioDispatcher) {
        saveMutex.withLock {
            val imageBytes = bitmapToByteArray(bitmap)
            val hash = sha256(imageBytes)

            val existing = dao.getByHash(hash)
            if (existing != null) {
                Log.d("SavedPigeonRepo", "Duplicate hash=$hash id=${existing.id}")
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

            val insertedId = dao.insertWithLimit(entity, limit = 20)
            Log.d("SavedPigeonRepo", "Saved id=$insertedId hash=$hash total=${dao.count()}")
            SaveResult.Saved(insertedId)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val maxDimension = 1024
        val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val ratio = minOf(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        } else bitmap
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
