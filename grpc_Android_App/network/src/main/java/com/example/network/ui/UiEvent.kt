package com.example.network.ui

/** Every message the coordinator or a worker wants to surface to the UI */
/**
 * Represents device status events for the UI, such as worker online/offline changes.
 */
sealed class UiEventDeviceStatus {
    abstract val timestamp: Long   // millis since epoch

    /**
     * Event indicating a worker's online/offline status has changed.
     * @param timestamp The time of the event in milliseconds since epoch.
     * @param humanName The user-friendly name of the worker.
     * @param online True if the worker is online, false if offline.
     */
    data class WorkerStatusChanged(
        override val timestamp: Long = System.currentTimeMillis(),
        val humanName: String,
        val online: Boolean            // true = READY, false = OFFLINE/DEAD
    ) : UiEventDeviceStatus()
}

/**
 * Represents work status events for the UI, such as task assignment, completion, or errors.
 */
sealed class UiEventWorkStatus {
    abstract val timestamp: Long

    /**
     * Event indicating a task has been assigned to a worker.
     * @param timestamp The time of the event in milliseconds since epoch.
     * @param humanName The user-friendly name of the worker.
     * @param portions The list of matrix portions assigned.
     */
    data class TaskAssigned(
        override val timestamp: Long = System.currentTimeMillis(),
        val humanName: String,
        val portions: List<Int>
    ) : UiEventWorkStatus()

    /**
     * Event indicating a worker was not assigned any task (idle).
     * @param timestamp The time of the event in milliseconds since epoch.
     * @param humanName The user-friendly name of the worker.
     */
    data class TaskNotAssigned(
        override val timestamp: Long = System.currentTimeMillis(),
        val humanName: String,
    ) : UiEventWorkStatus()

    /**
     * Event indicating a worker has completed a task.
     * @param timestamp The time of the event in milliseconds since epoch.
     * @param humanName The user-friendly name of the worker.
     * @param portions The list of matrix portions computed.
     * @param computationTime The time taken to compute the task in milliseconds.
     */
    data class TaskCompleted(
        override val timestamp: Long = System.currentTimeMillis(),
        val humanName: String,
        val portions: List<Int>,
        val computationTime: Long
    ) : UiEventWorkStatus()

    /**
     * Event indicating an error occurred during task processing.
     * @param timestamp The time of the event in milliseconds since epoch.
     * @param humanName The user-friendly name of the worker.
     * @param message The error message.
     */
    data class Error(
        override val timestamp: Long = System.currentTimeMillis(),
        val humanName: String,
        val message: String
    ) : UiEventWorkStatus()
}
