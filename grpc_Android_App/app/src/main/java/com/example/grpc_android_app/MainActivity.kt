package com.example.grpc_android_app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.network.matrix.MatrixComputationStrategy
import com.example.network.ui.*
import com.example.network.ui.loadFriendlyName
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.KeyboardType

/**
 * Main entry point for the app's activity. Sets up the Compose UI and provides the ViewModel.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { 
        MainViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppUI(viewModel, this)
                }
            }
        }
    }
}

/**
 * The main application UI, displaying matrix configuration, worker status, computation progress, and debug logs.
 * @param viewModel The main ViewModel for state and logic.
 * @param context The Android context.
 */
@Composable
fun AppUI(
    viewModel: MainViewModel,
    context: Context
) {
    val scope = rememberCoroutineScope()
    var matrixSizeText by remember { mutableStateOf("") }
    var matrixSize by remember { mutableIntStateOf(0) }
    val isComputing by viewModel.isComputing.collectAsState()
    val ipAddress = viewModel.getLocalIpAddress()

    val deviceLogs by viewModel.deviceLogFeed.collectAsState()
    val workLogs by viewModel.workLogFeed.collectAsState()
    val debugLogs = viewModel.debugLogs

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        // Header
        Text(
            "Distributed Matrix Computation",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Matrix Size Input
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    "Matrix Configuration",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = matrixSizeText,
                    onValueChange = {
                        matrixSizeText = it
                        matrixSize = it.toIntOrNull()?.coerceIn(2, 100) ?: 0
                    },
                    label = { Text("Matrix Size (NxN)", color = Color.White) },
                    supportingText = { Text("Enter a value between 2 and 100", color = Color.Gray) },
                    isError = matrixSizeText.isNotEmpty() && (matrixSize < 2 || matrixSize > 100),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isComputing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.Gray,
                        cursorColor = Color.Green
                    )
                )

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        scope.launch {
                            try {
                                if (matrixSize in 2..100) {
                                    val strategy = MatrixComputationStrategy(context.loadFriendlyName(), matrixSize)
                                    viewModel.startComputation(strategy)
                                } else {
                                    debugLogs.add("MainActivity: Invalid matrix size: $matrixSize")
                                }
                            } catch (e: Exception) {
                                debugLogs.add("MainActivity: Error starting computation: ${e.message}")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isComputing && matrixSize in 2..100 && matrixSizeText.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50),
                        contentColor = Color.White
                    )
                ) {
                    if (isComputing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isComputing) "Computing..." else "Compute Matrix")
                }
            }
        }

        // Worker Status Section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize()
            ) {
                Text(
                    "Worker Status",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(deviceLogs.entries.toList()) { entry ->
                        WorkerStatusItem(entry.key, entry.value)
                    }
                }
            }
        }

        // Work Progress Section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize()
            ) {
                Text(
                    "Computation Progress",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(workLogs.entries.toList()) { entry ->
                        WorkProgressItem(entry.key, entry.value)
                    }
                }
            }
        }

        // Debug Logs Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize()
            ) {
                Text(
                    "Debug Logs",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))
                
                val listState = rememberLazyListState()

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                ) {
                    items(debugLogs) { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier
                                .padding(vertical = 2.dp)
                                .fillMaxWidth()
                        )
                    }
                }
                
                // Auto-scroll to bottom when new logs are added
                LaunchedEffect(debugLogs.size) {
                    if (debugLogs.isNotEmpty()) {
                        val lastIndex = debugLogs.size - 1
                        scope.launch {
                            val count = listState.layoutInfo.totalItemsCount
                            val safeIndex = if (count == 0) 0 else minOf(lastIndex, count - 1)
                            if (safeIndex >= 0 && safeIndex < count) {
                                listState.animateScrollToItem(safeIndex)
                            }
                        }
                    }
                }
            }
        }

        // Device Info Footer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Device: ${context.loadFriendlyName()} | IP: $ipAddress",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Displays the status of a single worker.
 */
@Composable
fun WorkerStatusItem(friendlyName: String, status: String) {
    val backgroundColor = when {
        status.contains("Online") -> Color(0xFF4CAF50).copy(alpha = 0.1f)
        status.contains("Offline") -> Color(0xFFF44336).copy(alpha = 0.1f)
        else -> Color(0xFFFFA000).copy(alpha = 0.1f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(backgroundColor, MaterialTheme.shapes.small)
            .border(1.dp, backgroundColor.copy(alpha = 0.5f), MaterialTheme.shapes.small)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            friendlyName,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}

/**
 * Displays the computation progress for a single worker.
 */
@Composable
fun WorkProgressItem(friendlyName: String, status: String) {
    val backgroundColor = when {
        status.contains("✔️") -> Color(0xFF4CAF50).copy(alpha = 0.1f)
        status.contains("⚠️") -> Color(0xFFF44336).copy(alpha = 0.1f)
        status.contains("➡️") -> Color(0xFF2196F3).copy(alpha = 0.1f)
        else -> Color(0xFF2D2D2D)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(backgroundColor, MaterialTheme.shapes.small)
            .border(1.dp, backgroundColor.copy(alpha = 0.5f), MaterialTheme.shapes.small)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            friendlyName,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}
