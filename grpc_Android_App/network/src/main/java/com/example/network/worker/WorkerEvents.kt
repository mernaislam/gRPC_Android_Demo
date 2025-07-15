package com.example.network.worker

import com.example.protos.AssignTaskResponse
import io.grpc.ManagedChannel

/**
 * WorkerEvents defines callbacks for worker-related events such as metrics updates, logging contributions, and channel shutdowns.
 */
interface WorkerEvents {
    /**
     * Called when a worker's computation metrics are updated.
     * @param workerId The unique identifier of the worker.
     * @param computationTime The time taken for the computation in milliseconds.
     */
    fun onWorkerMetricsUpdated(workerId: String, computationTime: Long)

    /**
     * Logs the contributions of a worker after task completion.
     * @param response The AssignTaskResponse containing worker results.
     */
    fun logWorkerContributions(response: AssignTaskResponse)

    /**
     * Shuts down the gRPC channel associated with a worker.
     * @param channel The ManagedChannel to shut down.
     */
    fun shutdownWorkerChannel(channel: ManagedChannel)
} 