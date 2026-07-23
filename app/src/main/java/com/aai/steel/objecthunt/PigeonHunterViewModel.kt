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
 * Sealed UI State - replaces boolean soup (isAnalyzing, isSaving, etc.)
 * Each state only holds data it needs, impossible states impossible by construction.
 */
sealed interface PigeonHunterUiState {
    val savedCount: Int
    val queuedCount: Int
    val location: String?
    val isFetchingLocation: Boolean
    val saveMessage: String?
    val queueMessage: String?
    val isSaving: Boolean
    val isSyncingQueue: Boolean
    val userQuery: String
    val customAnswer: String?
    val isAskingCustom: Boolean

    data class Initial(
        override val savedCount: Int = 0,
        override val queuedCount: Int = 0,
        override val location: String? = null,
        override val isFetchingLocation: Boolean = false,
        override val saveMessage: String? = null,
        override val queueMessage: String? = null,
        override val isSaving: Boolean = false,
        override val isSyncingQueue: Boolean = false,
        override val userQuery: String = "",
        override val customAnswer: String? = null,
        override val isAskingCustom: Boolean = false
    ) : PigeonHunterUiState

    data class PhotoCaptured(
        val bitmap: Bitmap,
        override val savedCount: Int,
        override val queuedCount: Int,
        override val location: String?,
        override val isFetchingLocation: Boolean,
        override val saveMessage: String? = null,
        override val queueMessage: String? = null,
        override val isSaving: Boolean = false,
        override val isSyncingQueue: Boolean = false,
        override val userQuery: String = "",
        override val customAnswer: String? = null,
        override val isAskingCustom: Boolean = false
    ) : PigeonHunterUiState

    data class Analyzing(
        val bitmap: Bitmap,
        override val savedCount: Int,
        override val queuedCount: Int,
        override val location: String?,
        override val isFetchingLocation: Boolean,
        override val saveMessage: String? = null,
        override val queueMessage: String? = null,
        override val isSaving: Boolean = false,
        override val isSyncingQueue: Boolean = false,
        override val userQuery: String = "",
        override val customAnswer: String? = null,
        override val isAskingCustom: Boolean = false
    ) : PigeonHunterUiState

    data class Success(
        val bitmap: Bitmap,
        val result: PigeonDetectionResult,
        override val savedCount: Int,
        override val queuedCount: Int,
        override val location: String?,
        override val isFetchingLocation: Boolean,
        override val isSaving: Boolean = false,
        override val saveMessage: String? = null,
        override val queueMessage: String? = null,
        override val isSyncingQueue: Boolean = false,
        override val userQuery: String = "",
        override val customAnswer: String? = null,
        override val isAskingCustom: Boolean = false
    ) : PigeonHunterUiState

    data class Queued(
        val bitmap: Bitmap,
        override val savedCount: Int,
        override val queuedCount: Int,
        override val location: String?,
        override val isFetchingLocation: Boolean,
        override val queueMessage: String,
        val result: PigeonDetectionResult? = null,
        override val saveMessage: String? = null,
        override val isSaving: Boolean = false,
        override val isSyncingQueue: Boolean = false,
        override val userQuery: String = "",
        override val customAnswer: String? = null,
        override val isAskingCustom: Boolean = false
    ) : PigeonHunterUiState

    data class Error(
        val bitmap: Bitmap?,
        val message: String,
        override val savedCount: Int,
        override val queuedCount: Int,
        override val location: String?,
        override val isFetchingLocation: Boolean,
        override val saveMessage: String? = null,
        override val queueMessage: String? = null,
        override val isSaving: Boolean = false,
        override val isSyncingQueue: Boolean = false,
        override val userQuery: String = "",
        override val customAnswer: String? = null,
        override val isAskingCustom: Boolean = false
    ) : PigeonHunterUiState

    data class Saving(
        val bitmap: Bitmap,
        val result: PigeonDetectionResult,
        override val savedCount: Int,
        override val queuedCount: Int,
        override val location: String?,
        override val isFetchingLocation: Boolean,
        override val saveMessage: String? = null,
        override val queueMessage: String? = null,
        override val isSaving: Boolean = true,
        override val isSyncingQueue: Boolean = false,
        override val userQuery: String = "",
        override val customAnswer: String? = null,
        override val isAskingCustom: Boolean = false
    ) : PigeonHunterUiState

