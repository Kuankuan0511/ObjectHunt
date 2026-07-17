package com.aai.steel.objecthunt

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aai.steel.objecthunt.ui.theme.ObjectHuntTheme

class MainActivity : ComponentActivity() {
    
    private val viewModel: PigeonHunterViewModel by viewModels()

    private val MODEL_API_KEY = BuildConfig.MUSE_API_KEY
    
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.fetchCurrentLocation()
        } else {
            Toast.makeText(this, "Location permission denied. Can't get city.", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val cameraGranted = perms[Manifest.permission.CAMERA] == true
        if (!cameraGranted) {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        bitmap?.let {
            viewModel.onPhotoCaptured(it)
            fetchCityIfPermittedSilent()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun fetchCityIfPermittedSilent() {
        if (hasLocationPermission()) {
            viewModel.fetchCurrentLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        if (MODEL_API_KEY.isBlank()) {
            Toast.makeText(
                this, 
                "Please set muse.api.key in local.properties", 
                Toast.LENGTH_LONG
            ).show()
        }
        
        val missingPermissions = mutableListOf<String>()
        if (!hasCameraPermission()) {
            missingPermissions.add(Manifest.permission.CAMERA)
        }
        if (!hasLocationPermission()) {
            missingPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (missingPermissions.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
        
        setContent {
            ObjectHuntTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val uiState by viewModel.uiState.collectAsState()
                    
                    ObjectHuntScreen(
                        modifier = Modifier.padding(innerPadding),
                        uiState = uiState,
                        onTakePhoto = {
                            if (hasCameraPermission()) {
                                takePictureLauncher.launch(null)
                            } else {
                                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        onRetakePhoto = { viewModel.onRetakePhoto() },
                        onSave = { viewModel.onSaveCurrent() },
                        onClearSaveMessage = { viewModel.clearSaveMessage() },
                        onSyncQueue = { viewModel.syncQueuedDetections() },
                        onClearQueueMessage = { viewModel.clearQueueMessage() }
                    )
                }
            }
        }
    }
}

@Composable
fun ObjectHuntScreen(
    modifier: Modifier = Modifier,
    uiState: PigeonHunterUiState,
    onTakePhoto: () -> Unit,
    onRetakePhoto: () -> Unit,
    onSave: () -> Unit,
    onClearSaveMessage: () -> Unit,
    onSyncQueue: () -> Unit,
    onClearQueueMessage: () -> Unit
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Pigeon Hunter 🐦",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Queue status banner - always visible if queued
        if (uiState.queuedCount > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📡 Queued: ${uiState.queuedCount} pending",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.isSyncingQueue) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        OutlinedButton(onClick = onSyncQueue) {
                            Text("Sync")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (uiState.capturedBitmap == null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Take a photo to hunt for pigeons!",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                    Button(
                        onClick = onTakePhoto,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text("📸 Take Photo")
                    }
                    Text(
                        text = "Muse Spark AI will analyze the image and identify pigeon types",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Saved: ${uiState.savedCount}/20 | Queued: ${uiState.queuedCount}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            uiState.capturedBitmap?.let { bitmap ->
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Captured image",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (uiState.isAnalyzing) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator()
                                    Text(
                                        text = "Muse Spark is analyzing...",
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                    Text(
                                        text = "Checking for pigeons 🐦",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        } else if (uiState.pigeonResult != null) {
                            PigeonResultCard(result = uiState.pigeonResult)
                        }

                        if (uiState.location != null) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Photo taken in:",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "📍 ${uiState.location}",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (uiState.isFetchingLocation) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        } else if (uiState.isFetchingLocation) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Text(
                                    text = "  Getting current city...",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        uiState.saveMessage?.let { msg ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Text(
                                    text = msg,
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        uiState.queueMessage?.let { msg ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                )
                            ) {
                                Text(
                                    text = "📡 $msg",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        uiState.errorMessage?.let { error ->
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onSave,
                                enabled = !uiState.isAnalyzing && !uiState.isSaving && uiState.capturedBitmap != null,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (uiState.isSaving) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Saving...")
                                } else {
                                    Text("💾 Save (${uiState.savedCount}/20)")
                                }
                            }
                            Button(
                                onClick = onRetakePhoto,
                                enabled = !uiState.isAnalyzing && !uiState.isSaving,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Retake")
                            }
                        }

                        if (uiState.queuedCount > 0) {
                            OutlinedButton(
                                onClick = onSyncQueue,
                                enabled = !uiState.isSyncingQueue,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (uiState.isSyncingQueue) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Syncing ${uiState.queuedCount}...")
                                } else {
                                    Text("🔄 Sync ${uiState.queuedCount} queued")
                                }
                            }
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        uiState.capturedBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Captured image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (uiState.isAnalyzing) {
                            CircularProgressIndicator()
                            Text(
                                text = "Muse Spark is analyzing...",
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Text(
                                text = "Checking for pigeons 🐦",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        } else if (uiState.pigeonResult != null) {
                            PigeonResultCard(result = uiState.pigeonResult)
                        }

                        if (uiState.location != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Photo taken in:",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "📍 ${uiState.location}",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (uiState.isFetchingLocation) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        } else if (uiState.isFetchingLocation) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Text(
                                    text = "  Getting current city...",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        uiState.saveMessage?.let { msg ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Text(
                                    text = msg,
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        uiState.queueMessage?.let { msg ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                )
                            ) {
                                Text(
                                    text = "📡 $msg",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        uiState.errorMessage?.let { error ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSave,
                            enabled = !uiState.isAnalyzing && !uiState.isSaving,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (uiState.isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Saving...")
                            } else {
                                Text("💾 Save (${uiState.savedCount}/20)")
                            }
                        }
                        Button(
                            onClick = onRetakePhoto,
                            enabled = !uiState.isAnalyzing && !uiState.isSaving,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Retake")
                        }
                    }

                    if (uiState.queuedCount > 0) {
                        OutlinedButton(
                            onClick = onSyncQueue,
                            enabled = !uiState.isSyncingQueue,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (uiState.isSyncingQueue) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Syncing ${uiState.queuedCount}...")
                            } else {
                                Text("🔄 Sync ${uiState.queuedCount} queued (backoff)")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PigeonResultCard(result: PigeonDetectionResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (result.hasPigeon) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (result.hasPigeon) "🐦 PIGEON FOUND!" else "❌ No Pigeon",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (result.hasPigeon)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (result.hasPigeon) {
                result.pigeonType?.let { type ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Type:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = type,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Confidence:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${(result.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                result.features?.let { features ->
                    Text(
                        text = "Features:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = features,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                result.location?.let { location ->
                    Text(
                        text = "Location:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = location,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            } else {
                Text(
                    text = result.description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
