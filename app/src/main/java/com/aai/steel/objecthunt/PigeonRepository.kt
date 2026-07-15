package com.aai.steel.objecthunt

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Data class representing the result of pigeon detection
 * Used across ViewModel and UI layers
 */
data class PigeonDetectionResult(
    val hasPigeon: Boolean,
    val pigeonType: String?,
    val confidence: Float,
    val features: String?,
    val location: String?,
    val description: String,
    val rawResponse: String
)

/**
 * Data classes for Muse API request/response
 */
data class MuseApiRequest(
    @SerializedName("model") val model: String,
    @SerializedName("input") val input: List<InputMessage>,
    @SerializedName("temperature") val temperature: Double = 1.0,
    @SerializedName("top_p") val topP: Double = 1.0,
    @SerializedName("stream") val stream: Boolean = false
)

data class InputMessage(
    @SerializedName("type") val type: String = "message",
    @SerializedName("role") val role: String = "user",
    @SerializedName("content") val content: List<ContentPart>
)

sealed class ContentPart {
    data class TextPart(
        @SerializedName("type") val type: String = "input_text",
        @SerializedName("text") val text: String
    ) : ContentPart()
    
    data class ImagePart(
        @SerializedName("type") val type: String = "input_image",
        @SerializedName("image_url") val imageUrl: String
    ) : ContentPart()
}

data class MuseApiResponse(
    @SerializedName("id") val id: String?,
    @SerializedName("output") val output: List<OutputItem>?,
    @SerializedName("error") val error: ApiError?,
    @SerializedName("usage") val usage: Usage?
)

data class OutputItem(
    @SerializedName("type") val type: String,
    @SerializedName("content") val content: List<OutputContent>?
)

data class OutputContent(
    @SerializedName("type") val type: String,
    @SerializedName("text") val text: String?
)

data class ApiError(
    @SerializedName("code") val code: String?,
    @SerializedName("message") val message: String?
)

data class Usage(
    @SerializedName("input_tokens") val inputTokens: Int?,
    @SerializedName("output_tokens") val outputTokens: Int?,
    @SerializedName("total_tokens") val totalTokens: Int?
)

/**
 * Retrofit API interface for Muse API
 */
interface MuseApiService {
    @POST("v1/responses")
    suspend fun createResponse(
        @Header("Authorization") authorization: String,
        @Header("Content-Type") contentType: String = "application/json",
        @Body request: MuseApiRequest
    ): MuseApiResponse
}

/**
 * Repository for pigeon detection
 * Abstracts data sources (API, cache, etc.)
 */
