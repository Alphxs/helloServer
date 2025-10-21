package com.example.android_helloworld

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.android_helloworld.db.RecognitionResult
import com.example.android_helloworld.helpers.BitmapLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionHistoryScreen(viewModel: RecognitionViewModel) {
    val results by viewModel.recognitionResults.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recognition History") },
                actions = {
                    // Show the clear button only if there's something to clear
                    if (results.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearHistory() }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear History"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding -> // Content of the screen goes here
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding), // Apply padding from the TopAppBar
            color = MaterialTheme.colorScheme.background
        ) {
            if (results.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No recognition results yet. Upload an image from the web interface.")
                }
            } else {
                // Use contentPadding for the list items
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(results) { result ->
                        ResultCard(result = result)
                    }
                }
            }
        }
    }
}

@Composable
fun ResultCard(result: RecognitionResult) {
    val bitmap = BitmapLoader.loadBitmapFromFile(path = result.imagePath)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Uploaded Image for recognition",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentScale = ContentScale.Crop
                )
            }
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Recognized Objects:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = result.recognizedObjects,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