    data class SyncingQueue(
        val bitmap: Bitmap?,
        override val savedCount: Int,
        override val queuedCount: Int,
        override val location: String?,
        override val isFetchingLocation: Boolean,
        val result: PigeonDetectionResult? = null,
        override val saveMessage: String? = null,
        override val queueMessage: String? = null,
        override val isSaving: Boolean = false,
        override val isSyncingQueue: Boolean = true,
        override val userQuery: String = "",
        override val customAnswer: String? = null,
        override val isAskingCustom: Boolean = false
    ) : PigeonHunterUiState
}

/**
 * Extension to update common fields across all sealed states
 */
private fun PigeonHunterUiState.withCounts(
    savedCount: Int = this.savedCount,
    queuedCount: Int = this.queuedCount,
    location: String? = this.location,
    isFetchingLocation: Boolean = this.isFetchingLocation
): PigeonHunterUiState {
    return when (this) {
        is PigeonHunterUiState.Initial -> copy(savedCount = savedCount, queuedCount = queuedCount, location = location, isFetchingLocation = isFetchingLocation)
        is PigeonHunterUiState.PhotoCaptured -> copy(savedCount = savedCount, queuedCount = queuedCount, location = location, isFetchingLocation = isFetchingLocation)
        is PigeonHunterUiState.Analyzing -> copy(savedCount = savedCount, queuedCount = queuedCount, location = location, isFetchingLocation = isFetchingLocation)
        is PigeonHunterUiState.Success -> copy(savedCount = savedCount, queuedCount = queuedCount, location = location, isFetchingLocation = isFetchingLocation)
        is PigeonHunterUiState.Queued -> copy(savedCount = savedCount, queuedCount = queuedCount, location = location, isFetchingLocation = isFetchingLocation)
        is PigeonHunterUiState.Error -> copy(savedCount = savedCount, queuedCount = queuedCount, location = location, isFetchingLocation = isFetchingLocation)
        is PigeonHunterUiState.Saving -> copy(savedCount = savedCount, queuedCount = queuedCount, location = location, isFetchingLocation = isFetchingLocation)
        is PigeonHunterUiState.SyncingQueue -> copy(savedCount = savedCount, queuedCount = queuedCount, location = location, isFetchingLocation = isFetchingLocation)
    }
}

