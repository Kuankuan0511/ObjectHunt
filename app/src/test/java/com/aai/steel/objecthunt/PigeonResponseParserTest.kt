package com.aai.steel.objecthunt

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for PigeonResponseParser
 * Pure Kotlin, no Android deps, runs on JVM via ./gradlew test
 *
 * How to ensure parser correctness:
 * 1. Test happy paths (YES with all fields, NO)
 * 2. Test edge cases (case-insensitive, N/A, missing fields, empty)
 * 3. Test error paths (API errors, network exceptions)
 */
class PigeonResponseParserTest {

    // ---------- parseRawDescription ----------

    @Test
    fun parseRawDescription_hasPigeonYes_withAllFields() {
        val raw = """
            HAS_PIGEON: YES
            TYPE: Rock Pigeon
            FEATURES: grey feathers, white markings, medium size
            LOCATION: center of image
            CONFIDENCE: High
        """.trimIndent()

        val result = PigeonResponseParser.parseRawDescription(raw)

        assertTrue(result.hasPigeon)
        assertEquals("Rock Pigeon", result.pigeonType)
        assertEquals("grey feathers, white markings, medium size", result.features)
        assertEquals("center of image", result.location)
        assertEquals(0.9f, result.confidence, 0.01f)
        assertEquals(raw, result.description)
    }

    @Test
    fun parseRawDescription_hasPigeonNo() {
        val raw = "HAS_PIGEON: NO"
        val result = PigeonResponseParser.parseRawDescription(raw)

        assertFalse(result.hasPigeon)
        assertNull(result.pigeonType)
        assertEquals(0.1f, result.confidence, 0.01f) // default for NO
    }

    @Test
    fun parseRawDescription_caseInsensitive() {
        val raw = """
            has_pigeon: yes
            type: feral pigeon
            confidence: medium
        """.trimIndent()

        val result = PigeonResponseParser.parseRawDescription(raw)

        assertTrue(result.hasPigeon)
        assertEquals("feral pigeon", result.pigeonType)
        assertEquals(0.7f, result.confidence, 0.01f)
    }

    @Test
    fun parseRawDescription_typeNA_filtered() {
        val raw = """
            HAS_PIGEON: YES
            TYPE: N/A
            CONFIDENCE: Low
        """.trimIndent()

        val result = PigeonResponseParser.parseRawDescription(raw)

        assertTrue(result.hasPigeon)
        assertNull(result.pigeonType) // N/A should be treated as null
        assertEquals(0.5f, result.confidence, 0.01f)
    }

    @Test
    fun parseRawDescription_missingConfidence_defaults() {
        val raw = """
            HAS_PIGEON: YES
            TYPE: Wood Pigeon
        """.trimIndent()

        val result = PigeonResponseParser.parseRawDescription(raw)

        assertTrue(result.hasPigeon)
        assertEquals(0.8f, result.confidence, 0.01f) // default for YES when no confidence
    }

    @Test
    fun parseRawDescription_confidenceLow() {
        val raw = "HAS_PIGEON: YES\nCONFIDENCE: Low"
        val result = PigeonResponseParser.parseRawDescription(raw)
        assertEquals(0.5f, result.confidence, 0.01f)
    }

    @Test
    fun parseRawDescription_emptyString() {
        val result = PigeonResponseParser.parseRawDescription("")
        assertFalse(result.hasPigeon)
        assertEquals(0.1f, result.confidence, 0.01f)
    }

    // ---------- parseApiResponse ----------

    @Test
    fun parseApiResponse_withError() {
        val response = MuseApiResponse(
            id = "test",
            output = null,
            error = ApiError(code = "invalid_api_key", message = "bad key"),
            usage = null
        )

        val result = PigeonResponseParser.parseApiResponse(response)

        assertFalse(result.hasPigeon)
        assertTrue(result.description.contains("Invalid API key"))
    }

    @Test
    fun parseApiResponse_emptyOutput() {
        val response = MuseApiResponse(
            id = "test",
            output = emptyList(),
            error = null,
            usage = null
        )

        val result = PigeonResponseParser.parseApiResponse(response)

        assertFalse(result.hasPigeon)
        assertTrue(result.description.contains("did not return any analysis"))
    }

