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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aai.steel.objecthunt.ui.SavedPigeonsScreen
import com.aai.steel.objecthunt.ui.theme.ObjectHuntTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
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
        if (isGranted) viewModel.fetchCurrentLocation()
        else Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.CAMERA] != true) {
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
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun fetchCityIfPermittedSilent() {
        if (hasLocationPermission()) viewModel.fetchCurrentLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        if (MODEL_API_KEY.isBlank()) {
            Toast.makeText(this, "Please set muse.api.key in local.properties", Toast.LENGTH_LONG).show()
        }
        
        val missing = mutableListOf<String>()
        if (!hasCameraPermission()) missing.add(Manifest.permission.CAMERA)
        if (!hasLocationPermission()) missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (missing.isNotEmpty()) requestMultiplePermissionsLauncher.launch(missing.toTypedArray())
        
        setContent {
            ObjectHuntTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val uiState by viewModel.uiState.collectAsState()
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = "hunt",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("hunt") {
                            ObjectHuntScreen(
                                modifier = Modifier,
                                uiState = uiState,
                                onTakePhoto = {
                                    if (hasCameraPermission()) takePictureLauncher.launch(null)
                                    else requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                },
                                onRetakePhoto = { viewModel.onRetakePhoto() },
                                onSave = { viewModel.onSaveCurrent() },
                                onSyncQueue = { viewModel.syncQueuedDetections() },
                                onViewSaved = { navController.navigate("saved") }
                            )
                        }
                        composable("saved") {
                            SavedPigeonsScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() },
                                modifier = Modifier
                            )
                        }
                    }
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
    onSyncQueue: () -> Unit,
    onViewSaved: () -> Unit
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Pigeon Hunter 🐦",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (uiState.queuedCount > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📡 Queued: ${uiState.queuedCount} pending",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    val isSyncing = uiState is PigeonHunterUiState.SyncingQueue
                    if (isSyncing) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else OutlinedButton(onClick = onSyncQueue) { Text("Sync") }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        when (uiState) {
            is PigeonHunterUiState.Initial -> {
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(text = "Take a photo to hunt for pigeons!", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 24.dp))
                        Button(onClick = onTakePhoto, modifier = Modifier.padding(16.dp)) { Text("📸 Take Photo") }
                        Text(text = "Muse Spark AI will analyze the image and identify pigeon types", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "Saved: ${uiState.savedCount}/20 | Queued: ${uiState.queuedCount}", style = MaterialTheme.typography.bodySmall)
                        uiState.saveMessage?.let { Text(text = it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
                        uiState.queueMessage?.let { Text(text = "📡 $it", modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall) }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = onViewSaved) {
                            Text("📚 View Saved (${uiState.savedCount}/20)")
                        }
                    }
                }
            }

            is PigeonHunterUiState.PhotoCaptured, is PigeonHunterUiState.Analyzing,
            is PigeonHunterUiState.Success, is PigeonHunterUiState.Queued,
            is PigeonHunterUiState.Error, is PigeonHunterUiState.Saving,
            is PigeonHunterUiState.SyncingQueue -> {
                // Extract common bitmap and messages via smart cast helper
                val bitmap = when (uiState) {
                    is PigeonHunterUiState.PhotoCaptured -> uiState.bitmap
                    is PigeonHunterUiState.Analyzing -> uiState.bitmap
                    is PigeonHunterUiState.Success -> uiState.bitmap
                    is PigeonHunterUiState.Queued -> uiState.bitmap
                    is PigeonHunterUiState.Error -> uiState.bitmap
                    is PigeonHunterUiState.Saving -> uiState.bitmap
                    is PigeonHunterUiState.SyncingQueue -> uiState.bitmap
                    is PigeonHunterUiState.Initial -> null
                }

                // Remembered ImageBitmap to avoid recomposing bitmap on every state copy
                val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }

                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Card(modifier = Modifier.weight(1f).fillMaxHeight(), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
                            Column(modifier = Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                imageBitmap?.let {
                                    Image(bitmap = it, contentDescription = "Captured image", modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ContentForState(uiState, onSave, onRetakePhoto, onSyncQueue, onViewSaved)
                        }
                    }
                } else {
                    Card(modifier = Modifier.fillMaxWidth().weight(1f), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
                        Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            imageBitmap?.let {
                                Image(bitmap = it, contentDescription = "Captured image", modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)))
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            ContentForState(uiState, onSave, onRetakePhoto, onSyncQueue, onViewSaved)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onSave,
                            enabled = uiState is PigeonHunterUiState.Success && !uiState.isSaving,
                            modifier = Modifier.weight(1f)
                        ) {
                            val isSaving = (uiState as? PigeonHunterUiState.Success)?.isSaving == true || uiState is PigeonHunterUiState.Saving
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Saving...")
                            } else {
                                Text("💾 Save (${uiState.savedCount}/20)")
                            }
                        }
                        Button(onClick = onRetakePhoto, modifier = Modifier.weight(1f)) { Text("Retake") }
                    }
                    if (uiState.queuedCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = onSyncQueue, modifier = Modifier.fillMaxWidth()) {
                            Text("🔄 Sync ${uiState.queuedCount} queued (backoff)")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onViewSaved, modifier = Modifier.fillMaxWidth()) {
                        Text("📚 View Saved (${uiState.savedCount}/20)")
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentForState(
    uiState: PigeonHunterUiState,
    onSave: () -> Unit,
    onRetakePhoto: () -> Unit,
    onSyncQueue: () -> Unit,
    onViewSaved: () -> Unit = {}
) {
    when (uiState) {
        is PigeonHunterUiState.Analyzing -> {
            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(text = "Muse Spark is analyzing...", modifier = Modifier.padding(top = 8.dp))
                    Text(text = "Checking for pigeons 🐦", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        is PigeonHunterUiState.Success -> {
            PigeonResultCard(result = uiState.result)
            if (uiState.location != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Photo taken in:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(text = "📍 ${uiState.location}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        if (uiState.isFetchingLocation) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }
            }
            uiState.saveMessage?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(text = msg, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            uiState.queueMessage?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(text = "📡 $msg", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        is PigeonHunterUiState.Queued -> {
            uiState.result?.let { PigeonResultCard(result = it) }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Text(text = "📡 ${uiState.queueMessage}", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        is PigeonHunterUiState.Error -> {
            uiState.bitmap?.let { /* image already shown outside */ }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(text = uiState.message, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        is PigeonHunterUiState.Saving -> {
            PigeonResultCard(result = uiState.result)
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Text(text = "  Saving...", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        is PigeonHunterUiState.SyncingQueue -> {
            uiState.result?.let { PigeonResultCard(result = it) }
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Text(text = "  Syncing ${uiState.queuedCount} queued with backoff...", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        is PigeonHunterUiState.PhotoCaptured -> {
            Text(text = "Ready to analyze...", style = MaterialTheme.typography.bodyMedium)
        }
        is PigeonHunterUiState.Initial -> {}
    }

    // For landscape, buttons are inside this column; portrait handled outside
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (isLandscape) {
        Spacer(modifier = Modifier.height(8.dp))
        when (uiState) {
            is PigeonHunterUiState.Success -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSave, enabled = !uiState.isSaving, modifier = Modifier.weight(1f)) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Saving...")
                        } else {
                            Text("💾 Save (${uiState.savedCount}/20)")
                        }
                    }
                    Button(onClick = onRetakePhoto, modifier = Modifier.weight(1f)) { Text("Retake") }
                }
                if (uiState.queuedCount > 0) {
                    OutlinedButton(onClick = onSyncQueue, modifier = Modifier.fillMaxWidth()) {
                        Text("🔄 Sync ${uiState.queuedCount} (backoff)")
                    }
                }
                OutlinedButton(onClick = onViewSaved, modifier = Modifier.fillMaxWidth()) {
                    Text("📚 View Saved (${uiState.savedCount}/20)")
                }
            }
            is PigeonHunterUiState.Error, is PigeonHunterUiState.Queued -> {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetakePhoto, modifier = Modifier.fillMaxWidth()) { Text("Retake") }
                    OutlinedButton(onClick = onViewSaved, modifier = Modifier.fillMaxWidth()) {
                        Text("📚 View Saved (${uiState.savedCount}/20)")
                    }
                }
            }
            else -> {
                OutlinedButton(onClick = onViewSaved, modifier = Modifier.fillMaxWidth()) {
                    Text("📚 View Saved (${uiState.savedCount}/20)")
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
            containerColor = if (result.hasPigeon) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (result.hasPigeon) "🐦 PIGEON FOUND!" else "❌ No Pigeon",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (result.hasPigeon) {
                result.pigeonType?.let { type ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "Type:", fontWeight = FontWeight.Bold)
                        Text(text = type)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Confidence:", fontWeight = FontWeight.Bold)
                    Text(text = "${(result.confidence * 100).toInt()}%")
                }
                Spacer(modifier = Modifier.height(8.dp))
                result.features?.let {
                    Text(text = "Features:", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
                    Text(text = it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                }
                result.location?.let {
                    Text(text = "Location:", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
                    Text(text = it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            } else {
                Text(text = result.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
