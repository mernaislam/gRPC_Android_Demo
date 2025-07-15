package com.example.network.network

import com.example.network.interfaces.ICoordinatorStrategy
import com.example.network.interfaces.INetworkService
import com.example.network.matrix.*
import com.example.network.interfaces.WorkerResult
import com.example.network.ui.*
import com.example.protos.*
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Coordinator is a gRPC server implementation of TaskService, acting as a coordinator and a local worker.
 * It handles worker registration, task distribution, computation (local and remote),
 * and aggregates the results from multiple workers.
 */
class Coordinator(
    private val logs: MutableList<String>,
    private val localAddress: String,
    private val taskFriendlyName: String,
    private val strategy: ICoordinatorStrategy = MatrixCoordinatorStrategy()
) : TaskServiceGrpc.TaskServiceImplBase() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val computation = MatrixComputationStrategy(taskFriendlyName, workerAddress = localAddress)
    private val workers = ConcurrentHashMap.newKeySet<Pair<String, String>>().apply {
        add(localAddress to taskFriendlyName)
    }
    private val stubs = ConcurrentHashMap<String, TaskServiceGrpc.TaskServiceBlockingStub>()
    private val workerPerformance = ConcurrentHashMap<String, WorkerMetrics>()
    private val metricsLock = Object()

    data class WorkerMetrics(
        @Volatile var successfulTasks: Int = 0,
        @Volatile var failedTasks: Int = 0,
        @Volatile var averageResponseTime: Double = 0.0,
        @Volatile var lastResponseTime: Long = 0,
        @Volatile var isAvailable: Boolean = true
    )

    /**
     * Returns a cached gRPC stub for the given worker address or creates a new one if not cached.
     * @param address The address of the worker.
     * @return The blocking stub for the worker.
     */
    private fun getOrCreateStub(address: String): TaskServiceGrpc.TaskServiceBlockingStub {
        return stubs.computeIfAbsent(address) {
            val (host, portStr) = it.split(":")
            val port = portStr.toIntOrNull() ?: throw IllegalArgumentException("Invalid port in $address")
            io.grpc.ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build()
                .let { channel -> TaskServiceGrpc.newBlockingStub(channel) }
        }
    }

    /**
     * Handles worker registration requests from remote workers.
     * @param request The registration request.
     * @param responseObserver The gRPC response observer.
     */
    override fun registerWorker(
        request: RegisterWorkerRequest,
        responseObserver: StreamObserver<RegisterWorkerResponse>
    ) {
        if (request.workerAddress.isEmpty() && request.friendlyName.isEmpty()) {
            val response = RegisterWorkerResponse.newBuilder()
                .setSuccess(true)
                .setFriendlyName(taskFriendlyName)
                .build()
            responseObserver.onNext(response)
            responseObserver.onCompleted()
            return
        }

        registerWorkerInternal(request)
        val response = RegisterWorkerResponse.newBuilder()
            .setSuccess(true)
            .setFriendlyName(taskFriendlyName)
            .build()
        responseObserver.onNext(response)
        responseObserver.onCompleted()

        workerPerformance[request.workerAddress] = WorkerMetrics()
    }

    /**
     * Registers or updates a worker's internal state and metrics.
     * @param request The registration request.
     */
    private fun registerWorkerInternal(request: RegisterWorkerRequest) {
        val workerPair = Pair(request.workerAddress, request.friendlyName)
        val isNewWorker = workers.add(workerPair)
        
        synchronized(metricsLock) {
            val metrics = workerPerformance.getOrPut(request.workerAddress) { WorkerMetrics() }
            metrics.isAvailable = true
            metrics.failedTasks = 0
            metrics.successfulTasks = 0
            metrics.averageResponseTime = 0.0
            metrics.lastResponseTime = 0
            logs.add("Coordinator: Worker ${request.friendlyName} is now marked as available")
        }

        if (isNewWorker) {
            logs.add("Coordinator: Registered new worker at ${request.workerAddress} (${request.friendlyName})")
        } else {
            logs.add("Coordinator: Worker ${request.workerAddress} (${request.friendlyName}) re-registered")
        }

        DeviceEventBus.post(
            UiEventDeviceStatus.WorkerStatusChanged(
                humanName = request.friendlyName,
                online = true
            )
        )
    }

    /**
     * Handles task assignment requests, distributes tasks, and aggregates results.
     * @param request The task assignment request.
     * @param responseObserver The gRPC response observer.
     */
    override fun assignTask(
        request: AssignTaskRequest,
        responseObserver: StreamObserver<AssignTaskResponse>
    ) {
        resetState()

        // Automatically prune unavailable workers before distribution
        synchronized(metricsLock) {
            val unavailable = workers.filter { workerPerformance[it.first]?.isAvailable == false }
            unavailable.forEach { workers.remove(it) }
        }

        strategy.logInput(request, logs)

        if (workers.isEmpty()) {
            logs.add("Coordinator: No workers to assign tasks")
            responseObserver.onNext(AssignTaskResponse.getDefaultInstance())
            responseObserver.onCompleted()
            return
        }

        val availableWorkers = workers.filter { worker ->
            val metrics = workerPerformance[worker.first]
            val isAvailable = metrics?.isAvailable ?: true
            if (isAvailable) {
                logs.add("Coordinator: Worker ${worker.second} is available for task distribution")
            } else {
                logs.add("Coordinator: Worker ${worker.second} is marked as unavailable, skipping in distribution")
            }
            isAvailable
        }.sortedBy { it.first }

        if (availableWorkers.isEmpty()) {
            logs.add("Coordinator: No available workers for task distribution")
            responseObserver.onNext(AssignTaskResponse.getDefaultInstance())
            responseObserver.onCompleted()
            return
        }

        logs.add("Coordinator: Distributing tasks to ${availableWorkers.size} available workers")
        val taskDistribution = strategy.distributeTasks(request.taskRequest.rowsAList.size, availableWorkers, logs) as List<Pair<Pair<String, String>, IntRange>>
        val failedTasks = mutableListOf<Pair<List<Int>, String>>()

        CoroutineScope(Dispatchers.Default).launch {
            val deferredResults = taskDistribution.map { (worker, taskRange) ->
                async {
                    val portions = (taskRange.first..taskRange.last).toList()
                    logs.add("Coordinator: Assigning portions [${taskRange.first}..${taskRange.last}] to ${worker.second}")

                    WorkEventBus.post(
                        UiEventWorkStatus.TaskAssigned(
                            humanName = worker.second,
                            portions = portions
                        )
                    )

                    val subRequest = strategy.buildSubRequest(request, taskRange)
                    val startTime = System.currentTimeMillis()
                    val result = executeTask(worker, subRequest)
                    val endTime = System.currentTimeMillis()
                    val computationTime = endTime - startTime

                    if (result != null) {
                        updateWorkerMetrics(worker.first, true, computationTime)
                        logs.add("Coordinator: Worker ${worker.second} successfully computed portions $portions")
                        WorkEventBus.post(
                            UiEventWorkStatus.TaskCompleted(
                                humanName = worker.second,
                                portions = portions,
                                computationTime = computationTime
                            )
                        )
                        worker.first to WorkerResult(result, portions, computationTime)
                    } else {
                        logs.add("Coordinator: Task failed for worker ${worker.second}, marking for redistribution")
                        updateWorkerMetrics(worker.first, false, computationTime)
                        failedTasks.add(portions to worker.first)
                        null
                    }
                }
            }
            val resultsWithInfo = deferredResults.mapNotNull { it.await() }.toMutableList()

            if (failedTasks.isNotEmpty()) {
                logs.add("Coordinator: ${failedTasks.size} tasks failed during execution")
                val availableForRedistribution = availableWorkers.filter { worker ->
                    !failedTasks.any { it.second == worker.first }
                }
                if (availableForRedistribution.isNotEmpty()) {
                    logs.add("Coordinator: Redistributing failed tasks to: ${availableForRedistribution.map { it.second }}")
                    val redistributedResults = redistributeFailedTasks(failedTasks, availableForRedistribution, request)
                    resultsWithInfo.addAll(redistributedResults)
                } else {
                    logs.add("Coordinator: No workers available for redistribution")
                }
            }

            val response = strategy.aggregateResults(resultsWithInfo)
            responseObserver.onNext(response)
            responseObserver.onCompleted()
            failedTasks.clear()
        }
    }

    /**
     * Updates the metrics for a worker after a task attempt.
     * @param workerId The worker's address.
     * @param success Whether the task was successful.
     * @param computationTime The time taken for the computation.
     */
    private fun updateWorkerMetrics(
        workerId: String,
        success: Boolean,
        computationTime: Long
    ) {
        synchronized(metricsLock) {
            val metrics = workerPerformance.getOrPut(workerId) { WorkerMetrics() }
            val workerInfo = workers.find { it.first == workerId }
            
            if (success) {
                metrics.successfulTasks++
                metrics.lastResponseTime = computationTime
                metrics.averageResponseTime = (metrics.averageResponseTime * (metrics.successfulTasks - 1) + computationTime) / metrics.successfulTasks
                metrics.failedTasks = 0
                if (!metrics.isAvailable) {
                    metrics.isAvailable = true
                    if (workerInfo != null) {
                        logs.add("Worker ${workerInfo.second} is now available")
                        DeviceEventBus.post(
                            UiEventDeviceStatus.WorkerStatusChanged(
                                humanName = workerInfo.second,
                                online = true
                            )
                        )
                    }
                }
            } else {
                metrics.failedTasks++
                if (metrics.failedTasks >= 3) {
                    if (metrics.isAvailable) {
                        metrics.isAvailable = false
                        if (workerInfo != null) {
                            logs.add("Worker ${workerInfo.second} is now unavailable")
                            DeviceEventBus.post(
                                UiEventDeviceStatus.WorkerStatusChanged(
                                    humanName = workerInfo.second,
                                    online = false
                                )
                            )
                            // Remove the worker from the active workers list
                            workers.remove(workerInfo)
                            // Clean up any associated resources
                            stubs.remove(workerId)
                        }
                    }
                }
            }
        }
    }

    /**
     * Executes a task on a worker, either locally or remotely.
     * @param worker The worker's address and name.
     * @param subRequest The sub-task request.
     * @return The response, or null if execution failed.
     */
    private fun executeTask(
        worker: Pair<String, String>,
        subRequest: AssignTaskRequest
    ): AssignTaskResponse? {
        return try {
            if (worker.first == localAddress) {
                computation.computeTask(subRequest)
            } else {
                synchronized(metricsLock) {
                    if (workerPerformance[worker.first]?.isAvailable == false) {
                        logs.add("Coordinator: Skipping task execution for unavailable worker ${worker.second}")
                        // Clean up the worker's resources
                        stubs.remove(worker.first)
                        workers.remove(worker)
                        null
                    } else {
                        callRemoteWorker(worker, subRequest)
                    }
                }
            }
        } catch (e: Exception) {
            logs.add("Coordinator: Task execution failed for ${worker.first}: ${e.message}")
            // Clean up on error
            synchronized(metricsLock) {
                stubs.remove(worker.first)
                workers.remove(worker)
            }
            null
        }
    }

    /**
     * Calls a remote worker to execute a task via gRPC.
     * @param worker The worker's address and name.
     * @param request The sub-task request.
     * @return The response, or null if execution failed.
     */
    private fun callRemoteWorker(
        worker: Pair<String, String>,
        request: AssignTaskRequest
    ): AssignTaskResponse? {
        val stub = getOrCreateStub(worker.first)
        return try {
            val response = stub.withDeadlineAfter(30, TimeUnit.SECONDS)
                .assignTask(request)
            
            if (response.resultCount == 0) {
                logs.add("Empty response from worker ${worker.second}")
                updateWorkerMetrics(worker.first, false, 0)
                null
            } else {
                updateWorkerMetrics(worker.first, true, 0)
                response
            }
        } catch (ex: io.grpc.StatusRuntimeException) {
            if (ex.status.code == io.grpc.Status.Code.DEADLINE_EXCEEDED) {
                logs.add("Worker ${worker.second} is slow (timeout), not marking offline.")
                return null
            } else {
                logs.add("Error assigning task to ${worker.first}: ${ex.message}")
                scope.launch {
                    WorkEventBus.post(
                        UiEventWorkStatus.Error(
                            humanName = worker.second,
                            message = "Cannot compute work now: ${ex.message}"
                        )
                    )
                }
                updateWorkerMetrics(worker.first, false, 0)
                return null
            }
        } catch (ex: Exception) {
            logs.add("Error assigning task to ${worker.first}: ${ex.message}")
            scope.launch {
                WorkEventBus.post(
                    UiEventWorkStatus.Error(
                        humanName = worker.second,
                        message = "Cannot compute work now: ${ex.message}"
                    )
                )
            }
            updateWorkerMetrics(worker.first, false, 0)
            null
        }
    }

    /**
     * Redistributes failed tasks among available workers and collects results.
     * @param failedTasks The list of failed portions and their original worker addresses.
     * @param availableWorkers The list of available workers for redistribution.
     * @param request The original task request.
     * @return A list of successful results from redistribution.
     */
    private fun redistributeFailedTasks(
        failedTasks: List<Pair<List<Int>, String>>,
        availableWorkers: List<Pair<String, String>>,
        request: AssignTaskRequest
    ): List<Pair<String, WorkerResult>> {
        val results = mutableListOf<Pair<String, WorkerResult>>()
        
        val tasksGroupedByWorker = failedTasks.groupBy { it.second }
        
        tasksGroupedByWorker.forEach { (failedWorkerAddress, tasksFromWorker) ->
            val originalWorker = workers.find { it.first == failedWorkerAddress }
            if (originalWorker != null && workerPerformance[failedWorkerAddress]?.isAvailable == true) {
                tasksFromWorker.forEach { (portions, _) ->
                    val startTime = System.currentTimeMillis()
                    val range: IntRange = portions.first()..portions.last()
                    val subRequest = strategy.buildSubRequest(request, range)

                    try {
                        val result = executeTask(originalWorker, subRequest)
                        val endTime = System.currentTimeMillis()
                        
                        if (result != null) {
                            results.add(originalWorker.first to WorkerResult(
                                response = result,
                                assignedRange = portions,
                                computationTime = endTime - startTime
                            )
                            )
                            updateWorkerMetrics(originalWorker.first, true, endTime - startTime)
                            logs.add("Worker ${originalWorker.second} is back online and recomputed its original portions [${portions.joinToString(", ")}]")
                            
                            WorkEventBus.post(
                                UiEventWorkStatus.TaskCompleted(
                                    humanName = originalWorker.second,
                                    portions = portions,
                                    computationTime = endTime - startTime
                                )
                            )
                            return@forEach
                        }
                    } catch (e: Exception) {
                        logs.add("Failed to retry with original worker ${originalWorker.second}: ${e.message}")
                    }
                }
            }

            val availableWorkersExceptFailed = availableWorkers.filter { it.first != failedWorkerAddress }
            if (availableWorkersExceptFailed.isEmpty()) {
                logs.add("No available workers to redistribute tasks from worker $failedWorkerAddress")
                return@forEach
            }

            val sortedWorkers = availableWorkersExceptFailed.sortedBy { worker ->
                workerPerformance[worker.first]?.lastResponseTime ?: Long.MAX_VALUE
            }

            tasksFromWorker.forEachIndexed { index, (portions, _) ->
                val targetWorker = sortedWorkers[index % sortedWorkers.size]
                
                val startTime = System.currentTimeMillis()
                val range: IntRange = portions.first()..portions.last()
                val subRequest = strategy.buildSubRequest(request, range)

                try {
                    val result = executeTask(targetWorker, subRequest)
                    val endTime = System.currentTimeMillis()
                    
                    if (result != null) {
                        results.add(targetWorker.first to WorkerResult(
                            response = result,
                            assignedRange = portions,
                            computationTime = endTime - startTime
                        )
                        )
                        updateWorkerMetrics(targetWorker.first, true, endTime - startTime)
                        logs.add("Successfully redistributed portions [${portions.joinToString(", ")}] to ${targetWorker.second}")
                        
                        WorkEventBus.post(
                            UiEventWorkStatus.TaskCompleted(
                                humanName = targetWorker.second,
                                portions = portions,
                                computationTime = endTime - startTime
                            )
                        )
                    } else {
                        logs.add("Redistribution to ${targetWorker.second} failed")
                        updateWorkerMetrics(targetWorker.first, false, endTime - startTime)
                    }
                } catch (e: Exception) {
                    logs.add("Error during redistribution to ${targetWorker.second}: ${e.message}")
                    updateWorkerMetrics(targetWorker.first, false, 0)
                }
            }
        }

        return results
    }

    /**
     * Adds a worker to the coordinator's set of known workers.
     * @param address The worker's address.
     * @param friendlyName The worker's friendly name.
     */
    fun addWorker(address: String, friendlyName: String) {
        if (workers.add(address to friendlyName)) {
            logs.add("Coordinator: Pre-registered worker $address via broadcast")
        }
    }

    /**
     * Marks a worker as unavailable and removes it from the active set.
     * @param address The worker's address.
     */
    @Synchronized
    fun markWorkerUnavailable(address: String) {
        val workerInfo = workers.find { it.first == address }
        if (workerInfo != null) {
            workerPerformance[address]?.isAvailable = false
            workers.remove(workerInfo)
            stubs.remove(address)
            logs.add("Coordinator: Worker $address marked as unavailable and removed due to offline status")
            // Notify UI that this worker is now idle/offline
            WorkEventBus.post(
                UiEventWorkStatus.TaskNotAssigned(
                    humanName = workerInfo.second
                )
            )
        }
    }

    /**
     * Marks a worker as available and adds it back to the active set if not present.
     * @param address The worker's address.
     * @param friendlyName The worker's friendly name.
     */
    fun markWorkerAvailable(address: String, friendlyName: String) {
        synchronized(metricsLock) {
            val workerInfo = workers.find { it.first == address }
            if (workerInfo == null) {
                workers.add(address to friendlyName)
                logs.add("Coordinator: Worker $address ($friendlyName) marked as available and added back to workers list")
            }
            workerPerformance[address]?.isAvailable = true
        }
    }

    /**
     * Resets the state and metrics for all workers before a new computation.
     */
    private fun resetState() {
        synchronized(metricsLock) {
            logs.add("Coordinator: Resetting worker performance metrics for new computation")

            val currentMetrics = workerPerformance.mapValues { (_, metrics) -> 
                WorkerMetrics(
                    isAvailable = metrics.isAvailable,
                    failedTasks = metrics.failedTasks
                )
            }
            
            workerPerformance.clear()
            
            workers.forEach { (address, friendlyName) ->
                val previousMetrics = currentMetrics[address]
                workerPerformance[address] = WorkerMetrics().apply {
                    isAvailable = previousMetrics?.isAvailable ?: true
                    failedTasks = previousMetrics?.failedTasks ?: 0
                    
                    if (isAvailable) {
                        logs.add("Coordinator: Preserving available status for worker $friendlyName")
                    } else {
                        logs.add("Coordinator: Worker $friendlyName is still marked as unavailable")
                    }
                }
            }
        }
    }
}
