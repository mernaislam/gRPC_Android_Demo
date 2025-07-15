package com.example.network.matrix

import com.example.network.interfaces.ICoordinatorStrategy
import com.example.network.interfaces.INetworkService
import com.example.network.interfaces.WorkerResult
import com.example.network.network.Coordinator
import com.example.network.ui.UiEventWorkStatus
import com.example.network.ui.WorkEventBus
import com.example.protos.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/**
 * Strategy for coordinating matrix multiplication tasks across multiple workers.
 * This class implements the ICoordinatorStrategy interface to distribute and aggregate results.
 */
class MatrixCoordinatorStrategy : ICoordinatorStrategy {

    /**
     * Distributes the total number of rows across available workers.
     * @param totalSize The total number of rows in the matrix.
     * @param availableWorkers List of available worker addresses and names.
     * @param logs Mutable list for logging events.
     * @return A list of pairs, where each pair is a worker and their assigned row range.
     */
    override fun distributeTasks(
        totalSize: Int,
        availableWorkers: List<Pair<String, String>>,
        logs: MutableList<String>
    ): Any {
        logs.add("Coordinator: Starting task distribution for $totalSize rows across ${availableWorkers.size} workers")

        val contributingWorkers = mutableSetOf<Pair<String, String>>()
        val assignments = mutableListOf<Pair<Pair<String, String>, IntRange>>()
        
        // For very small matrices, use a simpler distribution
        if (totalSize <= availableWorkers.size) {
            logs.add("Coordinator: Using simple distribution - matrix too small for all workers")
            // If matrix is smaller than worker count, only use first N workers
            val simpleAssignments = availableWorkers.take(totalSize).mapIndexed { index, worker ->
                contributingWorkers.add(worker)
                logs.add("Coordinator: Assigning row $index to worker ${worker.second}")
                worker to (index..index)
            }
            
            // Notify idle workers
            val idleWorkers = availableWorkers.filterNot { it in contributingWorkers }
            idleWorkers.forEach { worker ->
                logs.add("Coordinator: Worker ${worker.second} is idle - no tasks available")
                WorkEventBus.post(
                    UiEventWorkStatus.TaskNotAssigned(
                        humanName = worker.second,
                    )
                )
            }
            
            return simpleAssignments
        }

        val baseRowsPerWorker = totalSize / availableWorkers.size
        var remainingRows = totalSize % availableWorkers.size

        var currentOffset = 0
        availableWorkers.forEach { worker ->
            val rowsForThisWorker = baseRowsPerWorker + if (remainingRows > 0) 1 else 0

            if (rowsForThisWorker > 0) {
                val range = currentOffset until (currentOffset + rowsForThisWorker)
                assignments.add(worker to range)
                contributingWorkers.add(worker)
                logs.add("Coordinator: Assigning rows $range to worker ${worker.second}")

                currentOffset += rowsForThisWorker
                if (remainingRows > 0) remainingRows--
            }
        }

        // Notify idle workers
        val idleWorkers = availableWorkers.filterNot { it in contributingWorkers }
        idleWorkers.forEach { worker ->
            logs.add("Coordinator: Worker ${worker.second} is idle - no tasks available")
            WorkEventBus.post(
                UiEventWorkStatus.TaskNotAssigned(
                    humanName = worker.second,
                )
            )
        }

        return assignments
    }

    /**
     * Builds a sub-request for a specific row range.
     * @param request The original AssignTaskRequest.
     * @param range The row range to be processed.
     * @return A new AssignTaskRequest containing only the rows within the specified range.
     */
    override fun buildSubRequest(
        request: AssignTaskRequest,
        range: IntRange
    ): AssignTaskRequest {
        val taskRequest = TaskRequest.newBuilder()
            .addAllRowsA(request.taskRequest.rowsAList.subList(range.first, range.last + 1))
            .addAllRowsB(request.taskRequest.rowsBList)
            .setStartRow(range.first)
            .setNumRows(range.count())
            .build()

        return AssignTaskRequest.newBuilder()
            .setTaskRequest(taskRequest)
            .build()
    }

