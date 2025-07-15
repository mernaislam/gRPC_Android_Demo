package com.example.network.worker

import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.network.interfaces.IComputationStrategy
import com.example.protos.*
import io.grpc.ManagedChannel
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Worker is a gRPC server implementation that handles distributed computation tasks.
 * It is designed to run on worker devices in a distributed computation system.
 *
 * The worker receives assigned tasks from a coordinator, computes the result
 * for its assigned portion, and returns them back via gRPC.
 */
class Worker(
    private val stub: TaskServiceGrpc.TaskServiceStub,
    private val channel: ManagedChannel,
    private val scope: CoroutineScope,
    private val logs: SnapshotStateList<String>,
    private val onFinish: () -> Unit,
    private val strategy: IComputationStrategy,
    private val workerEvents: WorkerEvents
) : TaskServiceGrpc.TaskServiceImplBase() {

    /**
     * Performs the computation task assigned by the coordinator.
     *
     * This method is invoked remotely by a coordinator via gRPC when a task is assigned.
     * It uses the provided computation strategy to process the task and return results.
     *
     * @param request The gRPC request containing task data
     * @param responseObserver Used to send the result back to the coordinator
     */
    override fun assignTask(
        request: AssignTaskRequest,
        responseObserver: StreamObserver<AssignTaskResponse>
    ) {
        scope.launch(Dispatchers.Default) {
            try {
                val response = strategy.computeTask(request)
                responseObserver.onNext(response)
                responseObserver.onCompleted()
            } catch (e: Exception) {
                logs.add("Error computing task: ${e.message}")
                responseObserver.onError(e)
            }
        }
    }

    /**
     * Starts the worker by initiating a computation task.
     * Uses the provided strategy to generate input and build the request.
     */
    fun start() {
        val startTime = System.currentTimeMillis()

        try {
            // Use strategy to generate input and build request
            val (input1, input2) = strategy.generateInput()

            strategy.logInput(input1, input2, logs)

            val request = strategy.buildRequest(input1, input2)
            stub.assignTask(request, object : StreamObserver<AssignTaskResponse> {
                override fun onNext(value: AssignTaskResponse) {
                    try {
                        val endTime = System.currentTimeMillis()
                        val computationTime = endTime - startTime

                        // Update metrics for each worker
                        value.workerInfoMap.keys.forEach { worker ->
                            workerEvents.onWorkerMetricsUpdated(worker, computationTime)
                        }

                        scope.launch(Dispatchers.Main) {
                            try {
                                strategy.logOutput(value, logs)
                                workerEvents.logWorkerContributions(value)
                            } catch (e: Exception) {
                                logs.add("Error processing response: ${e.message}")
                            } finally {
                                workerEvents.shutdownWorkerChannel(channel)
                                onFinish()
                            }
                        }
                    } catch (e: Exception) {
                        logs.add("Error handling response: ${e.message}")
                        channel.shutdownNow()
                        onFinish()
                    }
                }

                override fun onError(t: Throwable) {
                    scope.launch(Dispatchers.Main) {
                        logs.add("Worker error: ${t.localizedMessage ?: "Unknown error"}")
                    }
                    channel.shutdownNow()
                    onFinish()
                }

                override fun onCompleted() {
                    // Do nothing for single-response calls
                }
            })
        } catch (e: Exception) {
            logs.add("Error starting worker: ${e.message}")
            channel.shutdownNow()
            onFinish()
        }
    }
}
