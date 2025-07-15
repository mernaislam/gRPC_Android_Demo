package com.example.network.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * DeviceEventBus is a singleton event bus for broadcasting device status events (e.g., worker online/offline)
 * to the UI layer using a shared flow. Allows decoupled communication between backend and UI.
 */
object DeviceEventBus {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val _events = MutableSharedFlow<UiEventDeviceStatus>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    // Non-suspend version for use in non-coroutine contexts
    fun post(event: UiEventDeviceStatus) {
        scope.launch {
            _events.emit(event)
        }
    }
}

/**
 * WorkEventBus is a singleton event bus for broadcasting work status events (e.g., task assigned/completed/errors)
 * to the UI layer using a shared flow. Enables decoupled, reactive updates for work progress.
 */
object WorkEventBus {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val _events = MutableSharedFlow<UiEventWorkStatus>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    // Non-suspend version for use in non-coroutine contexts
    fun post(event: UiEventWorkStatus) {
        scope.launch {
            _events.emit(event)
        }
    }
}