    @Test
    fun parseApiResponse_validOutput() {
        val response = MuseApiResponse(
            id = "test",
            output = listOf(
                OutputItem(
                    type = "message",
                    content = listOf(
                        OutputContent(type = "output_text", text = "HAS_PIGEON: YES\nTYPE: King Pigeon\nCONFIDENCE: High")
                    )
                )
            ),
            error = null,
            usage = null
        )

        val result = PigeonResponseParser.parseApiResponse(response)

        assertTrue(result.hasPigeon)
        assertEquals("King Pigeon", result.pigeonType)
        assertEquals(0.9f, result.confidence, 0.01f)
    }

    // ---------- createErrorResultFromException ----------

    @Test
    fun createErrorResult_noInternet() {
        val e = Exception("Unable to resolve host api.ai.meta.com")
        val result = PigeonResponseParser.createErrorResultFromException(e)
        assertFalse(result.hasPigeon)
        assertTrue(result.description.contains("No internet"))
    }

    @Test
    fun createErrorResult_timeout() {
        val e = Exception("timeout")
        val result = PigeonResponseParser.createErrorResultFromException(e)
        assertTrue(result.description.contains("timed out", ignoreCase = true))
    }

    @Test
    fun createErrorResult_401() {
        val e = Exception("HTTP 401 Unauthorized")
        val result = PigeonResponseParser.createErrorResultFromException(e)
        assertTrue(result.description.contains("Invalid API key"))
    }

    @Test
    fun createErrorResult_503() {
        val e = Exception("HTTP 503 Service Unavailable")
        val result = PigeonResponseParser.createErrorResultFromException(e)
        assertTrue(result.description.contains("Server error"))
    }

    // ---------- Additional coverage: FEATURES, LOCATION, whitespace, greedy, errors ----------

    @Test
    fun parseRawDescription_featuresExtraction() {
        val raw = """
            HAS_PIGEON: YES
            FEATURES: brown wings, black tail
            CONFIDENCE: High
        """.trimIndent()
        val result = PigeonResponseParser.parseRawDescription(raw)
        assertEquals("brown wings, black tail", result.features)
        assertTrue(result.hasPigeon)
    }

    @Test
    fun parseRawDescription_locationExtraction() {
        val raw = """
            HAS_PIGEON: YES
            LOCATION: top-left corner
            CONFIDENCE: Medium
        """.trimIndent()
        val result = PigeonResponseParser.parseRawDescription(raw)
        assertEquals("top-left corner", result.location)
        assertEquals(0.7f, result.confidence, 0.01f)
    }

    @Test
    fun parseRawDescription_whitespaceTrimming() {
        val raw = """
            HAS_PIGEON:   YES  
            TYPE:   Rock Pigeon   
            FEATURES:   grey feathers   
            LOCATION:   center   
            CONFIDENCE:   High   
        """.trimIndent()
        val result = PigeonResponseParser.parseRawDescription(raw)
        assertTrue(result.hasPigeon)
        assertEquals("Rock Pigeon", result.pigeonType)
        assertEquals("grey feathers", result.features)
        assertEquals("center", result.location)
        assertEquals(0.9f, result.confidence, 0.01f)
    }

    @Test
    fun parseRawDescription_greedyCheck_typeDoesNotCaptureNextLine() {
        // TYPE regex uses (.+) which should stop at newline (dot doesn't match newline by default)
        val raw = """
            HAS_PIGEON: YES
            TYPE: Rock Pigeon
            FEATURES: grey
            LOCATION: center
            CONFIDENCE: High
        """.trimIndent()
        val result = PigeonResponseParser.parseRawDescription(raw)
        assertEquals("Rock Pigeon", result.pigeonType)
        assertEquals("grey", result.features)
        assertEquals("center", result.location)
    }

    @Test
    fun parseRawDescription_junkTextBefore() {
        val raw = """
            This is an AI analysis of the image.
            I can see a bird.
            HAS_PIGEON: YES
            TYPE: Feral Pigeon
            CONFIDENCE: Medium
        """.trimIndent()
        val result = PigeonResponseParser.parseRawDescription(raw)
        assertTrue(result.hasPigeon)
        assertEquals("Feral Pigeon", result.pigeonType)
    }