/**
 * ViewModel with Hilt DI - now uses sealed state
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
        Log.d("PigeonHunterVM", "Hilt ViewModel init with sealed state")

        viewModelScope.launch {
            try {
                val count = savedRepository.getCount()
                _uiState.value = _uiState.value.withCounts(savedCount = count)
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Failed to load saved count", e)
            }
        }

        viewModelScope.launch {
            try {
                savedRepository.getSavedPigeonsFlow().collect { list ->
                    _uiState.value = _uiState.value.withCounts(savedCount = list.size)
                }
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Failed to collect saved flow", e)
            }
        }

        viewModelScope.launch {
            try {
                queueRepository.getQueuedFlow().collect { list ->
                    _uiState.value = _uiState.value.withCounts(queuedCount = list.size)
                }
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Failed to collect queued flow", e)
            }
        }

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
    
    private val _uiState = MutableStateFlow<PigeonHunterUiState>(PigeonHunterUiState.Initial())
    val uiState: StateFlow<PigeonHunterUiState> = _uiState.asStateFlow()

    val savedPigeonsFlow = savedRepository.getSavedPigeonsFlow()
    
    fun onPhotoCaptured(bitmap: Bitmap) {
        Log.d("PigeonHunterVM", "Photo captured, starting analysis")
        val current = _uiState.value
        _uiState.value = PigeonHunterUiState.Analyzing(
            bitmap = bitmap,
            savedCount = current.savedCount,
            queuedCount = current.queuedCount,
            location = current.location,
            isFetchingLocation = current.isFetchingLocation
        )
        analyzePhoto(bitmap)
    }

    fun onRetakePhoto() {
        Log.d("PigeonHunterVM", "Resetting, preserving counts")
        val current = _uiState.value
        _uiState.value = PigeonHunterUiState.Initial(
            savedCount = current.savedCount,
            queuedCount = current.queuedCount,
            location = null,
            isFetchingLocation = false
        )
    }
    
    private fun analyzePhoto(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                Log.d("PigeonHunterVM", "Starting analysis via Repository...")
                val result = repository.detectPigeon(bitmap)

                val current = _uiState.value
                val isNetworkError = result.description.contains("No internet", ignoreCase = true) ||
                        result.description.contains("Failed to analyze", ignoreCase = true) &&
                        (result.description.contains("Unable to resolve host", ignoreCase = true) ||
                        result.description.contains("timeout", ignoreCase = true))

                if (isNetworkError && !networkMonitor.isCurrentlyAvailable()) {
                    Log.d("PigeonHunterVM", "Network down, queuing")
                    queueRepository.enqueue(bitmap, current.location)
                    _uiState.value = PigeonHunterUiState.Queued(
                        bitmap = bitmap,
                        savedCount = current.savedCount,
                        queuedCount = current.queuedCount + 1,
                        location = current.location,
                        isFetchingLocation = false,
                        queueMessage = "No internet - queued, will sync when online",
                        result = PigeonDetectionResult(
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
                    _uiState.value = PigeonHunterUiState.Success(
                        bitmap = bitmap,
                        result = result,
                        savedCount = current.savedCount,
                        queuedCount = current.queuedCount,
                        location = current.location,
                        isFetchingLocation = false
                    )
                    Log.d("PigeonHunterVM", "Analysis complete. Has pigeon: ${result.hasPigeon}")
                }

            } catch (e: SecurityException) {
                Log.e("PigeonHunterVM", "Location permission not granted", e)

            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Error analyzing image", e)
                val current = _uiState.value
                val bitmap = when (current) {
                    is PigeonHunterUiState.Analyzing -> current.bitmap
                    is PigeonHunterUiState.PhotoCaptured -> current.bitmap
                    else -> null
                }
                val isNetwork = e.message?.let {
                    it.contains("Unable to resolve host", ignoreCase = true) ||
                    it.contains("timeout", ignoreCase = true) ||
                    it.contains("No internet", ignoreCase = true)
                } ?: false

                if (isNetwork && bitmap != null) {
                    try {
                        queueRepository.enqueue(bitmap, current.location)
                        _uiState.value = PigeonHunterUiState.Queued(
                            bitmap = bitmap,
                            savedCount = current.savedCount,
                            queuedCount = current.queuedCount + 1,
                            location = current.location,
                            isFetchingLocation = false,
                            queueMessage = "Network error - queued, will retry with backoff"
                        )
                    } catch (queueEx: Exception) {
                        Log.e("PigeonHunterVM", "Failed to queue", queueEx)
                        _uiState.value = PigeonHunterUiState.Error(
                            bitmap = bitmap,
                            message = "Error: ${e.message}",
                            savedCount = current.savedCount,
                            queuedCount = current.queuedCount,
                            location = current.location,
                            isFetchingLocation = false
                        )
                    }
                } else {
                    _uiState.value = PigeonHunterUiState.Error(
                        bitmap = bitmap,
                        message = "Error: ${e.message}",
                        savedCount = current.savedCount,
                        queuedCount = current.queuedCount,
                        location = current.location,
                        isFetchingLocation = false
                    )
                }
            }
        }
    }

    fun syncQueuedDetections() {
        val current = _uiState.value
        if (current is PigeonHunterUiState.SyncingQueue) {
            Log.d("PigeonHunterVM", "Already syncing, skipping")
            return
        }

        viewModelScope.launch {
            val syncBitmap = when (current) {
                is PigeonHunterUiState.Success -> current.bitmap
                is PigeonHunterUiState.Queued -> current.bitmap
                else -> null
            }

            _uiState.value = PigeonHunterUiState.SyncingQueue(
                bitmap = syncBitmap,
                savedCount = current.savedCount,
                queuedCount = current.queuedCount,
                location = current.location,
                isFetchingLocation = current.isFetchingLocation,
                result = (current as? PigeonHunterUiState.Success)?.result,
                saveMessage = (current as? PigeonHunterUiState.Success)?.saveMessage
            )

            try {
                val result = queueRepository.syncPending(appContext)
                val afterSync = _uiState.value
                when (result) {
                    is DetectionQueueRepository.SyncResult.Synced -> {
                        if (result.failed > 0) {
                            val allQueued = queueRepository.getAllQueued()
                            val retrying = allQueued.filter { it.status == "RETRYING" }
                            if (retrying.isNotEmpty()) {
                                val minNextRetry = retrying.minOf { it.nextRetryAt }
                                val delayMs = (minNextRetry - System.currentTimeMillis()).coerceAtLeast(0L)
                                _uiState.value = PigeonHunterUiState.Success(
                                    bitmap = syncBitmap ?: return@launch,
                                    result = (afterSync as? PigeonHunterUiState.SyncingQueue)?.result
                                        ?: PigeonDetectionResult(false, null, 0f, null, null, "Synced", ""),
                                    savedCount = afterSync.savedCount,
                                    queuedCount = afterSync.queuedCount,
                                    location = afterSync.location,
                                    isFetchingLocation = false,
                                    saveMessage = afterSync.saveMessage,
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
                                _uiState.value = PigeonHunterUiState.Success(
                                    bitmap = syncBitmap ?: return@launch,
                                    result = (afterSync as? PigeonHunterUiState.SyncingQueue)?.result
                                        ?: PigeonDetectionResult(false, null, 0f, null, null, "Synced", ""),
                                    savedCount = afterSync.savedCount,
                                    queuedCount = afterSync.queuedCount,
                                    location = afterSync.location,
                                    isFetchingLocation = false,
                                    saveMessage = "Synced ${result.success} queued!"
                                )
                            }
                        } else {
                            _uiState.value = when (afterSync) {
                                is PigeonHunterUiState.SyncingQueue -> PigeonHunterUiState.Success(
                                    bitmap = syncBitmap ?: return@launch,
                                    result = afterSync.result ?: PigeonDetectionResult(false, null, 0f, null, null, "Synced", ""),
                                    savedCount = afterSync.savedCount,
                                    queuedCount = afterSync.queuedCount,
                                    location = afterSync.location,
                                    isFetchingLocation = false,
                                    saveMessage = if (result.success > 0) "Synced ${result.success} queued!" else null
                                )
                                else -> afterSync.withCounts()
                            }
                        }
                    }
                    is DetectionQueueRepository.SyncResult.NoNetwork -> {
                        _uiState.value = PigeonHunterUiState.Success(
                            bitmap = syncBitmap ?: return@launch,
                            result = (afterSync as? PigeonHunterUiState.SyncingQueue)?.result
                                ?: PigeonDetectionResult(false, null, 0f, null, null, "", ""),
                            savedCount = afterSync.savedCount,
                            queuedCount = afterSync.queuedCount,
                            location = afterSync.location,
                            isFetchingLocation = false,
                            queueMessage = "Still offline"
                        )
                    }
                    is DetectionQueueRepository.SyncResult.NothingToSync -> {
                        _uiState.value = afterSync.withCounts()
                    }
                }
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Sync failed", e)
                val after = _uiState.value
                _uiState.value = PigeonHunterUiState.Error(
                    bitmap = syncBitmap,
                    message = "Sync failed: ${e.message}",
                    savedCount = after.savedCount,
                    queuedCount = after.queuedCount,
                    location = after.location,
                    isFetchingLocation = false
                )
            }
        }
    }

    fun clearQueueMessage() {
        val current = _uiState.value
        _uiState.value = when (current) {
            is PigeonHunterUiState.Success -> current.copy(queueMessage = null)
            is PigeonHunterUiState.Error -> current.copy(queueMessage = null)
            is PigeonHunterUiState.Initial -> current.copy(queueMessage = null)
            else -> current
        }
    }

    @SuppressLint("MissingPermission")
    fun fetchCurrentLocation() {
        if (_uiState.value.isFetchingLocation) {
            Log.d("PigeonHunterVM", "Already fetching location, skipping")
            return
        }

        viewModelScope.launch {
            val current = _uiState.value
            _uiState.value = current.withCounts(isFetchingLocation = true)

            try {
                val city = getCityFromCurrentLocation(appContext)

                val after = _uiState.value
                if (city != null) {
                    Log.d("PigeonHunterVM", "Location resolved to city: $city")
                    _uiState.value = after.withCounts(location = city, isFetchingLocation = false)
                } else {
                    Log.w("PigeonHunterVM", "Failed to resolve city from location")
                    _uiState.value = PigeonHunterUiState.Error(
                        bitmap = when (after) {
                            is PigeonHunterUiState.Success -> after.bitmap
                            is PigeonHunterUiState.Analyzing -> after.bitmap
                            is PigeonHunterUiState.PhotoCaptured -> after.bitmap
                            is PigeonHunterUiState.Queued -> after.bitmap
                            else -> null
                        },
                        message = "Unable to determine city from location",
                        savedCount = after.savedCount,
                        queuedCount = after.queuedCount,
                        location = after.location,
                        isFetchingLocation = false
                    )
                }
            } catch (se: SecurityException) {
                Log.e("PigeonHunterVM", "Location permission not granted", se)
                val after = _uiState.value
                _uiState.value = PigeonHunterUiState.Error(
                    bitmap = null,
                    message = "Location permission required",
                    savedCount = after.savedCount,
                    queuedCount = after.queuedCount,
                    location = after.location,
                    isFetchingLocation = false
                )
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Error fetching location", e)
                val after = _uiState.value
                _uiState.value = PigeonHunterUiState.Error(
                    bitmap = null,
                    message = "Failed to get location: ${e.message}",
                    savedCount = after.savedCount,
                    queuedCount = after.queuedCount,
                    location = after.location,
                    isFetchingLocation = false
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCityFromCurrentLocation(context: Context): String? {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        var location = awaitLastLocation(fusedClient)
        if (location == null) {
            location = awaitCurrentLocation(fusedClient)
        }
        if (location == null) return null
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
                                addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: addr.countryName
                            }
                            cont.resume(result) {}
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                    addresses?.firstOrNull()?.let { addr ->
                        addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: addr.countryName
                    }
                }
                Log.d("PigeonHunterVM", "Geocoded $latitude,$longitude -> $city")
                city
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Geocoding failed", e)
                null
            }
        }
    
    fun onSaveCurrent() {
        val current = _uiState.value
        val bitmap = when (current) {
            is PigeonHunterUiState.Success -> current.bitmap
            is PigeonHunterUiState.Queued -> current.bitmap
            is PigeonHunterUiState.Analyzing -> current.bitmap
            is PigeonHunterUiState.PhotoCaptured -> current.bitmap
            is PigeonHunterUiState.Saving -> current.bitmap
            is PigeonHunterUiState.SyncingQueue -> current.bitmap
            is PigeonHunterUiState.Error -> current.bitmap
            is PigeonHunterUiState.Initial -> null
        }
        val result = when (current) {
            is PigeonHunterUiState.Success -> current.result
            is PigeonHunterUiState.Saving -> current.result
            is PigeonHunterUiState.SyncingQueue -> current.result
            is PigeonHunterUiState.Queued -> current.result
            else -> null
        }

        if (bitmap == null) {
            _uiState.value = PigeonHunterUiState.Error(
                bitmap = null,
                message = "No photo to save",
                savedCount = current.savedCount,
                queuedCount = current.queuedCount,
                location = current.location,
                isFetchingLocation = false,
                userQuery = current.userQuery
            )
            return
        }

        // Requirement: only save pictures that contain pigeon
        if (result == null || !result.hasPigeon) {
            _uiState.value = when (current) {
                is PigeonHunterUiState.Success -> current.copy(saveMessage = "Can't save - no pigeon detected 🐦❌")
                is PigeonHunterUiState.Error -> current.copy(saveMessage = "Can't save - no pigeon")
                else -> PigeonHunterUiState.Error(
                    bitmap = bitmap,
                    message = "Can't save - no pigeon detected in this photo",
                    savedCount = current.savedCount,
                    queuedCount = current.queuedCount,
                    location = current.location,
                    isFetchingLocation = false,
                    userQuery = current.userQuery
                )
            }
            return
        }

        if (current is PigeonHunterUiState.Saving) return

        viewModelScope.launch {
            _uiState.value = PigeonHunterUiState.Saving(
                bitmap = bitmap,
                result = result,
                savedCount = current.savedCount,
                queuedCount = current.queuedCount,
                location = current.location,
                isFetchingLocation = false,
                userQuery = current.userQuery,
                customAnswer = current.customAnswer
            )
            try {
                when (val saveResult = savedRepository.savePigeon(bitmap, result, current.location)) {
                    is SavedPigeonRepository.SaveResult.Saved -> {
                        val newCount = savedRepository.getCount()
                        _uiState.value = PigeonHunterUiState.Success(
                            bitmap = bitmap,
                            result = result,
                            savedCount = newCount,
                            queuedCount = current.queuedCount,
                            location = current.location,
                            isFetchingLocation = false,
                            saveMessage = if (newCount >= 20) "Saved! Oldest deleted (max 20)" else "Saved! ($newCount/20)",
                            userQuery = current.userQuery,
                            customAnswer = current.customAnswer
                        )
                        Log.d("PigeonHunterVM", "Saved id=${saveResult.id}, count=$newCount")
                    }
                    is SavedPigeonRepository.SaveResult.AlreadyExists -> {
                        val count = savedRepository.getCount()
                        _uiState.value = PigeonHunterUiState.Success(
                            bitmap = bitmap,
                            result = result,
                            savedCount = count,
                            queuedCount = current.queuedCount,
                            location = current.location,
                            isFetchingLocation = false,
                            saveMessage = "Already saved! (id=${saveResult.existingId})",
                            userQuery = current.userQuery,
                            customAnswer = current.customAnswer
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Failed to save", e)
                _uiState.value = PigeonHunterUiState.Error(
                    bitmap = bitmap,
                    message = "Failed to save: ${e.message}",
                    savedCount = current.savedCount,
                    queuedCount = current.queuedCount,
                    location = current.location,
                    isFetchingLocation = false,
                    userQuery = current.userQuery
                )
            }
        }
    }

    fun updateUserQuery(query: String) {
        val current = _uiState.value
        _uiState.value = when (current) {
            is PigeonHunterUiState.Initial -> current.copy(userQuery = query)
            is PigeonHunterUiState.PhotoCaptured -> current.copy(userQuery = query)
            is PigeonHunterUiState.Analyzing -> current.copy(userQuery = query)
            is PigeonHunterUiState.Success -> current.copy(userQuery = query)
            is PigeonHunterUiState.Queued -> current.copy(userQuery = query)
            is PigeonHunterUiState.Error -> current.copy(userQuery = query)
            is PigeonHunterUiState.Saving -> current.copy(userQuery = query)
            is PigeonHunterUiState.SyncingQueue -> current.copy(userQuery = query)
        }
    }

    fun onAskCustom() {
        val current = _uiState.value
        val bitmap = when (current) {
            is PigeonHunterUiState.Success -> current.bitmap
            is PigeonHunterUiState.Queued -> current.bitmap
            is PigeonHunterUiState.PhotoCaptured -> current.bitmap
            is PigeonHunterUiState.Analyzing -> current.bitmap
            is PigeonHunterUiState.Saving -> current.bitmap
            is PigeonHunterUiState.SyncingQueue -> current.bitmap
            is PigeonHunterUiState.Error -> current.bitmap
            is PigeonHunterUiState.Initial -> null
        }
        val query = current.userQuery
        if (bitmap == null) {
            _uiState.value = PigeonHunterUiState.Error(
                bitmap = null,
                message = "Take a photo first to ask",
                savedCount = current.savedCount,
                queuedCount = current.queuedCount,
                location = current.location,
                isFetchingLocation = false,
                userQuery = query
            )
            return
        }
        if (query.isBlank()) {
            _uiState.value = when (current) {
                is PigeonHunterUiState.Success -> current.copy(saveMessage = "Type a question like 'does this picture contain cat?'")
                is PigeonHunterUiState.Error -> current.copy(saveMessage = "Type a question")
                else -> current
            }
            return
        }

        viewModelScope.launch {
            // Set asking state
            _uiState.value = when (val c = _uiState.value) {
                is PigeonHunterUiState.Success -> c.copy(isAskingCustom = true, customAnswer = null)
                is PigeonHunterUiState.Error -> PigeonHunterUiState.Success(
                    bitmap = c.bitmap ?: bitmap,
                    result = PigeonDetectionResult(false, null, 0f, null, null, "", ""),
                    savedCount = c.savedCount,
                    queuedCount = c.queuedCount,
                    location = c.location,
                    isFetchingLocation = false,
                    userQuery = query,
                    isAskingCustom = true
                )
                else -> {
                    // Keep current but set asking
                    val base = _uiState.value
                    base
                }
            }
            // Actually set isAskingCustom via withCounts extension for generic
            val beforeAsk = _uiState.value
            _uiState.value = when (beforeAsk) {
                is PigeonHunterUiState.Success -> beforeAsk.copy(isAskingCustom = true, customAnswer = null, userQuery = query)
                is PigeonHunterUiState.Initial -> beforeAsk.copy(isAskingCustom = true, customAnswer = null, userQuery = query)
                is PigeonHunterUiState.PhotoCaptured -> beforeAsk.copy(isAskingCustom = true, customAnswer = null, userQuery = query)
                is PigeonHunterUiState.Analyzing -> beforeAsk.copy(isAskingCustom = true, customAnswer = null, userQuery = query)
                is PigeonHunterUiState.Queued -> beforeAsk.copy(isAskingCustom = true, customAnswer = null, userQuery = query)
                is PigeonHunterUiState.Error -> beforeAsk.copy(isAskingCustom = true, customAnswer = null, userQuery = query)
                is PigeonHunterUiState.Saving -> beforeAsk.copy(isAskingCustom = true, customAnswer = null, userQuery = query)
                is PigeonHunterUiState.SyncingQueue -> beforeAsk.copy(isAskingCustom = true, customAnswer = null, userQuery = query)
            }

            try {
                val answer = repository.askCustom(bitmap, query)
                val after = _uiState.value
                _uiState.value = when (after) {
                    is PigeonHunterUiState.Success -> after.copy(isAskingCustom = false, customAnswer = answer, userQuery = query)
                    is PigeonHunterUiState.Initial -> after.copy(isAskingCustom = false, customAnswer = answer, userQuery = query)
                    is PigeonHunterUiState.PhotoCaptured -> after.copy(isAskingCustom = false, customAnswer = answer, userQuery = query)
                    is PigeonHunterUiState.Analyzing -> after.copy(isAskingCustom = false, customAnswer = answer, userQuery = query)
                    is PigeonHunterUiState.Queued -> after.copy(isAskingCustom = false, customAnswer = answer, userQuery = query)
                    is PigeonHunterUiState.Error -> after.copy(isAskingCustom = false, customAnswer = answer, userQuery = query)
                    is PigeonHunterUiState.Saving -> after.copy(isAskingCustom = false, customAnswer = answer, userQuery = query)
                    is PigeonHunterUiState.SyncingQueue -> after.copy(isAskingCustom = false, customAnswer = answer, userQuery = query)
                }
            } catch (e: Exception) {
                val after = _uiState.value
                val errMsg = "Failed to ask: ${e.message}"
                _uiState.value = when (after) {
                    is PigeonHunterUiState.Success -> after.copy(isAskingCustom = false, customAnswer = errMsg, userQuery = query)
                    else -> after
                }
            }
        }
    }

    fun clearSaveMessage() {
        val current = _uiState.value
        if (current is PigeonHunterUiState.Success) {
            _uiState.value = current.copy(saveMessage = null)
        }
    }
    
    fun clearError() {
        val current = _uiState.value
        _uiState.value = PigeonHunterUiState.Initial(
            savedCount = current.savedCount,
            queuedCount = current.queuedCount,
            location = current.location,
            isFetchingLocation = false,
            userQuery = current.userQuery
        )
    }

    fun deleteSaved(id: Long) {
        viewModelScope.launch {
            try {
                savedRepository.deleteById(id)
            } catch (e: Exception) {
                Log.e("PigeonHunterVM", "Failed to delete $id", e)
            }
        }
    }
}
