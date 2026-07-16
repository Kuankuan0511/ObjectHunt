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
}
