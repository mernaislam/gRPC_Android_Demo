package com.example.network.interfaces

import com.example.protos.AssignTaskRequest
import com.example.protos.AssignTaskResponse

/**
 * Interface defining the strategy for task distribution and result aggregation.
 * Different implementations can handle different types of computations (matrix, image processing, etc.)
 */
interface ICoordinatorStrategy {
    /**
     * Distributes tasks among available workers
     * @param totalSize Total number of tasks to distribute
     * @param availableWorkers List of available worker addresses and names
     * @return Map of worker to their assigned task range
     */
    fun distributeTasks(
        totalSize: Int,
        availableWorkers: List<Pair<String, String>>,
        logs: MutableList<String>
    ): Any

    /**
     * Builds a sub-request for a specific worker based on their assigned range
     * @param request Original request containing all data
     * @param range Range of tasks assigned to the worker
     * @return AssignTaskRequest containing only the worker's assigned portion
     */
    fun buildSubRequest(
        request: AssignTaskRequest,
        range: IntRange
    ): AssignTaskRequest

    /**
     * Aggregates results from all workers into a single response
     * @param results List of worker addresses and their computation results
     * @return Combined AssignTaskResponse
     */
    fun aggregateResults(
        results: List<Pair<String, WorkerResult>>
    ): AssignTaskResponse

    /**
     * Logs input data in a format specific to the computation type
     * @param request Original request containing all data
     * @param logs List to add log messages to
     */
    fun logInput(request: AssignTaskRequest, logs: MutableList<String>)
}

/**
 * Data class representing a worker's computation result
 */
data class WorkerResult(
    val response: AssignTaskResponse,
    val assignedRange: List<Int>,
    val computationTime: Long
) 