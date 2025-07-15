package com.example.network.interfaces

import com.example.protos.AssignTaskRequest
import com.example.protos.AssignTaskResponse

interface INetworkService {
    /**
     * Executes a task on a worker.
     *
     * @param workerAddress The address of the worker to execute the task on.
     * @param request The task request.
     * @return The task response.
     */
    fun executeTask(workerAddress: String, request: AssignTaskRequest): AssignTaskResponse

    /**
     * Retrieves a list of available workers.
     *
     * @return A list of available workers, each represented as a pair of (address, status).
     */
    fun getAvailableWorkers(): List<Pair<String, String>>
} 