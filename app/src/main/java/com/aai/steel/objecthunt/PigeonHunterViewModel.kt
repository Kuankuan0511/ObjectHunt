package com.aai.steel.objecthunt

import android.annotation.SuppressLint
import android.app.Application
import android.location.Geocoder
import android.os.Build
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
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

/**
 * UI State for the Pigeon Hunter screen
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
 * ViewModel using ApplicationContext instead of Activity Context.
 * Extends AndroidViewModel so we can get applicationContext via getApplication().
 * This avoids memory leaks from holding an Activity reference.
 */
class PigeonHunterViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PigeonRepository
    private val appContext = getApplication<Application>().applicationContext

    init {
        val apiService = PigeonRepository.createApiService()
        repository = PigeonRepository(
            apiService = apiService,
            apiKey = BuildConfig.MUSE_API_KEY,
            model = BuildConfig.MUSE_API_MODEL
        )
        Log.d("PigeonHunterVM", "ViewModel init with applicationContext - model ${BuildConfig.MUSE_API_MODEL}")
    }
    
    private val _uiState = MutableStateFlow(PigeonHunterUiState())
    val uiState: StateFlow<PigeonHunterUiState> = _uiState.asStateFlow()
    
    fun onPhotoCaptured(bitmap: Bitmap) {
        Log.d("PigeonHunterVM", "Photo captured, starting analysis")
        _uiState.value = _uiState.value.copy(
            capturedBitmap = bitmap,
            isAnalyzing = true,
            pigeonResult = null,
            errorMessage = null
        )
        analyzePhoto(bitmap)
    }

    fun onRetakePhoto() {
        Log.d("PigeonHunterVM", "Resetting for new photo")
        _uiState.value = PigeonHunterUiState()
    }
    
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
     * Public API: shareLocation - uses applicationContext internally,
     * no Activity context needed from caller.
     */
    fun shareLocation() {
        fetchCurrentLocation()
    }

    /**
     * Fetch current location and convert to city string using applicationContext.
     * No Context param needed - uses appContext from AndroidViewModel.
     */
    @SuppressLint("MissingPermission")
    fun fetchCurrentLocation() {
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
                val city = getCityFromCurrentLocation()

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

    @SuppressLint("MissingPermission")
    private suspend fun getCityFromCurrentLocation(): String? {
        val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)

        var location = awaitLastLocation(fusedClient)
        Log.d("PigeonHunterVM", "Last location: $location")

        if (location == null) {
            Log.d("PigeonHunterVM", "Last location null, requesting current location...")
            location = awaitCurrentLocation(fusedClient)
            Log.d("PigeonHunterVM", "Current location: $location")
        }

        if (location == null) {
            Log.w("PigeonHunterVM", "Could not obtain any location")
            return null
        }

        return reverseGeocodeToCity(location.latitude, location.longitude)
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

    private suspend fun reverseGeocodeToCity(latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(appContext, Locale.getDefault())

                val city = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                            val result = addresses.firstOrNull()?.let { addr ->
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
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
