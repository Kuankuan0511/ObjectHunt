package com.aai.steel.objecthunt

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aai.steel.objecthunt.data.DetectionQueueRepository
import com.aai.steel.objecthunt.data.NetworkMonitor
import com.aai.steel.objecthunt.data.SavedPigeonRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * UI State for the Pigeon Hunter screen
 */
data class PigeonHunterUiState(
    val capturedBitmap: Bitmap? = null,
    val pigeonResult: PigeonDetectionResult? = null,
    val isAnalyzing: Boolean = false,
    val errorMessage: String? = null,
    val location: String? = null,
    val isFetchingLocation: Boolean = false,
    val isSaving: Boolean = false,
    val saveMessage: String? = null,
    val savedCount: Int = 0,
    val queuedCount: Int = 0,
    val isSyncingQueue: Boolean = false,
    val queueMessage: String? = null
)

/**
 * ViewModel with Hilt DI - dependencies injected, no manual creation
 * Uses applicationContext via Hilt @ApplicationContext qualifier
 */
@HiltViewModel
class PigeonHunterViewModel @Inject constructor(
    private val repository: PigeonRepository,
    private val savedRepository: SavedPigeonRepository,
    private val queueRepository: DetectionQueueRepository,
    private val networkMonitor: NetworkMonitor,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    init {
        Log.d("PigeonHunterVM", "Hilt ViewModel init - all repos injected")

        // Keep savedCount in sync
        viewModelScope.launch {
            try {
                val count = savedRepository.getCount()
                _uiState.value = _uiState.value.copy(savedCount = count)
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Failed to load saved count", e)
            }
        }

        viewModelScope.launch {
            try {
                savedRepository.getSavedPigeonsFlow().collect { list ->
                    _uiState.value = _uiState.value.copy(savedCount = list.size)
                }
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Failed to collect saved flow", e)
            }
        }

        viewModelScope.launch {
            try {
                queueRepository.getQueuedFlow().collect { list ->
                    _uiState.value = _uiState.value.copy(queuedCount = list.size)
                }
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Failed to collect queued flow", e)
            }
        }

        // Auto-sync when connectivity restored
        viewModelScope.launch {
            try {
                networkMonitor.observe().collect { isAvailable ->
                    Log.d("PigeonHunterVM", "Network available: $isAvailable, queued=${_uiState.value.queuedCount}")
                    if (isAvailable && _uiState.value.queuedCount > 0) {
                        syncQueuedDetections()
                    }
                }
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Network monitor failed", e)
            }
        }
    }
    
    private val _uiState = MutableStateFlow(PigeonHunterUiState())
    val uiState: StateFlow<PigeonHunterUiState> = _uiState.asStateFlow()

    val savedPigeonsFlow = savedRepository.getSavedPigeonsFlow()
    
    fun onPhotoCaptured(bitmap: Bitmap) {
        Log.d("PigeonHunterVM", "Photo captured, starting analysis")
        _uiState.value = _uiState.value.copy(
            capturedBitmap = bitmap,
            isAnalyzing = true,
            pigeonResult = null,
            errorMessage = null,
            saveMessage = null
        )
        analyzePhoto(bitmap)
    }

    fun onRetakePhoto() {
        Log.d("PigeonHunterVM", "Resetting for new photo, preserving savedCount=${_uiState.value.savedCount}")
        val currentSavedCount = _uiState.value.savedCount
        _uiState.value = PigeonHunterUiState(savedCount = currentSavedCount)
    }
    
    private fun analyzePhoto(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                Log.d("PigeonHunterVM", "Starting analysis via Repository...")
                val result = repository.detectPigeon(bitmap)

                // Queue if network error and no network
                val isNetworkError = result.description.contains("No internet", ignoreCase = true) ||
                        result.description.contains("Failed to analyze", ignoreCase = true) &&
                        (result.description.contains("Unable to resolve host", ignoreCase = true) ||
                        result.description.contains("timeout", ignoreCase = true))

                if (isNetworkError && !networkMonitor.isCurrentlyAvailable()) {
                    Log.d("PigeonHunterVM", "Network down, queuing detection")
                    queueRepository.enqueue(bitmap, _uiState.value.location)
                    _uiState.value = _uiState.value.copy(
                        isAnalyzing = false,
                        queueMessage = "No internet - queued (${_uiState.value.queuedCount + 1} pending), will sync when online",
                        pigeonResult = PigeonDetectionResult(
                            hasPigeon = false,
                            pigeonType = null,
                            confidence = 0f,
                            features = null,
                            location = null,
                            description = "Queued for later - no internet",
                            rawResponse = ""
                        )
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        pigeonResult = result,
                        isAnalyzing = false
                    )
                    Log.d("PigeonHunterVM", "Analysis complete. Has pigeon: ${result.hasPigeon}")
                }

            } catch (e: SecurityException) {
                Log.e("PigeonHunterVM", "Location permission is not granted", e)

            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Error analyzing image", e)
                val isNetwork = e.message?.let {
                    it.contains("Unable to resolve host", ignoreCase = true) ||
                    it.contains("timeout", ignoreCase = true) ||
                    it.contains("No internet", ignoreCase = true)
                } ?: false

                if (isNetwork) {
                    try {
                        queueRepository.enqueue(bitmap, _uiState.value.location)
                        _uiState.value = _uiState.value.copy(
                            isAnalyzing = false,
                            queueMessage = "Network error - queued, will retry with backoff",
                            pigeonResult = PigeonDetectionResult(
                                hasPigeon = false,
                                pigeonType = null,
                                confidence = 0f,
                                features = null,
                                location = null,
                                description = "Queued due to network error",
                                rawResponse = ""
                            )
                        )
                    } catch (queueEx: Exception) {
                        Log.e("PigeonHunterVM", "Failed to queue", queueEx)
                        _uiState.value = _uiState.value.copy(
                            isAnalyzing = false,
                            errorMessage = "Error: ${e.message}"
                        )
                    }
                } else {
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
    }

    fun syncQueuedDetections() {
        if (_uiState.value.isSyncingQueue) {
            Log.d("PigeonHunterVM", "Already syncing, skipping")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncingQueue = true, queueMessage = "Syncing queued detections...")
            try {
                val result = queueRepository.syncPending(appContext)
                when (result) {
                    is DetectionQueueRepository.SyncResult.Synced -> {
                        if (result.failed > 0) {
                            val allQueued = queueRepository.getAllQueued()
                            val retrying = allQueued.filter { it.status == "RETRYING" }
                            if (retrying.isNotEmpty()) {
                                val minNextRetry = retrying.minOf { it.nextRetryAt }
                                val delayMs = (minNextRetry - System.currentTimeMillis()).coerceAtLeast(0L)
                                _uiState.value = _uiState.value.copy(
                                    isSyncingQueue = false,
                                    queueMessage = "Synced ${result.success}, ${result.failed} failed - retrying in ${delayMs/1000}s (backoff)"
                                )
                                viewModelScope.launch {
                                    kotlinx.coroutines.delay(delayMs)
                                    if (_uiState.value.queuedCount > 0) {
                                        Log.d("PigeonHunterVM", "Auto-retrying after backoff ${delayMs}ms")
                                        syncQueuedDetections()
                                    }
                                }
                            } else {
                                _uiState.value = _uiState.value.copy(
                                    isSyncingQueue = false,
                                    queueMessage = "Synced ${result.success} queued, ${result.failed} failed"
                                )
                            }
                        } else {
                            _uiState.value = _uiState.value.copy(
                                isSyncingQueue = false,
                                queueMessage = if (result.success > 0) "Synced ${result.success} queued!" else null
                            )
                        }
                    }
                    is DetectionQueueRepository.SyncResult.NoNetwork -> {
                        _uiState.value = _uiState.value.copy(
                            isSyncingQueue = false,
                            queueMessage = "Still offline - queued ${result}"
                        )
                    }
                    is DetectionQueueRepository.SyncResult.NothingToSync -> {
                        _uiState.value = _uiState.value.copy(isSyncingQueue = false, queueMessage = null)
                    }
                }
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Sync failed", e)
                _uiState.value = _uiState.value.copy(
                    isSyncingQueue = false,
                    queueMessage = "Sync failed: ${e.message}"
                )
            }
        }
    }

    fun clearQueueMessage() {
        _uiState.value = _uiState.value.copy(queueMessage = null)
    }

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

    @SuppressLint("MissingPermission")
    private suspend fun getCityFromCurrentLocation(context: Context): String? {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

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

    private suspend fun reverseGeocodeToCity(context: Context, latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())

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
    
    fun onSaveCurrent() {
        val bitmap = _uiState.value.capturedBitmap
        val result = _uiState.value.pigeonResult
        val city = _uiState.value.location

        if (bitmap == null) {
            _uiState.value = _uiState.value.copy(saveMessage = "No photo to save")
            return
        }

        if (_uiState.value.isSaving) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, saveMessage = null, errorMessage = null)
            try {
                when (val saveResult = savedRepository.savePigeon(bitmap, result, city)) {
                    is SavedPigeonRepository.SaveResult.Saved -> {
                        val newCount = savedRepository.getCount()
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            savedCount = newCount,
                            saveMessage = if (newCount >= 20) "Saved! Oldest deleted (max 20)" else "Saved! ($newCount/20)"
                        )
                        Log.d("PigeonHunterVM", "Saved pigeon id=${saveResult.id}, count=$newCount")
                    }
                    is SavedPigeonRepository.SaveResult.AlreadyExists -> {
                        val count = savedRepository.getCount()
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            savedCount = count,
                            saveMessage = "Already saved! (id=${saveResult.existingId})"
                        )
                        Log.d("PigeonHunterVM", "Duplicate detected, existingId=${saveResult.existingId}")
                    }
                }
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Failed to save pigeon", e)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "Failed to save: ${e.message}"
                )
            }
        }
    }

    fun clearSaveMessage() {
        _uiState.value = _uiState.value.copy(saveMessage = null)
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun deleteSaved(id: Long) {
        viewModelScope.launch {
            try {
                savedRepository.deleteById(id)
                val count = savedRepository.getCount()
                _uiState.value = _uiState.value.copy(savedCount = count, saveMessage = "Deleted")
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Failed to delete $id", e)
            }
        }
    }
}
