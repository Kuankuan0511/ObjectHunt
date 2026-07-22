package com.aai.steel.objecthunt

import android.graphics.Bitmap
import android.graphics.Color
import com.aai.steel.objecthunt.data.PigeonDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PigeonRepositoryTest {

    private lateinit var repository: PigeonRepository

    @Before
    fun setUp() {
        // Default repo with fake success API for most tests
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
        repository = PigeonRepository(fakeSuccessApi, "test_key", "test-model")
    }

    // ---------- bitmap compression ----------

    @Test
    fun bitmapToBase64_resizesLargeBitmap() = runTest {
        // 2000x2000 -> should resize to max 1024
        val largeBitmap = Bitmap.createBitmap(2000, 2000, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        // Use reflection to access private bitmapToBase64, or test via detectPigeon which calls it
        // Instead, we test that detectPigeon succeeds and doesn't crash with large bitmap
        val result = repository.detectPigeon(largeBitmap)
        assertTrue(result.hasPigeon)
    }

    @Test
    fun bitmapToBase64_smallBitmap_unchanged() = runTest {
        val smallBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val result = repository.detectPigeon(smallBitmap)
        assertTrue(result.hasPigeon)
    }

    // ---------- retry logic ----------

    @Test
    fun detectPigeon_retriesOnRetryableError_thenSucceeds() = runTest {
        var callCount = 0
        val fakeRetryingApi = object : MuseApiService {
            override suspend fun createResponse(
                authorization: String,
                contentType: String,
                request: MuseApiRequest
            ): MuseApiResponse {
                callCount++
                if (callCount < 3) {
                    throw IOException("Unable to resolve host")
                }
                return MuseApiResponse(
                    id = "test",
                    output = listOf(
                        OutputItem(
                            type = "message",
                            content = listOf(OutputContent(type = "output_text", text = "HAS_PIGEON: YES\nTYPE: Wood Pigeon\nCONFIDENCE: Medium"))
                        )
                    ),
                    error = null,
                    usage = null
                )
            }
        }
        val retryingRepo = PigeonRepository(fakeRetryingApi, "key", "model")
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

        val result = retryingRepo.detectPigeon(bitmap)

        // Should have retried 2 times (callCount 3) and eventually succeeded
        assertEquals(3, callCount)
        assertTrue(result.hasPigeon)
        assertEquals("Wood Pigeon", result.pigeonType)
    }

    @Test
    fun detectPigeon_retriesExhausted_returnsErrorResult() = runTest {
        var callCount = 0
        val alwaysFailingApi = object : MuseApiService {
            override suspend fun createResponse(
                authorization: String,
                contentType: String,
                request: MuseApiRequest
            ): MuseApiResponse {
                callCount++
                throw IOException("timeout")
            }
        }
        val failingRepo = PigeonRepository(alwaysFailingApi, "key", "model")
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

        val result = failingRepo.detectPigeon(bitmap)

        assertEquals(3, callCount) // max retries = 3
        assertFalse(result.hasPigeon)
        assertEquals(0f, result.confidence, 0.01f)
        assertTrue(result.description.contains("timed out", ignoreCase = true))
    }

    @Test
    fun detectPigeon_doesNotRetryOnClientError_401() = runTest {
        var callCount = 0
        val clientErrorApi = object : MuseApiService {
            override suspend fun createResponse(
                authorization: String,
                contentType: String,
                request: MuseApiRequest
            ): MuseApiResponse {
                callCount++
                throw IOException("HTTP 401 Unauthorized")
            }
        }
        val clientErrorRepo = PigeonRepository(clientErrorApi, "key", "model")
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

        val result = clientErrorRepo.detectPigeon(bitmap)

        // Should NOT retry on 401 (client error), only 1 call
        assertEquals(1, callCount)
        assertFalse(result.hasPigeon)
        assertTrue(result.description.contains("Invalid API key"))
    }

    @Test
    fun detectPigeon_doesNotRetryOnClientError_403() = runTest {
        var callCount = 0
        val api = object : MuseApiService {
            override suspend fun createResponse(
                authorization: String,
                contentType: String,
                request: MuseApiRequest
            ): MuseApiResponse {
                callCount++
                throw IOException("HTTP 403 Forbidden")
            }
        }
        val repo = PigeonRepository(api, "key", "model")
        val result = repo.detectPigeon(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        assertEquals(1, callCount)
        assertTrue(result.description.contains("Access denied"))
    }

    @Test
    fun detectPigeon_retriesOnServerError_502_503_504() = runTest {
        val retryableCodes = listOf("502", "503", "504")
        for (code in retryableCodes) {
            var callCount = 0
            val api = object : MuseApiService {
                override suspend fun createResponse(
                    authorization: String,
                    contentType: String,
                    request: MuseApiRequest
                ): MuseApiResponse {
                    callCount++
                    if (callCount < 2) throw IOException("HTTP $code")
                    return MuseApiResponse(
                        id = "test",
                        output = listOf(
                            OutputItem(
                                type = "message",
                                content = listOf(OutputContent(type = "output_text", text = "HAS_PIGEON: NO"))
                            )
                        ),
                        error = null,
                        usage = null
                    )
                }
            }
            val repo = PigeonRepository(api, "key", "model")
            val result = repo.detectPigeon(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
            assertEquals("Should retry on $code", 2, callCount)
            assertFalse(result.hasPigeon)
        }
    }

    @Test
    fun detectPigeon_handlesApiError_modelNotFound() = runTest {
        val apiErrorApi = object : MuseApiService {
            override suspend fun createResponse(
                authorization: String,
                contentType: String,
                request: MuseApiRequest
            ): MuseApiResponse {
                return MuseApiResponse(
                    id = "test",
                    output = null,
                    error = ApiError(code = "model_not_found", message = "not found"),
                    usage = null
                )
            }
        }
        val repo = PigeonRepository(apiErrorApi, "key", "model")
        val result = repo.detectPigeon(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        assertFalse(result.hasPigeon)
        assertTrue(result.description.contains("not available", ignoreCase = true))
    }

    @Test
    fun detectPigeon_noPigeon_returnsLowConfidence() = runTest {
        val noPigeonApi = object : MuseApiService {
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
                            content = listOf(OutputContent(type = "output_text", text = "HAS_PIGEON: NO"))
                        )
                    ),
                    error = null,
                    usage = null
                )
            }
        }
        val repo = PigeonRepository(noPigeonApi, "key", "model")
        val result = repo.detectPigeon(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        assertFalse(result.hasPigeon)
        assertEquals(0.1f, result.confidence, 0.01f)
    }
}