    /**
     * Aggregates results from multiple workers into a single AssignTaskResponse.
     * @param results A list of pairs, where each pair is a worker address and their WorkerResult.
     * @return An AssignTaskResponse containing combined results and worker information.
     */
    override fun aggregateResults(
        results: List<Pair<String, WorkerResult>>
    ): AssignTaskResponse {
        // Sort results by worker address to maintain order
        val sortedResults = results.sortedBy { it.first }
        
        // Combine all results in order
        val combinedResults = sortedResults.flatMap { (_, result) ->
            result.response.resultList
        }

        // Create worker info map and friendly names map
        val workerInfoMap = mutableMapOf<String, RowIndices>()
        val friendlyNamesMap = mutableMapOf<String, String>()

        // Group results by worker address to combine original and redistributed rows
        val workerResults = sortedResults.groupBy { it.first }
        
        workerResults.forEach { (workerAddress, workerResults) ->
            // Combine all rows (both original and redistributed) for this worker
            val allRows = workerResults.flatMap { it.second.assignedRange }.sorted()
            
            // Add worker info with all rows
            workerInfoMap[workerAddress] = RowIndices.newBuilder()
                .addAllValues(allRows)
                .build()

            // Add friendly name from the worker's response
            workerResults.firstOrNull()?.second?.response?.friendlyNamesMap?.get(workerAddress)?.let { friendlyName ->
                friendlyNamesMap[workerAddress] = friendlyName
            }
        }

        return AssignTaskResponse.newBuilder()
            .addAllResult(combinedResults)
            .putAllWorkerInfo(workerInfoMap)
            .putAllFriendlyNames(friendlyNamesMap)
            .build()
    }

    /**
     * Logs the input matrices for debugging purposes.
     * @param request The AssignTaskRequest containing the matrices.
     * @param logs Mutable list for logging events.
     */
    override fun logInput(request: AssignTaskRequest, logs: MutableList<String>) {
        logs.add("Matrix multiplication task:")
        logs.add(formatMatrix(request.taskRequest.rowsAList, "Matrix A"))
        logs.add(formatMatrix(request.taskRequest.rowsBList, "Matrix B"))
    }

    /**
     * Formats a matrix for logging, including its name and values.
     * @param matrix The list of rows representing the matrix.
     * @param name The name of the matrix (e.g., "Matrix A", "Matrix B").
     * @param decimalPlaces The number of decimal places to format values.
     * @return A string representation of the matrix.
     */
    private fun formatMatrix(matrix: List<Row>, name: String, decimalPlaces: Int = 2): String {
        val builder = StringBuilder()
        builder.append("$name:\n")
        matrix.forEach { row ->
            builder.append(
                row.valuesList.joinToString(prefix = "[", postfix = "]", separator = ", ") {
                    "%.${decimalPlaces}f".format(it)
                }
            )
            builder.append("\n")
        }
        return builder.toString()
    }

