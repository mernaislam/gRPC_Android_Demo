package com.example.network.interfaces

import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.protos.AssignTaskRequest
import com.example.protos.AssignTaskResponse

/**
 * Interface defining the strategy for computation tasks.
 * Different implementations can handle different types of computations (matrix, image processing, etc.)
 */
interface IComputationStrategy {
    /**
     * Generates input data for the computation task
     * @return Pair of input data (can be matrices, images, etc.)
     */
    fun generateInput(): Pair<Any, Any>

    /**
     * Builds a request object from the input data
     * @param input1 First input
     * @param input2 Second input
     * @return AssignTaskRequest object containing the task data
     */
    fun buildRequest(input1: Any, input2: Any): AssignTaskRequest

    /**
     * Computes the task based on the request
     * @param request AssignTaskRequest containing the task data
     * @return AssignTaskResponse containing the computation results
     */
    fun computeTask(request: AssignTaskRequest): AssignTaskResponse

    /**
     * Logs the input data in a format specific to the computation type
     * @param input1 First input
     * @param input2 Second input
     * @param logs List to add log messages to
     */
    fun logInput(input1: Any, input2: Any, logs: SnapshotStateList<String>)

    /**
     * Logs the output data in a format specific to the computation type
     * @param response AssignTaskResponse containing the results
     * @param logs List to add log messages to
     */
    fun logOutput(response: AssignTaskResponse, logs: SnapshotStateList<String>)

    /**
     * Gets the worker's friendly name for UI and logging purposes
     * @return String representing the worker's name
     */
    fun getWorkerName(): String
}
