package com.example.network.matrix

import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.network.interfaces.IComputationStrategy
import com.example.protos.*
import kotlin.math.round
import kotlin.random.Random

/**
 * A strategy for computing matrix multiplication tasks.
 * This class handles the generation of input matrices, building task requests,
 * computing results, and logging relevant information.
 */
class MatrixComputationStrategy(
    private val workerName: String,
    private val matrixSize: Int = 100,
    private val workerAddress: String = ""
) : IComputationStrategy {

    /**
     * Generates a pair of random matrices for testing matrix multiplication.
     * @return A pair of matrices (matrixA, matrixB).
     */
    override fun generateInput(): Pair<Any, Any> {
        // Generate random matrices for testing
        val matrixA = generateOptimizedMatrix(matrixSize, matrixSize)
        val matrixB = generateOptimizedMatrix(matrixSize, matrixSize)
        return Pair(matrixA, matrixB)
    }

    /**
     * Builds a task request for matrix multiplication.
     * @param input1 The first matrix (List<List<Double>>).
     * @param input2 The second matrix (List<List<Double>>).
     * @return An AssignTaskRequest protobuf message.
     */
    override fun buildRequest(input1: Any, input2: Any): AssignTaskRequest {
        require(input1 is List<*> && input2 is List<*>) { "Inputs must be lists of lists of doubles" }
        val matrixA = input1 as List<List<Double>>
        val matrixB = input2 as List<List<Double>>

        val taskRequest = TaskRequest.newBuilder()
            .addAllRowsA(matrixA.map { row ->
                Row.newBuilder()
                    .addAllValues(row)
                    .build()
            })
            .addAllRowsB(matrixB.map { row ->
                Row.newBuilder()
                    .addAllValues(row)
                    .build()
            })
            .setStartRow(0)
            .setNumRows(matrixSize)
            .build()

        return AssignTaskRequest.newBuilder()
            .setTaskRequest(taskRequest)
            .build()
    }

    /**
     * Computes the result for a given matrix multiplication task.
     * @param request The AssignTaskRequest protobuf message containing the task details.
     * @return An AssignTaskResponse protobuf message containing the computed result.
     */
    override fun computeTask(request: AssignTaskRequest): AssignTaskResponse {
        // Convert proto rows to matrix
        val matrixA = request.taskRequest.rowsAList.map { it.valuesList }
        val matrixB = request.taskRequest.rowsBList.map { it.valuesList }

        // Compute matrix multiplication for the assigned rows
        val result = multiplyMatrices(matrixA, matrixB)

        // Convert result back to proto format
        return AssignTaskResponse.newBuilder()
            .addAllResult(result.map { row ->
                Row.newBuilder()
                    .addAllValues(row)
                    .build()
            })
            .putFriendlyNames(workerAddress, workerName)
            .build()
    }

    /**
     * Logs the input matrices for debugging purposes.
     * @param input1 The first matrix (List<List<Double>>).
     * @param input2 The second matrix (List<List<Double>>).
     * @param logs A list to which log messages will be added.
     */
    override fun logInput(input1: Any, input2: Any, logs: SnapshotStateList<String>) {
        require(input1 is List<*> && input2 is List<*>) { "Inputs must be lists of lists of doubles" }
        val matrixA = input1 as List<List<Double>>
        val matrixB = input2 as List<List<Double>>

        if (matrixA.isEmpty() || matrixB.isEmpty() || matrixA[0].isEmpty() || matrixB[0].isEmpty()) {
            logs.add("Matrix A or B is empty. Invalid matrix size.")
            return
        }
        logs.add("Matrix A dimensions: ${matrixA.size}x${matrixA[0].size}")
        logs.add("Matrix B dimensions: ${matrixB.size}x${matrixB[0].size}")
    }

    /**
     * Logs the output result of a matrix multiplication task.
     * @param response The AssignTaskResponse protobuf message containing the result.
     * @param logs A list to which log messages will be added.
     */
    override fun logOutput(response: AssignTaskResponse, logs: SnapshotStateList<String>) {
        if (response.resultList.isEmpty()) {
            logs.add("Client: Received empty result matrix. Check input size.")
            return
        }
        logs.add("Client: Received result matrix:")
        response.resultList.forEachIndexed { i, row ->
            val formattedRow = row.valuesList.joinToString(", ") { "%.2f".format(it) }
            logs.add("Row $i: [$formattedRow]")
        }
        logs.add("Matrix multiplication completed. Result matrix dimensions: ${response.resultCount}x${response.getResult(0).valuesCount}")
    }

    /**
     * Gets the name of the worker.
     * @return The name of the worker.
     */
    override fun getWorkerName(): String = workerName

    /**
     * Multiplies two matrices.
     * @param a The first matrix (List<List<Double>>).
     * @param b The second matrix (List<List<Double>>).
     * @return The product matrix (List<List<Double>>).
     */
    private fun multiplyMatrices(a: List<List<Double>>, b: List<List<Double>>): List<List<Double>> {
        if (a.isEmpty() || b.isEmpty() || b[0].isEmpty()) return emptyList()
        val n = a.size
        val m = b[0].size
        val p = b.size

        return List(n) { i ->
            List(m) { j ->
                (0 until p).sumOf { k ->
                    a[i][k] * b[k][j]
                }
            }
        }
    }

    /**
     * Generates an optimized matrix for testing purposes.
     * @param rows The number of rows.
     * @param cols The number of columns.
     * @return A List<List<Double>> matrix.
     */
    private fun generateOptimizedMatrix(rows: Int, cols: Int): List<List<Double>> {
        return List(rows) { i ->
            List(cols) { j ->
                when {
                    i == j -> 1.0
                    (i + j) % 2 == 0 -> 0.0
                    else -> round(Random.nextDouble(-5.0, 5.0) * 100) / 100
                }
            }
        }
    }
} 