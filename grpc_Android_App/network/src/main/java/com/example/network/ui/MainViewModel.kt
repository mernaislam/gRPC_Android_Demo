package com.example.network.ui

import android.content.Context
import androidx.lifecycle.*
import kotlinx.coroutines.flow.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.network.interfaces.IComputationStrategy
import com.example.network.network.*
import kotlinx.coroutines.launch

/**
 * MainViewModel manages distributed computation state, logs, and UI event feeds for the app.
 * It coordinates computation lifecycle, aggregates logs, and exposes state for Compose UI.
 *
 * @property network The GrpcNetwork instance for distributed computation and logging.
 */
class MainViewModel(
    val network: GrpcNetwork
) : ViewModel() {
    /** Indicates if a computation is currently running. */
    private val _isComputing = MutableStateFlow(false)
    val isComputing: StateFlow<Boolean> = _isComputing

    /** Debug logs for the UI, shared with the network layer. */
    val debugLogs: SnapshotStateList<String> = network.logs

    /**
     * StateFlow of device status logs, keyed by friendly name.
     * Aggregates device status events for UI display.
     */
    val deviceLogFeed: StateFlow<Map<String, String>> =
        DeviceEventBus.events
            .scan(emptyMap<String, String>()) { acc, event ->
                when (event) {
                    is UiEventDeviceStatus.WorkerStatusChanged -> {
                        acc + (event.humanName to event.toPrettyString())
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * StateFlow of work progress logs, keyed by friendly name.
     * Aggregates work status events for UI display.
     */
    val workLogFeed: StateFlow<Map<String, String>> =
        WorkEventBus.events
            .scan(emptyMap<String, String>()) { acc, event ->
                val key = when (event) {
                    is UiEventWorkStatus.TaskAssigned    -> event.humanName
                    is UiEventWorkStatus.TaskNotAssigned    -> event.humanName
                    is UiEventWorkStatus.TaskCompleted -> event.humanName
                    is UiEventWorkStatus.Error -> event.humanName
                }
                acc + (key to event.toPrettyString())
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Converts a device status event to a human-readable string for the UI.
     */
    private fun UiEventDeviceStatus.toPrettyString(): String = when (this) {
        is UiEventDeviceStatus.WorkerStatusChanged -> if (online) "✅ Online" else "🔴 Offline"
    }

    /**
     * Converts a work status event to a human-readable string for the UI.
     */
    private fun UiEventWorkStatus.toPrettyString(): String = when (this) {
        is UiEventWorkStatus.TaskAssigned  -> "➡️  Assigned portions [${portions.joinToString(", ")}]"
        is UiEventWorkStatus.TaskNotAssigned  -> "\uD83D\uDE34 is idle now"
        is UiEventWorkStatus.TaskCompleted -> "✔️ Computed portions [${portions.joinToString(", ")}] in ${computationTime}ms"
        is UiEventWorkStatus.Error        -> "⚠️  Cannot Compute tasks (device is offline)"
    }

    /**
     * Returns the local device's IP address for display.
     *
     * @return The local device's IP address as a String.
     */
    fun getLocalIpAddress(): String = network.getLocalIpAddress()

    /**
     * Starts a distributed computation using the provided strategy.
     * Clears logs, updates state, and launches the computation in the network layer.
     *
     * @param strategy The computation strategy to use (e.g., matrix multiplication).
     */
    fun startComputation(strategy: IComputationStrategy) {
        debugLogs.clear()
        viewModelScope.launch {
            try {
                _isComputing.value = true
                network.startAsClient(viewModelScope, strategy) {
                    viewModelScope.launch {
                        _isComputing.value = false
                    }
                }
            } catch (e: Exception) {
                debugLogs.add("Error starting computation: ${e.message}")
                _isComputing.value = false
            }
        }
    }
}

/**
 * MainViewModelFactory creates MainViewModel instances with the required context and network dependencies.
 *
 * @property context The Android context used to initialize dependencies.
 */
class MainViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    /**
     * Creates a new instance of the given ViewModel class.
     *
     * @param modelClass The class of the ViewModel to create.
     * @return A new instance of the requested ViewModel.
     * @throws IllegalArgumentException if the ViewModel class is unknown.
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            val network = GrpcNetwork(LogRepository.sharedLogs, context)
            return MainViewModel(network) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
