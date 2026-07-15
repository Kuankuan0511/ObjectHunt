package com.aai.steel.objecthunt

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * UI State for the Pigeon Hunter screen
 * Using a data class with copy() for easy state updates
 */
data class PigeonHunterUiState(
    val capturedBitmap: Bitmap? = null,
    val pigeonResult: PigeonDetectionResult? = null,
    val isAnalyzing: Boolean = false,
    val errorMessage: String? = null,
    /** City name where the photo was taken (e.g., "San Francisco") */
    val location: String? = null,
    val isFetchingLocation: Boolean = false
)

/**
 * ViewModel for Pigeon Hunter feature
 * Uses init block instead of a Factory in Activity.
 * Repository is created here so Activity can just do: by viewModels()
 */
class PigeonHunterViewModel : ViewModel() {

    private val repository: PigeonRepository
    private var isLocationPermissionGranted = false

    init {
        val apiService = PigeonRepository.createApiService()
        repository = PigeonRepository(
            apiService = apiService,
            apiKey = BuildConfig.MUSE_API_KEY,
            model = BuildConfig.MUSE_API_MODEL
        )
        Log.d("PigeonHunterVM", "ViewModel init - repo created with model ${BuildConfig.MUSE_API_MODEL}")
    }
    
    // Private mutable state
    private val _uiState = MutableStateFlow(PigeonHunterUiState())
    
    // Public immutable state (exposed to UI)
    val uiState: StateFlow<PigeonHunterUiState> = _uiState.asStateFlow()
    
    /**
     * Called when user takes a photo
     */
    fun onPhotoCaptured(bitmap: Bitmap) {
        Log.d("PigeonHunterVM", "Photo captured, starting analysis")
        _uiState.value = _uiState.value.copy(
            capturedBitmap = bitmap,
            isAnalyzing = true,
            pigeonResult = null,
            errorMessage = null
            // Keep existing location if already fetched, don't clear it
        )
        
        // Start analysis in background
        analyzePhoto(bitmap)
    }

    fun setIsLocationPermissionGranted (isGranted: Boolean) {
        this.isLocationPermissionGranted = isGranted
    }

    /**
     * Called when user wants to take another photo
     */
    fun onRetakePhoto() {
        Log.d("PigeonHunterVM", "Resetting for new photo")
        _uiState.value = PigeonHunterUiState()
    }
    