class PigeonRepository(
    private val apiService: MuseApiService,
    private val apiKey: String,
    private val model: String = "muse-spark-1.1"
) {
    
    /**
     * Detect pigeon in image using Muse API with retry logic
     * Retries up to 3 times with exponential backoff for transient failures
     */
    suspend fun detectPigeon(
        bitmap: Bitmap,
        maxRetries: Int = 3
    ): PigeonDetectionResult {
        var lastException: Exception? = null
        
        for (attempt in 0 until maxRetries) {
            try {
                Log.d("PigeonRepository", "Attempt ${attempt + 1}/$maxRetries: Starting pigeon detection via API")
                return performDetection(bitmap)
            } catch (e: Exception) {
                lastException = e
                val isRetryable = isRetryableError(e)
                
                if (isRetryable && attempt < maxRetries - 1) {
                    val delayMs = 1000L * (attempt + 1) // Exponential backoff: 1s, 2s, 3s
                    Log.w("PigeonRepository", "Attempt ${attempt + 1} failed: ${e.message}. Retrying in ${delayMs}ms...")
                    kotlinx.coroutines.delay(delayMs)
                } else {
                    Log.e("PigeonRepository", "All $maxRetries attempts failed", e)
                    // Don't break, just let loop finish and return error below
                }
            }
        }
        
        // All retries exhausted, return error result
        return createErrorResult(lastException)
    }
    
    /**
     * Perform the actual detection (single attempt)
     */
    private suspend fun performDetection(bitmap: Bitmap): PigeonDetectionResult {
        // Convert bitmap to base64
        val base64Image = bitmapToBase64(bitmap)
        
        // Build request
        val request = MuseApiRequest(
            model = model,
            input = listOf(
                InputMessage(
                    content = listOf(
                        ContentPart.TextPart(
                            text = """
                                Analyze this image for pigeons. Provide your answer in this exact format:
                                
                                HAS_PIGEON: YES or NO
                                TYPE: [If YES, specify the type: Rock Pigeon, Feral Pigeon, Wood Pigeon, 
                                       Stock Dove, Racing Homer, Fantail Pigeon, King Pigeon, or Unknown]
                                FEATURES: [If YES, describe color, markings, size, distinguishing features]
                                LOCATION: [If YES, where is the pigeon in the image?]
                                CONFIDENCE: [High, Medium, or Low]
                                
                                If NO pigeon is visible, just write:
                                HAS_PIGEON: NO
                            """.trimIndent()
                        ),
                        ContentPart.ImagePart(
                            imageUrl = "data:image/jpeg;base64,$base64Image"
                        )
                    )
                )
            )
        )
        
        // Make API call via Retrofit
        val response = apiService.createResponse(
            authorization = "Bearer $apiKey",
            request = request
        )
        
        // Parse response
        return parseResponse(response)
    }
    
    /**
     * Check if an error is retryable (transient network/server issues)
     */
    private fun isRetryableError(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: ""
        return when {
            message.contains("unable to resolve host") -> true // DNS failure
            message.contains("timeout") -> true // Timeout
            message.contains("502") -> true // Bad Gateway
            message.contains("503") -> true // Service Unavailable
            message.contains("504") -> true // Gateway Timeout
            else -> false // Don't retry for 4xx errors (client errors)
        }
    }
    
    /**
     * Create error result from exception
     */
    private fun createErrorResult(e: Exception?): PigeonDetectionResult {
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
    
    private fun parseResponse(response: MuseApiResponse): PigeonDetectionResult {
        // Check for API error
        response.error?.let { error ->
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
        
        // Extract text from output
        val description = response.output
            ?.filter { it.type == "message" }
            ?.flatMap { it.content ?: emptyList() }
            ?.filter { it.type == "output_text" }
            ?.mapNotNull { it.text }
            ?.joinToString("") ?: ""
        
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
        
        // Parse structured response
        val hasPigeon = description.contains("HAS_PIGEON:\\s*YES".toRegex(RegexOption.IGNORE_CASE))
        
        val typeMatch = "TYPE:\\s*(.+)".toRegex(RegexOption.IGNORE_CASE).find(description)
        val pigeonType = typeMatch?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("N/A", ignoreCase = true) }
        Log.d("ASDASD",pigeonType.toString())
        
        val featuresMatch = "FEATURES:\\s*(.+)".toRegex(RegexOption.IGNORE_CASE).find(description)
        val features = featuresMatch?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        
        val locationMatch = "LOCATION:\\s*(.+)".toRegex(RegexOption.IGNORE_CASE).find(description)
        val location = locationMatch?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        
        val confidenceMatch = "CONFIDENCE:\\s*(High|Medium|Low)".toRegex(RegexOption.IGNORE_CASE).find(description)
        val confidence = when (confidenceMatch?.groupValues?.get(1)?.lowercase()) {
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
            rawResponse = ""
        )
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        // Resize image if too large to prevent OOM and reduce API payload size
        // Max dimension of 1024px is sufficient for AI analysis and keeps payload reasonable
        val maxDimension = 1024
        val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            Log.d("PigeonRepository", "Resizing image from ${bitmap.width}x${bitmap.height}")
            val ratio = minOf(
                maxDimension.toFloat() / bitmap.width,
                maxDimension.toFloat() / bitmap.height
            )
            val newWidth = (bitmap.width * ratio).toInt()
            val newHeight = (bitmap.height * ratio).toInt()
            Log.d("PigeonRepository", "Resized to ${newWidth}x${newHeight}")
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }
        
        val outputStream = ByteArrayOutputStream()
        // Compress to JPEG with 80% quality to reduce size
        // API has limits on request size (typically ~10MB)
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        
        // Log size for debugging
        val sizeKb = byteArray.size / 1024
        Log.d("PigeonRepository", "Image size after compression: ${sizeKb}KB")
        
        if (sizeKb > 5000) { // 5MB warning threshold
            Log.w("PigeonRepository", "Image is large (${sizeKb}KB), may cause slow API response")
        }
        
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
    
    companion object {
        /**
         * Create Retrofit service for Muse API
         */
        fun createApiService(): MuseApiService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            
            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
            
            val retrofit = Retrofit.Builder()
                .baseUrl("https://api.ai.meta.com/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            
            return retrofit.create(MuseApiService::class.java)
        }
    }
}
