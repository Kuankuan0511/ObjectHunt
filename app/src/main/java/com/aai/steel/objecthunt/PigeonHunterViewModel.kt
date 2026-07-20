package com.aai.steel.objecthunt

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
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
    val isFetchingLocation: Boolean = false,
    val isSaving: Boolean = false,
    val saveMessage: String? = null,
    val savedCount: Int = 0,
    // Queue for offline detection
    val queuedCount: Int = 0,
    val isSyncingQueue: Boolean = false,
    val queueMessage: String? = null
)

/**
 * ViewModel using init block instead of a Factory in Activity.
 * Repository is created in init, Activity can just do: by viewModels()
 * 
 * Uses Activity Context passed from caller (converted to applicationContext internally
 * for FusedLocationProviderClient to avoid leaks).
 */
class PigeonHunterViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PigeonRepository
    private val savedRepository: com.aai.steel.objecthunt.data.SavedPigeonRepository
    private val queueRepository: com.aai.steel.objecthunt.data.DetectionQueueRepository
    private val networkMonitor: com.aai.steel.objecthunt.data.NetworkMonitor

    init {
        val apiService = PigeonRepository.createApiService()
        repository = PigeonRepository(
            apiService = apiService,
            apiKey = BuildConfig.MUSE_API_KEY,
            model = BuildConfig.MUSE_API_MODEL
        )
        savedRepository = com.aai.steel.objecthunt.data.SavedPigeonRepository.fromContext(getApplication())
        queueRepository = com.aai.steel.objecthunt.data.DetectionQueueRepository.fromContext(
            getApplication(), repository
        )
        networkMonitor = com.aai.steel.objecthunt.data.NetworkMonitor(getApplication())
        Log.d("PigeonHunterVM", "ViewModel init - repo created with model ${BuildConfig.MUSE_API_MODEL}")

        // Load saved count initially and keep it in sync via Flow
        viewModelScope.launch {
            try {
                val count = savedRepository.getCount()
                _uiState.value = _uiState.value.copy(savedCount = count)
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Failed to load saved count", e)
            }
        }

        // Keep savedCount always in sync with DB (fixes UI bug where count resets to 0)
        viewModelScope.launch {
            try {
                savedRepository.getSavedPigeonsFlow().collect { list ->
                    _uiState.value = _uiState.value.copy(savedCount = list.size)
                }
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Failed to collect saved flow", e)
            }
        }

        // Keep queuedCount in sync
        viewModelScope.launch {
            try {
                queueRepository.getQueuedFlow().collect { list ->
                    _uiState.value = _uiState.value.copy(queuedCount = list.size)
                }
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Failed to collect queued flow", e)
            }
        }

        // Auto-sync when connectivity restored - handles race conditions via distinctUntilChanged + Mutex
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

    // Expose saved pigeons flow for history screen
    val savedPigeonsFlow = savedRepository.getSavedPigeonsFlow()
    
    fun onPhotoCaptured(bitmap: Bitmap) {
        Log.d("PigeonHunterVM", "Photo captured, starting analysis")
        _uiState.value = _uiState.value.copy(
            capturedBitmap = bitmap,
            isAnalyzing = true,
            pigeonResult = null,
            errorMessage = null,
            saveMessage = null // clear previous save message on new photo
        )
        analyzePhoto(bitmap)
        // Don't call fetchCurrentLocation() here - Activity handles it via fetchCityIfPermittedSilent()
        // with permission check at launch, avoiding double fetch and SecurityException
    }

    fun onRetakePhoto() {
        Log.d("PigeonHunterVM", "Resetting for new photo, preserving savedCount=${_uiState.value.savedCount}")
        // Preserve savedCount - was bug: PigeonHunterUiState() reset it to 0
        val currentSavedCount = _uiState.value.savedCount
        _uiState.value = PigeonHunterUiState(savedCount = currentSavedCount)
    }
    
    private fun analyzePhoto(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                Log.d("PigeonHunterVM", "Starting analysis via Repository...")
                val result = repository.detectPigeon(bitmap)

                // Check if result is actually a network error - queue if so
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
                // Check if exception is network-related -> queue with backoff
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

    /**
     * Manually trigger sync of queued detections - with exponential backoff and concurrency protection
     * Now with auto-retry: if sync has failures, schedule next sync after min(nextRetryAt - now)
     */
    fun syncQueuedDetections() {
        if (_uiState.value.isSyncingQueue) {
            Log.d("PigeonHunterVM", "Already syncing, skipping")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncingQueue = true, queueMessage = "Syncing queued detections...")
            try {
                val result = queueRepository.syncPending(getApplication())
                when (result) {
                    is com.aai.steel.objecthunt.data.DetectionQueueRepository.SyncResult.Synced -> {
                        if (result.failed > 0) {
                            // Some failed, need to schedule auto-retry with backoff
                            val allQueued = queueRepository.getQueuedDao().getAll()
                            val retrying = allQueued.filter { it.status == "RETRYING" }
                            if (retrying.isNotEmpty()) {
                                val minNextRetry = retrying.minOf { it.nextRetryAt }
                                val delayMs = (minNextRetry - System.currentTimeMillis()).coerceAtLeast(0L)
                                _uiState.value = _uiState.value.copy(
                                    isSyncingQueue = false,
                                    queueMessage = "Synced ${result.success}, ${result.failed} failed - retrying in ${delayMs/1000}s (backoff)"
                                )
                                // Auto-retry after backoff delay - this makes backoff actually run
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
                    is com.aai.steel.objecthunt.data.DetectionQueueRepository.SyncResult.NoNetwork -> {
                        _uiState.value = _uiState.value.copy(
                            isSyncingQueue = false,
                            queueMessage = "Still offline - queued ${result}"
                        )
                    }
                    is com.aai.steel.objecthunt.data.DetectionQueueRepository.SyncResult.NothingToSync -> {
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
                val city = getCityFromCurrentLocation(getApplication())

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
    
    /**
     * Save current hunt to local DB (max 20, oldest deleted)
     * Repository handles limit enforcement
     */
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
                    is com.aai.steel.objecthunt.data.SavedPigeonRepository.SaveResult.Saved -> {
                        val newCount = savedRepository.getCount()
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            savedCount = newCount,
                            saveMessage = if (newCount >= 20) "Saved! Oldest deleted (max 20)" else "Saved! ($newCount/20)"
                        )
                        Log.d("PigeonHunterVM", "Saved pigeon id=${saveResult.id}, count=$newCount")
                    }
                    is com.aai.steel.objecthunt.data.SavedPigeonRepository.SaveResult.AlreadyExists -> {
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

    // For history screen - delete
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
