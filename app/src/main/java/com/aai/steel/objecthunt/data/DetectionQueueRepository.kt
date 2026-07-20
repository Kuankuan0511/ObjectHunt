package com.aai.steel.objecthunt.data

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.aai.steel.objecthunt.PigeonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.pow
import kotlin.random.Random

/**
 * Queue for detection requests when network is down.
 * Handles:
 * - F: retry/backoff logic (exponential backoff with jitter)
 * - B: concurrency/race-condition bugs (Mutex, single-flight sync)
 *
 * Usage:
 * - When detectPigeon fails due to network, call enqueue(bitmap, city)
 * - When connectivity restored, call syncPending() which retries with backoff
 */
class DetectionQueueRepository(
    private val queuedDao: QueuedDetectionDao,
    private val pigeonRepository: PigeonRepository,
    private val savedRepository: SavedPigeonRepository
) {
    // Mutex to protect concurrent access to queue - prevents race conditions
    private val queueMutex = Mutex()
    private val syncMutex = Mutex() // separate mutex for sync to allow enqueue during sync

    companion object {
        const val MAX_RETRY = 5
        const val BASE_DELAY_MS = 1000L // 1s
        const val MAX_DELAY_MS = 30_000L // 30s cap

        fun fromContext(context: Context, pigeonRepo: PigeonRepository): DetectionQueueRepository {
            val db = PigeonDatabase.getInstance(context.applicationContext)
            val savedRepo = SavedPigeonRepository.fromContext(context)
            return DetectionQueueRepository(db.queuedDetectionDao(), pigeonRepo, savedRepo)
        }

        fun inMemoryForTest(
            context: Context,
            pigeonRepo: PigeonRepository,
            savedRepo: SavedPigeonRepository
        ): DetectionQueueRepository {
            val db = PigeonDatabase.getInMemoryInstance(context)
            return DetectionQueueRepository(db.queuedDetectionDao(), pigeonRepo, savedRepo)
        }

        /**
         * Exponential backoff with jitter: delay = min(BASE * 2^attempt + jitter, MAX)
         */
        fun calculateBackoffDelay(attempt: Int): Long {
            val exponential = BASE_DELAY_MS * 2.0.pow(attempt.toDouble())
            val jitter = Random.nextLong(0, 500) // 0-500ms jitter to avoid thundering herd
            return minOf((exponential + jitter).toLong(), MAX_DELAY_MS)
        }

        fun isNetworkAvailable(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val network = cm.activeNetwork ?: return false
                    val caps = cm.getNetworkCapabilities(network) ?: return false
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                } else {
                    @Suppress("DEPRECATION")
                    cm.activeNetworkInfo?.isConnected == true
                }
            } catch (e: Exception) {
                // Robolectric fallback: NoSuchMethodError for getActiveNetwork() in older shadow
                try {
                    @Suppress("DEPRECATION")
                    cm.activeNetworkInfo?.isConnected == true
                } catch (e2: Exception) {
                    false
                }
            }
        }
    }

    fun getQueuedFlow(): Flow<List<QueuedDetectionEntity>> = queuedDao.getAllFlow()

    suspend fun getQueuedCount(): Int = queuedDao.count()

    suspend fun getAllQueued(): List<QueuedDetectionEntity> = queuedDao.getAll()

    // Exposed for ViewModel auto-retry calc and tests - allows getting DAO for advanced queries
    fun getQueuedDao(): QueuedDetectionDao = queuedDao

    /**
     * Enqueue a detection request when network is down.
     * Thread-safe via Mutex to prevent race conditions with concurrent enqueues.
     */
    suspend fun enqueue(bitmap: Bitmap, city: String?): Long = queueMutex.withLock {
        withContext(Dispatchers.IO) {
            val imageBytes = bitmapToBytes(bitmap)
            val entity = QueuedDetectionEntity(
                timestamp = System.currentTimeMillis(),
                city = city,
                imageBytes = imageBytes,
                retryCount = 0,
                nextRetryAt = System.currentTimeMillis(),
                status = "PENDING"
            )
            val id = queuedDao.insert(entity)
            Log.d("DetectionQueue", "Enqueued detection id=$id, city=$city, queueSize=${queuedDao.count()}")
            id
        }
    }

    /**
     * Sync pending detections - retry with exponential backoff.
     * Single-flight via syncMutex: only one sync runs at a time.
     * Thread-safe, handles concurrent calls.
     */
    suspend fun syncPending(context: Context): SyncResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isNetworkAvailable(context)) {
                Log.d("DetectionQueue", "No network, skipping sync")
                return@withContext SyncResult.NoNetwork
            }

            val ready = queuedDao.getReadyToRetry()
            if (ready.isEmpty()) {
                Log.d("DetectionQueue", "No queued detections ready")
                return@withContext SyncResult.NothingToSync
            }

            Log.d("DetectionQueue", "Syncing ${ready.size} queued detections")

            var successCount = 0
            var failedCount = 0

            for (queued in ready) {
                try {
                    // Convert bytes back to bitmap
                    val bitmap = bytesToBitmap(queued.imageBytes)
                    if (bitmap == null) {
                        Log.e("DetectionQueue", "Failed to decode bitmap for id=${queued.id}, deleting")
                        queuedDao.deleteById(queued.id)
                        failedCount++
                        continue
                    }

                    // Attempt detection with repository (which has its own retry logic)
                    val result = pigeonRepository.detectPigeon(bitmap)

                    // FIX: Robust error detection - previous code only checked 2 phrases ("No internet", "Failed to analyze")
                    // so timeout, 401, 403, 429, 500/503, invalid-key, model-not-found were wrongly treated as success
                    // and saved as real pigeon then deleted from queue (data loss).
                    // All error results have confidence == 0f and no HAS_PIGEON in description, while success has >=0.1 and contains HAS_PIGEON.
                    val isErrorResult = result.confidence == 0f ||
                            !result.description.contains("HAS_PIGEON:", ignoreCase = true) ||
                            result.description.contains("No internet", ignoreCase = true) ||
                            result.description.contains("Failed to analyze", ignoreCase = true) ||
                            result.description.contains("timeout", ignoreCase = true) ||
                            result.description.contains("Invalid API key", ignoreCase = true) ||
                            result.description.contains("Access denied", ignoreCase = true) ||
                            result.description.contains("Too many requests", ignoreCase = true) ||
                            result.description.contains("Server error", ignoreCase = true) ||
                            result.description.contains("API Error", ignoreCase = true)

                    if (isErrorResult) {
                        // Still failing - schedule retry with backoff
                        handleRetryFailure(queued)
                        failedCount++
                    } else {
                        // Success - save to saved pigeons and remove from queue
                        savedRepository.savePigeon(bitmap, result, queued.city)
                        queuedDao.deleteById(queued.id)
                        successCount++
                        Log.d("DetectionQueue", "Synced queued id=${queued.id} success, type=${result.pigeonType}")
                    }

                } catch (e: Exception) {
                    Log.e("DetectionQueue", "Sync failed for id=${queued.id}", e)
                    handleRetryFailure(queued)
                    failedCount++
                }
            }

            SyncResult.Synced(successCount, failedCount)
        }
    }

    private suspend fun handleRetryFailure(queued: QueuedDetectionEntity) {
        val newRetryCount = queued.retryCount + 1
        if (newRetryCount >= MAX_RETRY) {
            // Mark as failed after max retries
            queuedDao.updateRetry(
                id = queued.id,
                retryCount = newRetryCount,
                nextRetryAt = System.currentTimeMillis() + MAX_DELAY_MS,
                status = "FAILED"
            )
            Log.w("DetectionQueue", "Max retries reached for id=${queued.id}, marking FAILED")
        } else {
            val backoff = calculateBackoffDelay(newRetryCount)
            val nextRetry = System.currentTimeMillis() + backoff
            queuedDao.updateRetry(
                id = queued.id,
                retryCount = newRetryCount,
                nextRetryAt = nextRetry,
                status = "RETRYING"
            )
            Log.d("DetectionQueue", "Scheduled retry for id=${queued.id} attempt $newRetryCount after ${backoff}ms")
        }
    }

    private fun bitmapToBytes(bitmap: Bitmap): ByteArray {
        val maxDim = 1024
        val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        } else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    private fun bytesToBitmap(bytes: ByteArray): Bitmap? {
        return try {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    sealed class SyncResult {
        data class Synced(val success: Int, val failed: Int) : SyncResult()
        object NoNetwork : SyncResult()
        object NothingToSync : SyncResult()
    }
}
