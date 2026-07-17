package com.aai.steel.objecthunt

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.aai.steel.objecthunt.data.PigeonDatabase
import com.aai.steel.objecthunt.data.SavedPigeonRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SavedPigeonRepositoryTest {

    private lateinit var db: PigeonDatabase
    private lateinit var repository: SavedPigeonRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = PigeonDatabase.getInMemoryInstance(context)
        repository = SavedPigeonRepository(db.pigeonDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createBitmap(): Bitmap {
        // Small 10x10 bitmap for testing
        return Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
    }

    private fun createResult(type: String? = "Rock Pigeon"): PigeonDetectionResult {
        return PigeonDetectionResult(
            hasPigeon = true,
            pigeonType = type,
            confidence = 0.9f,
            features = "grey",
            location = "center",
            description = "Test",
            rawResponse = "HAS_PIGEON: YES"
        )
    }

    @Test
    fun savePigeon_savesSuccessfully() = runTest {
        val bitmap = createBitmap()
        val result = repository.savePigeon(bitmap, createResult(), "San Francisco")

        assertTrue(result is SavedPigeonRepository.SaveResult.Saved)
        assertEquals(1, repository.getCount())
    }

    @Test
    fun savePigeon_detectsDuplicate() = runTest {
        val bitmap = createBitmap()
        val result1 = createResult()

        val first = repository.savePigeon(bitmap, result1, "SF")
        assertTrue(first is SavedPigeonRepository.SaveResult.Saved)
        assertEquals(1, repository.getCount())

        // Save same bitmap again - should be detected as duplicate
        val second = repository.savePigeon(bitmap, result1, "SF")
        assertTrue(second is SavedPigeonRepository.SaveResult.AlreadyExists)
        assertEquals(1, repository.getCount()) // still 1, not 2
    }

    @Test
    fun savePigeon_differentBitmaps_notDuplicate() = runTest {
        val bitmap1 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val bitmap2 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }

        repository.savePigeon(bitmap1, createResult(), "SF")
        repository.savePigeon(bitmap2, createResult(), "SF")

        assertEquals(2, repository.getCount())
    }

    @Test
    fun savePigeon_enforcesLimit20() = runTest {
        // Save 20 different bitmaps
        for (i in 1..20) {
            val bmp = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply {
                eraseColor(i) // different color = different hash
            }
            repository.savePigeon(bmp, createResult("Pigeon $i"), "City $i")
        }
        assertEquals(20, repository.getCount())

        // 21st should delete oldest
        val bmp21 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(21) }
        repository.savePigeon(bmp21, createResult("Pigeon 21"), "City 21")

        assertEquals(20, repository.getCount())
        val all = repository.getSavedPigeons()
        assertFalse(all.any { it.pigeonType == "Pigeon 1" }) // oldest deleted
        assertTrue(all.any { it.pigeonType == "Pigeon 21" })
    }
}
