package com.aai.steel.objecthunt

/**
 * Dedicated parser for Muse API pigeon responses.
 * Pure Kotlin, no Android dependencies -> easy to unit test.
 * (Removed android.util.Log to avoid crashing JVM unit tests)
 *
 * Responsibilities:
 * - Extract raw text from MuseApiResponse output
 * - Parse structured fields HAS_PIGEON, TYPE, FEATURES, LOCATION, CONFIDENCE
 * - Map to domain model PigeonDetectionResult
 */
object PigeonResponseParser {

    // Precompiled regexes - case-insensitive
    private val hasPigeonRegex = Regex("HAS_PIGEON:\\s*(YES|NO)", RegexOption.IGNORE_CASE)
    private val typeRegex = Regex("TYPE:\\s*(.+)", RegexOption.IGNORE_CASE)
    private val featuresRegex = Regex("FEATURES:\\s*(.+)", RegexOption.IGNORE_CASE)
    private val locationRegex = Regex("LOCATION:\\s*(.+)", RegexOption.IGNORE_CASE)
    private val confidenceRegex = Regex("CONFIDENCE:\\s*(High|Medium|Low)", RegexOption.IGNORE_CASE)

    /**
     * Parse full API response (including error handling) into domain model.
     */
    fun parseApiResponse(response: MuseApiResponse): PigeonDetectionResult {
        // 1. Check for API-level error
        response.error?.let { error ->
            return createErrorResultFromApiError(error)
        }

        // 2. Extract concatenated text from output messages
        val description = extractDescription(response)

        if (description.isEmpty()) {
            return PigeonDetectionResult(
                hasPigeon = false,
                pigeonType = null,
                confidence = 0f,
                features = null,
                location = null,
                description = "The AI model did not return any analysis. Please try again with a clearer photo.",
                rawResponse = ""
            )
        }

        // 3. Parse structured fields from description
        return parseRawDescription(description)
    }

    /**
     * Parse raw description string like:
     * HAS_PIGEON: YES
     * TYPE: Rock Pigeon
     * FEATURES: grey, white markings...
     * LOCATION: center
     * CONFIDENCE: High
     */
    fun parseRawDescription(description: String): PigeonDetectionResult {
        val hasPigeon = hasPigeonRegex.find(description)
            ?.groupValues?.get(1)
            ?.equals("YES", ignoreCase = true) == true

        val pigeonType = typeRegex.find(description)
            ?.groupValues?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("N/A", ignoreCase = true) }

        // Debug log removed - was Log.d("ASDASD", pigeonType.toString())

        val features = featuresRegex.find(description)
            ?.groupValues?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val location = locationRegex.find(description)
            ?.groupValues?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val confidence = when (confidenceRegex.find(description)?.groupValues?.get(1)?.lowercase()) {
            "high" -> 0.9f
            "medium" -> 0.7f
            "low" -> 0.5f
            else -> if (hasPigeon) 0.8f else 0.1f
        }

        return PigeonDetectionResult(
            hasPigeon = hasPigeon,
            pigeonType = pigeonType,
            confidence = confidence,
            features = features,
            location = location,
            description = description,
            rawResponse = description
        )
    }

    private fun extractDescription(response: MuseApiResponse): String {
        return response.output
            ?.filter { it.type == "message" }
            ?.flatMap { it.content ?: emptyList() }
            ?.filter { it.type == "output_text" }
            ?.mapNotNull { it.text }
            ?.joinToString("") ?: ""
    }

    private fun createErrorResultFromApiError(error: ApiError): PigeonDetectionResult {
        val errorCode = error.code ?: "unknown"
        val userFriendlyMessage = when (errorCode) {
            "model_not_found" -> "The AI model is not available. Please check the model name in your configuration."
            "invalid_api_key" -> "Invalid API key. Please check your API key in local.properties."
            "model_not_accessible" -> "Your API key doesn't have access to this model. Please check your permissions."
            "rate_limit_exceeded" -> "Too many requests. Please wait a moment and try again."
            else -> "API Error: ${error.message ?: "Unknown error"}"
        }

        return PigeonDetectionResult(
            hasPigeon = false,
            pigeonType = null,
            confidence = 0f,
            features = null,
            location = null,
            description = userFriendlyMessage,
            rawResponse = ""
        )
    }

    /**
     * For network-level exceptions (timeout, no internet) -> user friendly message
     */
    fun createErrorResultFromException(e: Exception?): PigeonDetectionResult {
        val userFriendlyMessage = when {
            e?.message?.contains("Unable to resolve host", ignoreCase = true) == true ->
                "No internet connection. Please check your network and try again."
            e?.message?.contains("timeout", ignoreCase = true) == true ->
                "Request timed out after multiple attempts. The AI service may be busy. Please try again later."
            e?.message?.contains("401", ignoreCase = true) == true ->
                "Invalid API key. Please check your API key in local.properties."
            e?.message?.contains("403", ignoreCase = true) == true ->
                "Access denied. Your API key doesn't have permission to use this model."
            e?.message?.contains("429", ignoreCase = true) == true ->
                "Too many requests. Please wait a moment and try again."
            e?.message?.contains("500", ignoreCase = true) == true ||
            e?.message?.contains("502", ignoreCase = true) == true ||
            e?.message?.contains("503", ignoreCase = true) == true ->
                "Server error. The AI service is temporarily unavailable after multiple attempts. Please try again later."
            else ->
                "Failed to analyze image after multiple attempts: ${e?.message ?: "Unknown error"}"
        }

        return PigeonDetectionResult(
            hasPigeon = false,
            pigeonType = null,
            confidence = 0f,
            features = null,
            location = null,
            description = userFriendlyMessage,
            rawResponse = ""
        )
    }
}
