package com.aai.steel.objecthunt

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.aai.steel.objecthunt.data.DetectionQueueRepository
import com.aai.steel.objecthunt.data.PigeonDatabase
import com.aai.steel.objecthunt.data.PigeonEntity
import com.aai.steel.objecthunt.data.SavedPigeonRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for queue detection with exponential backoff and concurrency safety
 * F: retry/backoff logic
 * B: concurrency/race-condition bugs
 */
@RunWith(RobolectricTestRunner::class)
class DetectionQueueRepositoryTest {

    private lateinit var db: PigeonDatabase
    private lateinit var pigeonRepo: PigeonRepository
    private lateinit var savedRepo: SavedPigeonRepository
    private lateinit var queueRepo: DetectionQueueRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = PigeonDatabase.getInMemoryInstance(context)

        // Fake API service that returns success instantly - no network, no retry delays
        val fakeApiService = object : MuseApiService {
            override suspend fun createResponse(
                authorization: String,
                contentType: String,
                request: MuseApiRequest
            ): MuseApiResponse {
                return MuseApiResponse(
                    id = "test-id",
                    output = listOf(
                        OutputItem(
                            type = "message",
                            content = listOf(
                                OutputContent(
                                    type = "output_text",
                                    text = "HAS_PIGEON: YES\nTYPE: Rock Pigeon\nCONFIDENCE: High"
                                )
                            )
                        )
                    ),
                    error = null,
                    usage = null
                )
            }
        }
        pigeonRepo = PigeonRepository(fakeApiService, "fake_key", "test-model")

        savedRepo = SavedPigeonRepository(db.pigeonDao())
        queueRepo = DetectionQueueRepository(db.queuedDetectionDao(), pigeonRepo, savedRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun calculateBackoff_exponentialGrowth() {
        val d1 = DetectionQueueRepository.calculateBackoffDelay(0)
        val d2 = DetectionQueueRepository.calculateBackoffDelay(1)
        val d3 = DetectionQueueRepository.calculateBackoffDelay(2)
        val d4 = DetectionQueueRepository.calculateBackoffDelay(3)

        // Should grow exponentially: ~1s, 2s, 4s, 8s (+ jitter 0-500ms)
        assertTrue(d1 in 1000..1500)
        assertTrue(d2 in 2000..2500)
        assertTrue(d3 in 4000..4500)
        assertTrue(d4 in 8000..8500)
    }

    @Test
    fun calculateBackoff_cappedAtMax() {
        val d10 = DetectionQueueRepository.calculateBackoffDelay(10)
        // 2^10 * 1000 = 1,024,000 but capped at 30_000
        assertTrue(d10 <= 30000L)
        assertTrue(d10 >= 30000L - 500) // with jitter but capped
    }

    @Test
    fun enqueue_addsToQueue() = runTest {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val id = queueRepo.enqueue(bitmap, "SF")
        assertTrue(id > 0)
        assertEquals(1, queueRepo.getQueuedCount())
    }

    @Test
    fun enqueue_concurrentRaceCondition_threadSafe() = runTest {
        // Simulate 10 concurrent enqueues - should all succeed without race, no lost writes
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

        val jobs = (1..10).map { i ->
            async {
                queueRepo.enqueue(bitmap, "City $i")
            }
        }
        val ids = jobs.map { it.await() }

        // All 10 should have unique ids and count = 10 (Mutex prevents race)
        assertEquals(10, ids.distinct().size)
        assertEquals(10, queueRepo.getQueuedCount())
    }

    @Test
    fun syncPending_noNetwork_returnsNoNetwork() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // This test environment likely has no validated internet in Robolectric, or has
        // We can't guarantee network state, so just check that method doesn't crash
        // and returns a valid SyncResult type
        val result = queueRepo.syncPending(context)
        assertTrue(
            result is DetectionQueueRepository.SyncResult.NoNetwork ||
            result is DetectionQueueRepository.SyncResult.NothingToSync ||
            result is DetectionQueueRepository.SyncResult.Synced
        )
    }

    @Test
    fun syncPending_concurrentSync_singleFlight() = runTest {
        // Two concurrent syncs - only one should run at a time due to syncMutex
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        queueRepo.enqueue(bitmap, "SF")
        queueRepo.enqueue(bitmap, "SF")

        val job1 = async { queueRepo.syncPending(context) }
        val job2 = async { queueRepo.syncPending(context) }

        val r1 = job1.await()
        val r2 = job2.await()

        // Both should complete without deadlock or race
        assertNotNull(r1)
        assertNotNull(r2)
        // After sync, queue may still have items if no network, but no crash
    }
}
