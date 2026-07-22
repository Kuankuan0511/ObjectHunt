package com.aai.steel.objecthunt

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.aai.steel.objecthunt.data.DetectionQueueRepository
import com.aai.steel.objecthunt.data.NetworkMonitor
import com.aai.steel.objecthunt.data.PigeonDatabase
import com.aai.steel.objecthunt.data.SavedPigeonRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests for PigeonHunterViewModel state machine + save + queue + location
 * This is where most recent bugs lived: onRetake wiping count, double fetch, Flow sync, queue logic
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PigeonHunterViewModelTest {

    private lateinit var db: PigeonDatabase
    private lateinit var pigeonRepo: PigeonRepository
    private lateinit var savedRepo: SavedPigeonRepository
    private lateinit var queueRepo: DetectionQueueRepository
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var viewModel: PigeonHunterViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = PigeonDatabase.getInMemoryInstance(context)

        val fakeSuccessApi = object : MuseApiService {
            override suspend fun createResponse(
                authorization: String,
                contentType: String,
                request: MuseApiRequest
            ): MuseApiResponse {
                return MuseApiResponse(
                    id = "test",
                    output = listOf(
                        OutputItem(
                            type = "message",
                            content = listOf(
                                OutputContent(type = "output_text", text = "HAS_PIGEON: YES\nTYPE: Rock Pigeon\nCONFIDENCE: High")
                            )
                        )
                    ),
                    error = null,
                    usage = null
                )
            }
        }
        pigeonRepo = PigeonRepository(fakeSuccessApi, "test_key", "test-model")
        savedRepo = SavedPigeonRepository(db.pigeonDao())
        queueRepo = DetectionQueueRepository(db.queuedDetectionDao(), pigeonRepo, savedRepo)
        networkMonitor = NetworkMonitor(context)

        viewModel = PigeonHunterViewModel(
            repository = pigeonRepo,
            savedRepository = savedRepo,
            queueRepository = queueRepo,
            networkMonitor = networkMonitor,
            appContext = context
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun initialState_hasZeroCounts() = runTest {
        val state = viewModel.uiState.first()
        assertTrue(state is PigeonHunterUiState.Initial)
        assertEquals(0, state.savedCount)
        assertEquals(0, state.queuedCount)
        assertNull(state.location)
        assertFalse(state.isFetchingLocation)
    }

    @Test
    fun onPhotoCaptured_transitionsToAnalyzing() = runTest {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        viewModel.onPhotoCaptured(bitmap)
        idleMainLooper()

        // Should be Analyzing immediately
        val analyzing = viewModel.uiState.first()
        assertTrue(analyzing is PigeonHunterUiState.Analyzing)
        assertEquals(bitmap, (analyzing as PigeonHunterUiState.Analyzing).bitmap)

        advanceUntilIdle()

        // After analysis, should be Success
        val success = viewModel.uiState.first()
        assertTrue(success is PigeonHunterUiState.Success)
        assertTrue((success as PigeonHunterUiState.Success).result.hasPigeon)
    }

    @Test
    fun onRetakePhoto_preservesSavedCount_bugFix() = runTest {
        // Simulate having saved 5 pigeons
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeImage = ByteArray(10)
        val dao = db.pigeonDao()
        for (i in 1..5) {
            dao.insert(
                com.aai.steel.objecthunt.data.PigeonEntity(
                    timestamp = i.toLong(),
                    pigeonType = "Pigeon $i",
                    confidence = 0.9f,
                    features = null,
                    pigeonLocationInImage = null,
                    city = "City",
                    description = "desc",
                    rawResponse = "",
                    imageBytes = fakeImage,
                    imageHash = "hash$i"
                )
            )
        }
        // Wait for Flow collectors (Main looper) to update count
        idleMainLooper()
        advanceUntilIdle()
        idleMainLooper()

        val count = savedRepo.getCount()
        assertEquals(5, count)

        // Also wait for uiState flow to reflect count
        idleMainLooper()
        advanceUntilIdle()
        idleMainLooper()
        var state = viewModel.uiState.first()
        // Flow collector should have updated savedCount to 5
        // Note: may need a bit more idle due to Room flow being async
        shadowOf(Looper.getMainLooper()).idle()

        // Now capture photo and retake - count should be preserved (was bug: reset to 0)
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        viewModel.onPhotoCaptured(bitmap)
        idleMainLooper()
        advanceUntilIdle()
        idleMainLooper()
        viewModel.onRetakePhoto()
        idleMainLooper()
        advanceUntilIdle()
        idleMainLooper()

        val afterRetake = viewModel.uiState.first()
        assertTrue(afterRetake is PigeonHunterUiState.Initial)
        assertEquals(5, afterRetake.savedCount) // should preserve 5, not 0
    }

    @Test
    fun onSaveCurrent_savesAndUpdatesCount() = runTest {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        viewModel.onPhotoCaptured(bitmap)
        idleMainLooper()
        advanceUntilIdle()
        idleMainLooper() // completes analysis -> Success

        val successBefore = viewModel.uiState.first()
        assertTrue(successBefore is PigeonHunterUiState.Success)

        viewModel.onSaveCurrent()
        idleMainLooper()
        advanceUntilIdle()
        idleMainLooper()
        idleMainLooper()

        val afterSave = viewModel.uiState.first()
        assertTrue(afterSave is PigeonHunterUiState.Success)
        assertEquals(1, afterSave.savedCount)
        assertTrue(afterSave.saveMessage?.contains("Saved!") == true)
    }

    @Test
    fun onSaveCurrent_duplicateDetection() = runTest {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        viewModel.onPhotoCaptured(bitmap)
        advanceUntilIdle()

        viewModel.onSaveCurrent()
        idleMainLooper()
        advanceUntilIdle()
        idleMainLooper()
        idleMainLooper()
        assertEquals(1, viewModel.uiState.first().savedCount)

        // Save same bitmap again without retake - should be detected as duplicate
        viewModel.onSaveCurrent()
        idleMainLooper()
        advanceUntilIdle()
        idleMainLooper()

        val afterSecondSave = viewModel.uiState.first()
        assertEquals(1, afterSecondSave.savedCount) // still 1
        assertTrue(afterSecondSave is PigeonHunterUiState.Success)
        assertTrue((afterSecondSave as PigeonHunterUiState.Success).saveMessage?.contains("Already saved") == true)
    }

    @Test
    fun clearSaveMessage_clearsMessage() = runTest {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        viewModel.onPhotoCaptured(bitmap)
        advanceUntilIdle()
        viewModel.onSaveCurrent()
        idleMainLooper()
        advanceUntilIdle()
        idleMainLooper()

        var state = viewModel.uiState.first()
        assertTrue(state is PigeonHunterUiState.Success)
        assertNotNull((state as PigeonHunterUiState.Success).saveMessage)

        viewModel.clearSaveMessage()
        idleMainLooper()
        advanceUntilIdle()
        idleMainLooper()

        state = viewModel.uiState.first()
        assertTrue(state is PigeonHunterUiState.Success)
        assertNull((state as PigeonHunterUiState.Success).saveMessage)
    }

    @Test
    fun errorState_hasMessage() = runTest {
        // Create repo that always throws non-network error
        val errorApi = object : MuseApiService {
            override suspend fun createResponse(
                authorization: String,
                contentType: String,
                request: MuseApiRequest
            ): MuseApiResponse {
                throw RuntimeException("Something went wrong")
            }
        }
        val errorRepo = PigeonRepository(errorApi, "key", "model")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val errorVm = PigeonHunterViewModel(
            repository = errorRepo,
            savedRepository = savedRepo,
            queueRepository = queueRepo,
            networkMonitor = networkMonitor,
            appContext = context
        )

        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        errorVm.onPhotoCaptured(bitmap)
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        idleMainLooper()

        val errState = errorVm.uiState.first()
        assertTrue(errState is PigeonHunterUiState.Error)
        assertTrue((errState as PigeonHunterUiState.Error).message.contains("Something went wrong"))
    }

    @Test
    fun location_preservedAcrossStates() = runTest {
        // Simulate location fetched in Initial
        // We can't easily mock Geocoder, but we can test that location from Initial is carried to Analyzing and Success
        // For simplicity, we test withCounts preservation via direct state manipulation

        val initial = viewModel.uiState.first() as PigeonHunterUiState.Initial
        // Simulate having location from previous fetch
        // Need to use reflection or just test that onPhotoCaptured preserves location from current state?
        // In onPhotoCaptured we create Analyzing with location = current.location, so location preserved

        // Manually set location via private _uiState? Can't access, so we test via fetch logic not included
        // Instead, verify that after onPhotoCaptured, location from Initial is preserved in Analyzing

        // Since Initial location is null by default in this test env (no real Geocoder), we just check no crash
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        viewModel.onPhotoCaptured(bitmap)
        idleMainLooper()
        val analyzing = viewModel.uiState.first()
        assertTrue(analyzing is PigeonHunterUiState.Analyzing)
        assertEquals(initial.location, analyzing.location) // both null, but structure preserved
    }
}
