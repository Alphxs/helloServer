package com.example.android_helloworld.recognition

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.forEach
import androidx.core.graphics.values
import com.example.android_helloworld.db.RecognitionResult
import com.example.android_helloworld.helpers.BitmapLoader
import com.example.android_helloworld.testServer
import com.example.android_helloworld.RoutingAlgorithm
import com.google.androidgamesdk.gametextinput.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionHistoryScreen(viewModel: RecognitionViewModel, server: testServer?) {
    val results by viewModel.recognitionResults.collectAsState()
    var showSettings by remember { mutableStateOf(false) } // State for dropdown

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recognition History") },
                actions = {
                    // --- SETTINGS DROPDOWN ---
                    if (server != null) {
                        Box {
                            IconButton(onClick = { showSettings = true }) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Routing Settings"
                                )
                            }
                            DropdownMenu(
                                expanded = showSettings,
                                onDismissRequest = { showSettings = false }
                            ) {
                                Text(
                                    "Routing Algorithm",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                RoutingAlgorithm.entries.forEach { algo ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                when (algo) {
                                                    RoutingAlgorithm.RANDOM -> "Random (No CPT)"
                                                    RoutingAlgorithm.RANDOM_CPT -> "Random CPT"
                                                    RoutingAlgorithm.RTTMS -> "RTTms (Memory Aware)"
                                                    RoutingAlgorithm.RESAT_V2 -> "RESAT_V2"
                                                }
                                            )
                                        },
                                        onClick = {
                                            server.setAlgorithm(algo)
                                            showSettings = false
                                        },
                                        leadingIcon = {
                                            RadioButton(
                                                selected = (server.currentAlgorithm == algo),
                                                onClick = null // Handled by MenuItem click
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // --- DELETE BUTTON ---
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
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            if (results.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No recognition results yet.")
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Removed AlgorithmSettings from here
                    items(results) { result ->
                        ResultCard(result = result)
                    }
                }
            }
        }
    }
}

@Composable
fun AlgorithmSettings(server: testServer) {// State to track selection for UI updates
    var selectedAlgo by remember { mutableStateOf(RoutingAlgorithm.RTTMS) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Edge Routing Algorithm", style = MaterialTheme.typography.titleMedium)

        RoutingAlgorithm.values().forEach { algo ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = (selectedAlgo == algo),
                        onClick = {
                            selectedAlgo = algo
                            server.setAlgorithm(algo) // Update the server
                        }
                    )
                    .padding(vertical = 8.dp)
            ) {
                RadioButton(
                    selected = (selectedAlgo == algo),
                    onClick = {
                        selectedAlgo = algo
                        server.setAlgorithm(algo)
                    }
                )
                Text(
                    text = when(algo) {
                        RoutingAlgorithm.RANDOM -> "Random (No CPT)"
                        RoutingAlgorithm.RANDOM_CPT -> "Random CPT"
                        RoutingAlgorithm.RTTMS -> "RTTms (Memory Aware)"
                        RoutingAlgorithm.RESAT_V2 -> "RESAT_V2"
                    },
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        // Show status indicator
        Text(
            text = if (selectedAlgo == RoutingAlgorithm.RANDOM)
                "Warning: App may crash under heavy load"
            else "Status: Crash Prevention Active",
            color = if (selectedAlgo == RoutingAlgorithm.RANDOM) Color.Red else Color.Green,
            style = MaterialTheme.typography.bodySmall
        )
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
