package com.aai.steel.objecthunt

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.aai.steel.objecthunt.data.DetectionQueueRepository
import com.aai.steel.objecthunt.data.NetworkMonitor
import com.aai.steel.objecthunt.data.PigeonDatabase
import com.aai.steel.objecthunt.data.SavedPigeonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ViewModel tests using StandardTestDispatcher for Main.
 * viewModelScope uses Dispatchers.Main, so we set Main to testDispatcher to control it.
 * Using uiState.value for synchronous current state, not first() which would need looper idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PigeonHunterViewModelTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var db: PigeonDatabase
    private lateinit var pigeonRepo: PigeonRepository
    private lateinit var savedRepo: SavedPigeonRepository
    private lateinit var queueRepo: DetectionQueueRepository
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var viewModel: PigeonHunterViewModel

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun initialState_hasZeroCounts() = runTest(testDispatcher) {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state is PigeonHunterUiState.Initial)
        assertEquals(0, state.savedCount)
        assertEquals(0, state.queuedCount)
        assertNull(state.location)
        assertFalse(state.isFetchingLocation)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun onPhotoCaptured_transitionsToAnalyzing() = runTest(testDispatcher) {
        advanceUntilIdle()
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        viewModel.onPhotoCaptured(bitmap)

        // Analyzing is set synchronously before analyzePhoto coroutine runs
        val analyzing = viewModel.uiState.value
        assertTrue("Should be Analyzing immediately", analyzing is PigeonHunterUiState.Analyzing)
        assertEquals(bitmap, (analyzing as PigeonHunterUiState.Analyzing).bitmap)

        advanceUntilIdle() // run analyzePhoto to completion

        val success = viewModel.uiState.value
        assertTrue("Should be Success after analysis", success is PigeonHunterUiState.Success)
        assertTrue((success as PigeonHunterUiState.Success).result.hasPigeon)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun onRetakePhoto_preservesSavedCount_bugFix() = runTest(testDispatcher) {
        advanceUntilIdle()
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
        advanceUntilIdle()
        assertEquals(5, savedRepo.getCount())

        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        viewModel.onPhotoCaptured(bitmap)
        advanceUntilIdle()
        viewModel.onRetakePhoto()
        advanceUntilIdle()

        val afterRetake = viewModel.uiState.value
        assertTrue(afterRetake is PigeonHunterUiState.Initial)
        assertEquals(5, afterRetake.savedCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun onSaveCurrent_savesAndUpdatesCount() = runTest(testDispatcher) {
        advanceUntilIdle()
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        viewModel.onPhotoCaptured(bitmap)
        advanceUntilIdle()

        val successBefore = viewModel.uiState.value
        assertTrue(successBefore is PigeonHunterUiState.Success)

        viewModel.onSaveCurrent()
        advanceUntilIdle()

        val afterSave = viewModel.uiState.value
        assertTrue(afterSave is PigeonHunterUiState.Success)
        assertEquals(1, afterSave.savedCount)
        assertTrue(afterSave.saveMessage?.contains("Saved!") == true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun onSaveCurrent_duplicateDetection() = runTest(testDispatcher) {
        advanceUntilIdle()
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        viewModel.onPhotoCaptured(bitmap)
        advanceUntilIdle()

        viewModel.onSaveCurrent()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.savedCount)

        // Second save of same bitmap -> duplicate
        viewModel.onSaveCurrent()
        advanceUntilIdle()

        val afterSecondSave = viewModel.uiState.value
        assertEquals(1, afterSecondSave.savedCount)
        assertTrue(afterSecondSave is PigeonHunterUiState.Success)
        assertTrue((afterSecondSave as PigeonHunterUiState.Success).saveMessage?.contains("Already saved") == true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun clearSaveMessage_clearsMessage() = runTest(testDispatcher) {
        advanceUntilIdle()
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        viewModel.onPhotoCaptured(bitmap)
        advanceUntilIdle()
        viewModel.onSaveCurrent()
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertTrue(state is PigeonHunterUiState.Success)
        assertNotNull((state as PigeonHunterUiState.Success).saveMessage)

        viewModel.clearSaveMessage()
        // clearSaveMessage is synchronous (uses update, not launch) - no need advance, but do it
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertTrue(state is PigeonHunterUiState.Success)
        assertNull((state as PigeonHunterUiState.Success).saveMessage)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun errorState_hasMessage() = runTest(testDispatcher) {
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
        advanceUntilIdle()
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        errorVm.onPhotoCaptured(bitmap)
        advanceUntilIdle()

        val errState = errorVm.uiState.value
        assertTrue(errState is PigeonHunterUiState.Error)
        assertTrue((errState as PigeonHunterUiState.Error).message.contains("Something went wrong"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun location_preservedAcrossStates() = runTest(testDispatcher) {
        advanceUntilIdle()
        val initial = viewModel.uiState.value
        assertTrue(initial is PigeonHunterUiState.Initial)

        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        viewModel.onPhotoCaptured(bitmap)
        val analyzing = viewModel.uiState.value
        assertTrue(analyzing is PigeonHunterUiState.Analyzing)
        assertEquals(initial.location, analyzing.location)
    }
}