    /**
     * Analyze the photo using Repository (which calls Muse API)
     */
    private fun analyzePhoto(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                Log.d("PigeonHunterVM", "Starting analysis via Repository...")
                val result = repository.detectPigeon(bitmap)
                
                _uiState.value = _uiState.value.copy(
                    pigeonResult = result,
                    isAnalyzing = false
                )
                
                Log.d("PigeonHunterVM", "Analysis complete. Has pigeon: ${result.hasPigeon}")
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Error analyzing image", e)
                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    errorMessage = "Error: ${e.message}",
                    pigeonResult = PigeonDetectionResult(
                        hasPigeon = false,
                        pigeonType = null,
                        confidence = 0f,
                        features = null,
                        location = null,
                        description = "Error: ${e.message}",
                        rawResponse = ""
                    )
                )
            }
        }
    }

    /**
     * Public API: fetch current location and convert to city string.
     * This is called when user takes a photo AND when user taps "Share Location".
     * 
     * Steps:
     * 1. Get current location via FusedLocationProviderClient (lastLocation -> currentLocation)
     * 2. Reverse-geocode lat/lon to city name using Geocoder
     * 3. Update UI state with city string
     */
    @SuppressLint("MissingPermission") // Permission is checked in Activity before calling
    fun shareLocation(context: Context) {
        if (isLocationPermissionGranted) {
            fetchCurrentLocation(context)
        }
    }


    /**
     * Alias for shareLocation - more descriptive for auto-fetch on photo capture
     */
    @SuppressLint("MissingPermission")
    fun fetchCurrentLocation(context: Context) {
        // Prevent duplicate fetches
        if (_uiState.value.isFetchingLocation) {
            Log.d("PigeonHunterVM", "Already fetching location, skipping")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isFetchingLocation = true,
                errorMessage = null
            )

            try {
                val appContext = context.applicationContext
                val city = getCityFromCurrentLocation(appContext)

                if (city != null) {
                    Log.d("PigeonHunterVM", "Location resolved to city: $city")
                    _uiState.value = _uiState.value.copy(
                        location = city,
                        isFetchingLocation = false
                    )
                } else {
                    Log.w("PigeonHunterVM", "Failed to resolve city from location")
                    _uiState.value = _uiState.value.copy(
                        isFetchingLocation = false,
                        errorMessage = "Unable to determine city from location"
                    )
                }
            } catch (se: SecurityException) {
                Log.e("PigeonHunterVM", "Location permission not granted", se)
                _uiState.value = _uiState.value.copy(
                    isFetchingLocation = false,
                    errorMessage = "Location permission required. Please grant location access."
                )
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Error fetching location", e)
                _uiState.value = _uiState.value.copy(
                    isFetchingLocation = false,
                    errorMessage = "Failed to get location: ${e.message}"
                )
            }
        }
    }

    /**
     * Core logic: Get current lat/lon and reverse-geocode to city name
     */
    @SuppressLint("MissingPermission")
    private suspend fun getCityFromCurrentLocation(context: Context): String? {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        // Step 1: Try last known location first (fast, no extra battery)
        var location = awaitLastLocation(fusedClient)
        Log.d("PigeonHunterVM", "Last location: $location")

        // Step 2: If last location is null, request fresh high-accuracy location
        if (location == null) {
            Log.d("PigeonHunterVM", "Last location null, requesting current location...")
            location = awaitCurrentLocation(fusedClient)
            Log.d("PigeonHunterVM", "Current location: $location")
        }

        if (location == null) {
            Log.w("PigeonHunterVM", "Could not obtain any location")
            return null
        }

        // Step 3: Convert lat/lon to city string via Geocoder
        return reverseGeocodeToCity(context, location.latitude, location.longitude)
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitLastLocation(
        client: com.google.android.gms.location.FusedLocationProviderClient
    ): android.location.Location? = suspendCancellableCoroutine { cont ->
        client.lastLocation
            .addOnSuccessListener { loc -> cont.resume(loc) {} }
            .addOnFailureListener { cont.resume(null) {} }
            .addOnCanceledListener { cont.cancel() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitCurrentLocation(
        client: com.google.android.gms.location.FusedLocationProviderClient
    ): android.location.Location? = suspendCancellableCoroutine { cont ->
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc -> cont.resume(loc) {} }
            .addOnFailureListener { e ->
                Log.e("PigeonHunterVM", "getCurrentLocation failed", e)
                cont.resume(null) {}
            }
            .addOnCanceledListener { cont.cancel() }
    }

    /**
     * Reverse geocode lat/lon to city name
     * Handles both pre-Tiramisu (sync) and Tiramisu+ (async callback) APIs
     */
    private suspend fun reverseGeocodeToCity(context: Context, latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())

                val city = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // API 33+ uses async callback
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                            val result = addresses.firstOrNull()?.let { addr ->
                                // Prefer locality (city), fall back to sub-admin, admin, country
                                addr.locality
                                    ?: addr.subAdminArea
                                    ?: addr.adminArea
                                    ?: addr.countryName
                                    ?: "${addr.latitude},${addr.longitude}"
                            }
                            cont.resume(result) {}
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                    addresses?.firstOrNull()?.let { addr ->
                        addr.locality
                            ?: addr.subAdminArea
                            ?: addr.adminArea
                            ?: addr.countryName
                            ?: "${addr.latitude},${addr.longitude}"
                    }
                }

                Log.d("PigeonHunterVM", "Geocoded $latitude,$longitude -> $city")
                city
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Geocoding failed for $latitude,$longitude", e)
                null
            }
        }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
