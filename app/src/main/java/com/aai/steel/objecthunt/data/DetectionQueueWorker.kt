package com.aai.steel.objecthunt.data

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aai.steel.objecthunt.PigeonRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * CoroutineWorker that drains queued_detections in background.
 * Canonical offline-queue approach:
 * - Runs even after process death or reboot (WorkManager persists)
 * - System gates execution via CONNECTED network constraint (no manual connectivity check needed)
 * - Exponential backoff policy via WorkManager (no manual timer)
 */
@HiltWorker
class DetectionQueueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pigeonRepository: PigeonRepository,
    private val savedRepository: SavedPigeonRepository,
    private val queuedDao: QueuedDetectionDao
) : CoroutineWorker(context, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "detection_queue_sync"
        const val TAG = "DetectionQueueWorker"

        fun buildRequest(): androidx.work.OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED) // System gates execution
                .build()

            return OneTimeWorkRequestBuilder<DetectionQueueWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10L, // WorkManager will do 10s, 20s, 40s... exponential
                    TimeUnit.SECONDS
                )
                .addTag("queue-sync")
                .build()
        }

        fun enqueue(context: Context) {
            val request = buildRequest()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                )
            Log.d(TAG, "Enqueued WorkManager sync work with CONNECTED constraint + exponential backoff")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker started, attempt $runAttemptCount")
        return try {
            val queueRepo = DetectionQueueRepository(queuedDao, pigeonRepository, savedRepository)

            // Use the same robust sync logic that has retryCount + nextRetryAt handling
            val syncResult = queueRepo.syncPending(applicationContext)

            when (syncResult) {
                is DetectionQueueRepository.SyncResult.Synced -> {
                    if (syncResult.failed > 0) {
                        val remaining = queuedDao.getAll().filter { it.status != "FAILED" }
                        if (remaining.isNotEmpty()) {
                            val hasRetryable = remaining.any { it.retryCount < DetectionQueueRepository.MAX_RETRY }
                            if (hasRetryable) {
                                Log.d(TAG, "Partial failure: ${syncResult.success} success, ${syncResult.failed} failed - requesting retry with exponential backoff (attempt $runAttemptCount)")
                                // WorkManager will automatically retry with exponential backoff policy
                                Result.retry()
                            } else {
                                Log.d(TAG, "All remaining are FAILED, not retrying")
                                Result.success()
                            }
                        } else {
                            Log.d(TAG, "Sync completed: ${syncResult.success} success")
                            Result.success()
                        }
                    } else {
                        Log.d(TAG, "Sync completed fully: ${syncResult.success} success")
                        Result.success()
                    }
                }
                is DetectionQueueRepository.SyncResult.NoNetwork -> {
                    Log.d(TAG, "No network during worker, retrying")
                    Result.retry()
                }
                is DetectionQueueRepository.SyncResult.NothingToSync -> {
                    Log.d(TAG, "Nothing to sync")
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed", e)
            if (runAttemptCount >= 5) {
                Log.e(TAG, "Max worker retries reached, failing")
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }
}

/**
 * Non-Hilt fallback worker for simplicity when HiltWorkerFactory not configured
 * Uses manual DB creation - works even without Hilt
 */
class DetectionQueueWorkerManual(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("DetectionQueueWorkerManual", "Manual worker started")
        return try {
            val db = PigeonDatabase.getInstance(applicationContext)
            val api = PigeonRepository.createApiService()
            val pigeonRepo = PigeonRepository(api, com.aai.steel.objecthunt.BuildConfig.MUSE_API_KEY, com.aai.steel.objecthunt.BuildConfig.MUSE_API_MODEL)
            val savedRepo = SavedPigeonRepository(db.pigeonDao())
            val queueRepo = DetectionQueueRepository(db.queuedDetectionDao(), pigeonRepo, savedRepo)

            val result = queueRepo.syncPending(applicationContext)
            when (result) {
                is DetectionQueueRepository.SyncResult.Synced -> {
                    if (result.failed > 0 && result.failed < result.success + result.failed) {
                        Result.retry()
                    } else Result.success()
                }
                is DetectionQueueRepository.SyncResult.NoNetwork -> Result.retry()
                else -> Result.success()
            }
        } catch (e: Exception) {
            Log.e("DetectionQueueWorkerManual", "Failed", e)
            Result.retry()
        }
    }

    companion object {
        fun buildRequest(): androidx.work.OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            return OneTimeWorkRequestBuilder<DetectionQueueWorkerManual>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
                .build()
        }

        fun enqueue(context: Context) {
            val request = buildRequest()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    DetectionQueueWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                )
        }
    }
}