    /**
     * Starts the distributed matrix multiplication process.
     * @param request The AssignTaskRequest containing the matrices.
     * @param availableWorkers List of available worker addresses and names.
     * @param logs Mutable list for logging events.
     * @param networkService The INetworkService for executing tasks.
     * @param coordinator The Coordinator instance for marking worker availability.
     * @return The result matrix as a List<List<Double>>.
     */
    suspend fun start(
        request: AssignTaskRequest,
        availableWorkers: List<Pair<String, String>>,
        logs: MutableList<String>,
        networkService: INetworkService,
        coordinator: Coordinator
    ): List<List<Double>> {
        val totalRows = request.taskRequest.rowsAList.size
        logInput(request, logs)
        logs.add("Starting distributed matrix multiplication for $totalRows rows")
        val taskDistribution = distributeTasks(totalRows, availableWorkers, logs) as List<Pair<Pair<String, String>, IntRange>>
        if (taskDistribution.isEmpty()) {
            throw Exception("Failed to distribute tasks among workers")
        }
        val results = java.util.Collections.synchronizedList(mutableListOf<Pair<String, com.example.network.interfaces.WorkerResult>>())
        val failedTasks = java.util.concurrent.ConcurrentLinkedQueue<Pair<List<Int>, String>>()
        val availableWorkerQueue = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
        availableWorkerQueue.addAll(availableWorkers)
        val resultsLock = Any()
        val unavailableWorkers = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        // Helper to get currently available workers
        fun getAvailableWorkers(): List<Pair<String, String>> =
            availableWorkers.filter { !unavailableWorkers.contains(it.first) }
        // Helper to mark worker unavailable
        fun markUnavailable(address: String) {
            unavailableWorkers.add(address)
        }
        // Helper to mark worker available
        fun markAvailable(address: String) {
            unavailableWorkers.remove(address)
        }
        // --- Initial round ---
        kotlinx.coroutines.coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Job>()
            taskDistribution.forEach { (worker, range) ->
                val job = async(Dispatchers.IO) {
                    var currentWorker = worker
                    var currentRange = range
                    while (true) {
                        try {
                            WorkEventBus.post(
                                UiEventWorkStatus.TaskAssigned(
                                    humanName = currentWorker.second,
                                    portions = currentRange.toList()
                                )
                            )
                            val subRequest = buildSubRequest(request, currentRange)
                            val startTime = System.currentTimeMillis()
                            val response = try {
                                networkService.executeTask(currentWorker.first, subRequest)
                            } catch (e: Exception) {
                                null
                            }
                            val endTime = System.currentTimeMillis()
                            if (response != null) {
                                synchronized(resultsLock) {
                                    results.add(currentWorker.first to com.example.network.interfaces.WorkerResult(
                                        response = response,
                                        assignedRange = currentRange.toList(),
                                        computationTime = endTime - startTime
                                    ))
                                }
                                logs.add("Worker ${currentWorker.second} successfully processed rows ${currentRange.first} to ${currentRange.last} in ${endTime - startTime}ms")
                                WorkEventBus.post(
                                    UiEventWorkStatus.TaskCompleted(
                                        humanName = currentWorker.second,
                                        portions = currentRange.toList(),
                                        computationTime = endTime - startTime
                                    )
                                )
                                coordinator.markWorkerAvailable(currentWorker.first, currentWorker.second)
                                markAvailable(currentWorker.first)
                            } else {
                                failedTasks.add(currentRange.toList() to currentWorker.first)
                                logs.add("Worker ${currentWorker.second} failed to process rows ${currentRange.first} to ${currentRange.last}")
                                WorkEventBus.post(
                                    UiEventWorkStatus.Error(
                                        humanName = currentWorker.second,
                                        message = "Failed to process rows ${currentRange.first} to ${currentRange.last}"
                                    )
                                )
                                coordinator.markWorkerUnavailable(currentWorker.first)
                                markUnavailable(currentWorker.first)
                            }
                        } catch (e: Exception) {
                            failedTasks.add(currentRange.toList() to currentWorker.first)
                            logs.add("Error processing task for worker ${currentWorker.second}: ${e.message}")
                            WorkEventBus.post(
                                UiEventWorkStatus.Error(
                                    humanName = currentWorker.second,
                                    message = "Exception: ${e.message}"
                                )
                            )
                            coordinator.markWorkerUnavailable(currentWorker.first)
                            markUnavailable(currentWorker.first)
                        }
                        // No more failed tasks or no available workers in this round
                        break
                    }
                }
                jobs.add(job)
            }
            jobs.forEach { it.join() }
        }
        // --- Redistribute failed tasks evenly ---
        while (failedTasks.isNotEmpty()) {
            val failedList = mutableListOf<Pair<List<Int>, String>>()
            while (true) {
                val t = failedTasks.poll() ?: break
                failedList.add(t)
            }
            val currentAvailable = getAvailableWorkers()
            if (currentAvailable.isEmpty()) {
                logs.add("No available workers left for redistribution. ${failedList.size} failed portions remain.")
                break
            }
            // Evenly distribute failed tasks among available workers
            val chunks = currentAvailable.map { mutableListOf<Pair<List<Int>, String>>() }
            failedList.forEachIndexed { idx, task ->
                chunks[idx % currentAvailable.size].add(task)
            }
            kotlinx.coroutines.coroutineScope {
                val jobs = mutableListOf<kotlinx.coroutines.Job>()
                for ((workerIdx, worker) in currentAvailable.withIndex()) {
                    val workerTasks = chunks[workerIdx]
                    if (workerTasks.isEmpty()) continue
                    val job = async(Dispatchers.IO) {
                        for ((portion, _) in workerTasks) {
                            try {
                                WorkEventBus.post(
                                    UiEventWorkStatus.TaskAssigned(
                                        humanName = worker.second,
                                        portions = portion
                                    )
                                )
                                val subRequest = buildSubRequest(request, portion.first()..portion.last())
                                val startTime = System.currentTimeMillis()
                                val response = try {
                                    networkService.executeTask(worker.first, subRequest)
                                } catch (e: Exception) {
                                    null
                                }
                                val endTime = System.currentTimeMillis()
                                if (response != null) {
                                    synchronized(resultsLock) {
                                        results.add(worker.first to com.example.network.interfaces.WorkerResult(
                                            response = response,
                                            assignedRange = portion,
                                            computationTime = endTime - startTime
                                        ))
                                    }
                                    logs.add("[REDISTRIBUTION] Worker ${worker.second} successfully processed rows ${portion.first()} to ${portion.last()} in ${endTime - startTime}ms")
                                    WorkEventBus.post(
                                        UiEventWorkStatus.TaskCompleted(
                                            humanName = worker.second,
                                            portions = portion,
                                            computationTime = endTime - startTime
                                        )
                                    )
                                    coordinator.markWorkerAvailable(worker.first, worker.second)
                                    markAvailable(worker.first)
                                } else {
                                    failedTasks.add(portion to worker.first)
                                    logs.add("[REDISTRIBUTION] Worker ${worker.second} failed to process rows ${portion.first()} to ${portion.last()}")
                                    WorkEventBus.post(
                                        UiEventWorkStatus.Error(
                                            humanName = worker.second,
                                            message = "Failed to process rows ${portion.first()} to ${portion.last()}"
                                        )
                                    )
                                    coordinator.markWorkerUnavailable(worker.first)
                                    markUnavailable(worker.first)
                                }
                            } catch (e: Exception) {
                                failedTasks.add(portion to worker.first)
                                logs.add("[REDISTRIBUTION] Error processing task for worker ${worker.second}: ${e.message}")
                                WorkEventBus.post(
                                    UiEventWorkStatus.Error(
                                        humanName = worker.second,
                                        message = "Exception: ${e.message}"
                                    )
                                )
                                coordinator.markWorkerUnavailable(worker.first)
                                markUnavailable(worker.first)
                            }
                        }
                    }
                    jobs.add(job)
                }
                jobs.forEach { it.join() }
            }
        }
        if (results.isEmpty()) {
            throw Exception("All workers failed to process their tasks")
        }
        // Aggregate results by row index
        val resultRows = Array<kotlin.collections.List<Double>?>(totalRows) { null }
        results.forEach { (_, workerResult) ->
            workerResult.response.resultList.forEachIndexed { idx, row ->
                val globalIdx = workerResult.assignedRange[idx]
                resultRows[globalIdx] = row.valuesList
            }
        }
        // Validate all rows are filled
        if (resultRows.any { it == null }) {
            throw Exception("Missing results for some rows. Expected $totalRows, got ${resultRows.count { it != null }} rows")
        }
        logs.add("Successfully processed ${resultRows.size} rows for matrix multiplication")
        return resultRows.map { it!! }
    }
} 