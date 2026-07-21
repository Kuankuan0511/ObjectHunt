package com.aai.steel.objecthunt.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aai.steel.objecthunt.PigeonHunterViewModel
import com.aai.steel.objecthunt.data.PigeonEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SavedPigeonsScreen(
    viewModel: PigeonHunterViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val savedList by viewModel.savedPigeonsFlow.collectAsState(initial = emptyList())

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) { Text("← Back") }
            Text(
                text = "Saved Pigeons (${savedList.size}/20)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            // placeholder for alignment
            Spacer(modifier = Modifier.width(60.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (savedList.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = "No saved pigeons yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Take a photo and tap Save to see it here",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(savedList, key = { it.id }) { entity ->
                    SavedPigeonItem(
                        entity = entity,
                        onDelete = { viewModel.deleteSaved(entity.id) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { /* viewModel could have deleteAll, but use repo directly for simplicity */
                    // We'll call delete via viewModel method that deletes all - add if needed
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Total: ${savedList.size} saved, oldest auto-deleted at 20")
            }
        }
    }
}

@Composable
private fun SavedPigeonItem(
    entity: PigeonEntity,
    onDelete: () -> Unit
) {
    // Decode bytes to bitmap - remembered to avoid re-decode on recomposition
    val imageBitmap = remember(entity.id, entity.imageHash) {
        try {
            val bmp = BitmapFactory.decodeByteArray(entity.imageBytes, 0, entity.imageBytes.size)
            bmp?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Thumbnail
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "Saved pigeon",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Card(
                    modifier = Modifier.size(80.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("No Img", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.pigeonType ?: "Unknown Pigeon",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "📍 ${entity.city ?: "Unknown city"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Confidence: ${(entity.confidence * 100).toInt()}% | ${formatTimestamp(entity.timestamp)}",
                    style = MaterialTheme.typography.labelSmall
                )
                entity.features?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = "Features: $it", style = MaterialTheme.typography.labelSmall, maxLines = 2)
                }
                entity.pigeonLocationInImage?.let {
                    Text(text = "In image: $it", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(onClick = onDelete, modifier = Modifier.height(32.dp)) {
                    Text("Delete", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun formatTimestamp(ts: Long): String {
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    return sdf.format(Date(ts))
}