    @Test
    fun parseRawDescription_emptyFeaturesAndLocation() {
        val raw = """
            HAS_PIGEON: YES
            TYPE: Wood Pigeon
            FEATURES: 
            LOCATION: 
            CONFIDENCE: High
        """.trimIndent()
        val result = PigeonResponseParser.parseRawDescription(raw)
        // empty after trim should become null
        assertNull(result.features)
        assertNull(result.location)
    }

    @Test
    fun parseRawDescription_confidenceVariations() {
        val high = PigeonResponseParser.parseRawDescription("HAS_PIGEON: YES\nCONFIDENCE: HIGH")
        val medium = PigeonResponseParser.parseRawDescription("HAS_PIGEON: YES\nCONFIDENCE: medium")
        val low = PigeonResponseParser.parseRawDescription("HAS_PIGEON: YES\nCONFIDENCE: LoW")
        assertEquals(0.9f, high.confidence, 0.01f)
        assertEquals(0.7f, medium.confidence, 0.01f)
        assertEquals(0.5f, low.confidence, 0.01f)
    }

    @Test
    fun parseApiResponse_modelNotFound() {
        val response = MuseApiResponse(
            id = "test", output = null,
            error = ApiError(code = "model_not_found", message = "not found"),
            usage = null
        )
        val result = PigeonResponseParser.parseApiResponse(response)
        assertFalse(result.hasPigeon)
        assertTrue(result.description.contains("not available", ignoreCase = true))
    }

    @Test
    fun parseApiResponse_modelNotAccessible() {
        val response = MuseApiResponse(
            id = "test", output = null,
            error = ApiError(code = "model_not_accessible", message = "no access"),
            usage = null
        )
        val result = PigeonResponseParser.parseApiResponse(response)
        assertTrue(result.description.contains("doesn't have access", ignoreCase = true))
    }

    @Test
    fun parseApiResponse_rateLimit() {
        val response = MuseApiResponse(
            id = "test", output = null,
            error = ApiError(code = "rate_limit_exceeded", message = "too many"),
            usage = null
        )
        val result = PigeonResponseParser.parseApiResponse(response)
        assertTrue(result.description.contains("Too many requests"))
    }

    @Test
    fun parseApiResponse_unknownErrorCode() {
        val response = MuseApiResponse(
            id = "test", output = null,
            error = ApiError(code = "some_unknown", message = "weird error"),
            usage = null
        )
        val result = PigeonResponseParser.parseApiResponse(response)
        assertTrue(result.description.contains("API Error"))
    }

    @Test
    fun parseApiResponse_multipleContentPiecesConcatenated() {
        // Muse can return multiple output_text chunks
        val response = MuseApiResponse(
            id = "test",
            output = listOf(
                OutputItem(
                    type = "message",
                    content = listOf(
                        OutputContent(type = "output_text", text = "HAS_PIGEON: YES\n"),
                        OutputContent(type = "output_text", text = "TYPE: Fantail Pigeon\n"),
                        OutputContent(type = "output_text", text = "CONFIDENCE: High")
                    )
                )
            ),
            error = null,
            usage = null
        )
        val result = PigeonResponseParser.parseApiResponse(response)
        assertTrue(result.hasPigeon)
        assertEquals("Fantail Pigeon", result.pigeonType)
    }

    @Test
    fun createErrorResult_nullException() {
        val result = PigeonResponseParser.createErrorResultFromException(null)
        assertFalse(result.hasPigeon)
        assertTrue(result.description.contains("Unknown error", ignoreCase = true) || result.description.contains("Failed to analyze"))
    }

    @Test
    fun createErrorResult_403_and_429() {
        val e403 = Exception("HTTP 403 Forbidden")
        val e429 = Exception("HTTP 429 Too Many Requests")
        val r403 = PigeonResponseParser.createErrorResultFromException(e403)
        val r429 = PigeonResponseParser.createErrorResultFromException(e429)
        assertTrue(r403.description.contains("Access denied"))
        assertTrue(r429.description.contains("Too many requests"))
    }

    @Test
    fun createErrorResult_500_and_502() {
        val e500 = Exception("HTTP 500 Internal Server Error")
        val e502 = Exception("HTTP 502 Bad Gateway")
        assertTrue(PigeonResponseParser.createErrorResultFromException(e500).description.contains("Server error"))
        assertTrue(PigeonResponseParser.createErrorResultFromException(e502).description.contains("Server error"))
    }
}
