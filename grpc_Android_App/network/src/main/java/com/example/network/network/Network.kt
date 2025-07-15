package com.example.network.network

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.network.matrix.*
import com.example.network.interfaces.IComputationStrategy
import com.example.network.interfaces.INetworkService
import com.example.network.worker.*
import com.example.network.ui.*
import com.example.protos.*
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

const val SERVICE_TYPE = "_task._tcp.local."
const val MAX_GRPC_MESSAGE_SIZE = 32 * 1024 * 1024
const val COORDINATOR_PORT = 50051
const val WORKER_PORT = 50052
const val LOCALHOST = "127.0.0.1"
const val OPERATION_TIMEOUT_MS = 10000L

/**
 * GrpcNetwork manages the distributed computation network layer, including gRPC server/client setup,
 * mDNS-based service discovery, worker registration, health checks, and task execution.
 * It enables the device to act as both a coordinator and a worker in a peer-to-peer computation cluster.
 * This class handles all networking, registration, and orchestration logic for distributed matrix computation.
 */
class GrpcNetwork(
    val logs: SnapshotStateList<String>,
    private val context: Context
) : WorkerEvents, INetworkService {
    private val discoveredCoordinators = mutableStateListOf<String>()
    private val workerHealthStatus = mutableMapOf<String, Boolean>()
    private val workerPerformanceMetrics = mutableMapOf<String, Long>()
    private val addressToFriendlyName = mutableMapOf<String, String>()
    private var computationCallback: (() -> Unit)? = null
    private var periodicRegistrationJob: Job? = null


    /**
     * Initializes the gRPC network component by starting both the coordinator and worker services.
     *
     * - The coordinator is started on the specified `coordinatorPort` and registers itself for discovery via mDNS.
     * - The worker is started on a separate `workerPort`, also registers via mDNS, and begins listening for available coordinator services.
     *
     * This setup allows the device to participate in a peer-to-peer distributed system by acting as both a task coordinator and a compute worker.
     */
    init {
        try {
            startCoordinatorWithMdns()
            startWorkerWithMdns()
        } catch (e: Exception) {
            logs.add("Error initializing gRPC servers: ${e.message}")
        }
    }

    /**
     * Initializes and starts a gRPC coordinator server with mDNS service discovery.
     *
     * This function performs the following:
     * - Retrieves the local IP address of the device.
     * - Creates a Coordinator instance that acts as both coordinator and worker.
     * - Starts the gRPC server on the specified port in a background thread.
     * - Registers the coordinator service using mDNS for peer discovery.
     */
    private fun startCoordinatorWithMdns() {
        val ip = getLocalIpAddress()
        val localAddress = "$ip:$COORDINATOR_PORT"
        val friendlyName = context.loadFriendlyName()

        val coordinator = createCoordinatorServer(localAddress, friendlyName)
        Executors.newSingleThreadExecutor().execute {
            try {
                val server = startCoordinatorGrpcServer(coordinator, logs)
                setupMdnsCoordinator(ip)
                server.awaitTermination()
            } catch (e: Exception) {
                logs.add("Coordinator gRPC error: ${e.message}")
            }
        }
    }


    /**
     * Creates a Coordinator instance configured to act as both coordinator and worker.
     *
     * This function initializes the coordinator with the provided local address and logs,
     * then registers the coordinator itself as a worker capable of handling tasks.
     *
     * @param localAddress The address (IP:port) where the server is running.
     * @return A configured coordinator instance ready to handle gRPC requests.
     */
    private fun createCoordinatorServer(
        localAddress: String,
        friendlyName: String
    ): Coordinator {
        val coordinator = Coordinator(logs, localAddress, friendlyName)
        coordinator.addWorker(localAddress, friendlyName)
        logs.add("Coordinator: also registered self as worker $localAddress ($friendlyName)")
        return coordinator
    }


    /**
     * Starts a gRPC server instance on the specified port using the provided coordinator implementation.
     *
     * Configures the server to accept large messages (up to 32MB) and binds it to the given port
     * with insecure (plaintext) credentials. Logs the server startup event.
     *
     * @param coordinatorImpl The coordinator implementation that handles service logic.
     * @param logs A mutable list used to capture log messages for diagnostics or UI.
     * @return The started gRPC Server instance.
     */
    private fun startCoordinatorGrpcServer(
        coordinatorImpl: Coordinator,
        logs: MutableList<String>
    ): io.grpc.Server {
        val server = io.grpc.Grpc
            .newServerBuilderForPort(COORDINATOR_PORT, io.grpc.InsecureServerCredentials.create())
            .addService(coordinatorImpl)
            .maxInboundMessageSize(MAX_GRPC_MESSAGE_SIZE)
            .build()
            .start()

        logs.add("Coordinator gRPC started on port $COORDINATOR_PORT")
        return server
    }

    /**
     * Registers the coordinator as a discoverable mDNS service on the local network.
     *
     * Uses JmDNS to broadcast the coordinator's availability using the _task._tcp.local. service type,
     * allowing workers or clients to discover it dynamically.
     *
     * @param ip The local IP address of the coordinator.
     * @return The active JmDNS instance managing the mDNS registration.
     */
    private fun setupMdnsCoordinator(ip: String): JmDNS {
        val inetAddress = InetAddress.getByName(ip)
        val jmdns = JmDNS.create(inetAddress)
        val ipAddress = getLocalIpAddress()
        val serviceName = "Coordinator-$ipAddress"
        val serviceInfo = ServiceInfo.create(
            SERVICE_TYPE,
            serviceName,
            COORDINATOR_PORT,
            "Coordinator node"
        )
        jmdns.registerService(serviceInfo)
        logs.add("Coordinator mDNS service registered: ${serviceInfo.name}")
        return jmdns
    }

    /**
     * Starts a gRPC worker server and advertises the worker service using mDNS for discovery.
     *
     * This function performs the following:
     * 1. Obtains the local device IP address.
     * 2. Initializes mDNS and registers the worker service.
     * 3. Starts the gRPC server to handle incoming computation tasks.
     * 4. Listens for coordinator services on the network and manages registrations.
     * 5. Runs the entire process asynchronously on a background thread.
     *
     */
    private fun startWorkerWithMdns() {
        val currentDeviceAddress = getLocalIpAddress()

        Executors.newSingleThreadExecutor().execute {
            try {
                val ip = InetAddress.getByName(currentDeviceAddress)
                val jmdns = JmDNS.create(ip)

                registerWorkerService(jmdns)
                val server = startWorkerGrpcServer()
                listenForCoordinatorServices(
                    jmdns,
                    currentDeviceAddress
                )

                // Start periodic registration after worker server is up
                startPeriodicRegistration()
                server.awaitTermination()
            } catch (e: Exception) {
                logs.add("Worker gRPC error: ${e.message}")
            }
        }
    }

    /**
     * Registers the worker node as an mDNS service to allow dynamic discovery by coordinators.
     *
     * Broadcasts the worker service using the predefined service type with a unique name based on
     * the worker's port and logs the registration event.
     *
     * @param jmdns The JmDNS instance used to manage multicast DNS registrations.
     */
    private fun registerWorkerService(jmdns: JmDNS) {
        val ipAddress = getLocalIpAddress()
        val serviceName = "Worker-$ipAddress"
        val serviceInfo = ServiceInfo.create(
            SERVICE_TYPE,
            serviceName,   // Unique service name
            WORKER_PORT,                   // Port the gRPC server will listen on
            "Worker node"            // Optional description
        )
        jmdns.registerService(serviceInfo)
        logs.add("Worker mDNS service registered: ${serviceInfo.name}")
    }

    /**
     * Starts the worker's gRPC server to handle computation tasks from coordinators.
     *
     * Configures the server with insecure credentials and allows large inbound message sizes
     * to accommodate task data. Registers the Worker service implementation.
     *
     * @return The started gRPC Server instance.
     */
    private fun startWorkerGrpcServer(): io.grpc.Server {
        val workerName = context.loadFriendlyName()
        val workerAddress = "${getLocalIpAddress()}:$WORKER_PORT"
        val strategy = MatrixComputationStrategy(workerName, workerAddress = workerAddress) //---------- To be Modified
        val server = io.grpc.Grpc
            .newServerBuilderForPort(WORKER_PORT, io.grpc.InsecureServerCredentials.create())
            .addService(object : TaskServiceGrpc.TaskServiceImplBase() {
                override fun assignTask(
                    request: AssignTaskRequest,
                    responseObserver: StreamObserver<AssignTaskResponse>
                ) {
                    val response = strategy.computeTask(request)
                    responseObserver.onNext(response)
                    responseObserver.onCompleted()
                }
            })
            .maxInboundMessageSize(MAX_GRPC_MESSAGE_SIZE)
            .build()
            .start()

        // Start health check service
        startHealthCheckService()

        logs.add("Worker gRPC started on port $WORKER_PORT")
        return server
    }

    /**
     * Starts a background coroutine to periodically check the health of all known coordinators.
     * Adds random jitter to avoid thundering herd problems.
     */
    private fun startHealthCheckService() {
        // Create a coroutine scope that will be cancelled when the parent scope is cancelled
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Add initial random delay between 0-2 seconds to prevent thundering herd
                delay((0..2000).random().toLong())
                
                while (true) {
                    try {
                        checkWorkersHealth()
                    } catch (e: Exception) {
                        // Ignore health check errors to prevent log spam
                    }
                    // Check every 5 seconds with small random jitter (±500ms)
                    delay(5000L + (-500..500).random().toLong())
                }
            } catch (e: Exception) {
                logs.add("Health check service error: ${e.message}")
            }
        }
    }

    /**
     * Checks the health of all discovered coordinators and updates their status.
     * Posts UI events if a coordinator goes online or offline.
     */
    private fun checkWorkersHealth() {
        discoveredCoordinators.forEach { coordinatorAddress ->
            try {
                // Parse the address into host and port
                val parts = coordinatorAddress.split(":")
                if (parts.size != 2) {
                    logs.add("Invalid coordinator address format: $coordinatorAddress")
                    return@forEach
                }

                val host = parts[0]
                val port = parts[1].toIntOrNull()
                if (port == null) {
                    logs.add("Invalid port number in address: $coordinatorAddress")
                    return@forEach
                }

                // Create a channel with a short timeout
                val channel = ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .build()

                try {
                    val stub = TaskServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(2, TimeUnit.SECONDS) // Short timeout for health check

                    // Just send an empty request to check if the coordinator is alive
                    val request = RegisterWorkerRequest.newBuilder()
                        .setWorkerAddress("")
                        .setFriendlyName("")
                        .build()
                    
                    val response = stub.registerWorker(request)
                    val friendlyName = response.friendlyName
                    
                    // If we get here, the coordinator is alive
                    if (workerHealthStatus[coordinatorAddress] != true) {
                        workerHealthStatus[coordinatorAddress] = true
                        DeviceEventBus.post(
                            UiEventDeviceStatus.WorkerStatusChanged(
                                humanName = friendlyName,
                                online = true
                            )
                        )
                    }
                } catch (e: Exception) {
                    if (workerHealthStatus[coordinatorAddress] != false) {
                        workerHealthStatus[coordinatorAddress] = false
                        // Find the friendly name from previous registration or use address as fallback
                        val friendlyName = findFriendlyNameForAddress(coordinatorAddress)
                        DeviceEventBus.post(
                            UiEventDeviceStatus.WorkerStatusChanged(
                                humanName = friendlyName,
                                online = false
                            )
                        )
                    }
                } finally {
                    // Always shut down the channel
                    channel.shutdown()
                    try {
                        channel.awaitTermination(1, TimeUnit.SECONDS)
                    } catch (e: InterruptedException) {
                        channel.shutdownNow()
                    }
                }
            } catch (e: Exception) {
                workerHealthStatus[coordinatorAddress] = false
            }
        }
    }

    /**
     * Finds the friendly name for a given coordinator address, or returns the address itself.
     * @param address The coordinator's address.
     * @return The friendly name or the address itself.
     */
    private fun findFriendlyNameForAddress(address: String): String {
        return addressToFriendlyName[address] ?: address
    }

    /**
     * Listens for coordinator services on the local network via mDNS and registers this worker with newly discovered coordinators.
     *
     * This function adds a service listener to the JmDNS instance that:
     * - Detects new coordinator services advertised on the network.
     * - Resolves their IP and port information.
     * - Filters out self and duplicate entries.
     * - Registers the worker with each discovered coordinator using gRPC.
     *
     * @param jmdns The JmDNS instance managing mDNS service discovery.
     * @param currentDeviceAddress The local device IP address as a string.
     */
    private fun listenForCoordinatorServices(
        jmdns: JmDNS,
        currentDeviceAddress: String,
    ) {
        jmdns.addServiceListener(SERVICE_TYPE, object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                jmdns.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                logs.add("Service removed: ${event.name}")
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                val port = info.port
                val coordinatorAddress = "$host:$port"
                val myAddress = "$currentDeviceAddress:$WORKER_PORT"

                if (info.name.startsWith("Coordinator") &&
                    coordinatorAddress != myAddress &&
                    host != currentDeviceAddress
                ) {
                    // Always try to register, even if already discovered
                    registerWithCoordinator(host, myAddress)
                    if (!discoveredCoordinators.contains(coordinatorAddress)) {
                        discoveredCoordinators.add(coordinatorAddress)
                    }
                }
            }
        })
    }


    /**
     * Registers this worker node with a specified coordinator over gRPC.
     *
     * Establishes a gRPC channel to the coordinator's address and sends a registration request
     * including the worker's address. Logs success or failure of the registration.
     *
     * @param host The IP address of the coordinator.
     * @param myAddress The address (IP:port) of this worker node.
     */
    fun registerWithCoordinator(
        host: String,
        myAddress: String,
    ) {
        val coordinatorAddress = "$host:$COORDINATOR_PORT"
        val channel = createGrpcChannel(host)

        try {
            val stub = TaskServiceGrpc.newBlockingStub(channel)
            val myFriendlyName = context.loadFriendlyName()
            val request = RegisterWorkerRequest.newBuilder()
                .setWorkerAddress(myAddress)
                .setFriendlyName(myFriendlyName)
                .build()

            val response = stub.registerWorker(request)
            addressToFriendlyName[coordinatorAddress] = response.friendlyName
            logs.add("Registered with coordinator at $coordinatorAddress (${response.friendlyName})")
            
            // Post only the status change event
            DeviceEventBus.post(
                UiEventDeviceStatus.WorkerStatusChanged(
                    humanName = response.friendlyName,
                    online = true
                )
            )
        } catch (e: Exception) {
//            logs.add("Failed to register with coordinator at $coordinatorAddress: ${e.message}")
        } finally {
            shutdownChannel(channel)
        }
    }

    /**
     * Creates an asynchronous stub for the Service gRPC client using the provided ManagedChannel.
     *
     * This stub allows making non-blocking RPC calls using callbacks.
     *
     * @param channel The ManagedChannel used to communicate with the gRPC server.
     * @return A ServiceStub instance to invoke RPC methods asynchronously.
     */
    private fun createServiceStub(channel: ManagedChannel): TaskServiceGrpc.TaskServiceStub =
        TaskServiceGrpc.newStub(channel)


    /**
     * Retrieves the first non-loopback IPv4 address assigned to this device.
     *
     * Iterates over all network interfaces and their addresses, returning
     * the first valid IPv4 address found. Falls back to "127.0.0.1" if none are found.
     *
     * @return Local IPv4 address as a string, or "127.0.0.1" if none available.
     */
    fun getLocalIpAddress(): String {
        // Iterate through all network interfaces (e.g., wlan0, eth0, lo) on "this" device
        NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
            // Iterate through all IP addresses bound to this interface
            nif.inetAddresses.toList().forEach { address ->
                // Filter for IPv4 addresses that are not loopback (not 127.0.0.1)
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    // Return the first matching IPv4 address
                    return address.hostAddress ?: LOCALHOST
                }
            }
        }
        // Fallback: return loopback address if no valid local IP was found
        return LOCALHOST
    }

    /** Read the user-friendly device name (or generate a fallback). */
    @SuppressLint("HardwareIds")
    fun Context.loadFriendlyName(): String {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        return prefs.getString("friendly_name", null)
            ?: ("Tablet-" + Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ANDROID_ID
            ).takeLast(4))          // e.g. "Tablet-42AF"
    }


    /**
     * Starts the client coroutine to send tasks to a gRPC server.
     *
     * Generates two random matrices of the specified size, logs them, sends them to the server
     * via gRPC, then logs the resulting data and worker contributions upon response.
     *
     * @param scope CoroutineScope to launch the client operation.
     * @param strategy The Computation Strategy dependent on the context of the app.
     */
    fun startAsClient(scope: CoroutineScope, strategy: IComputationStrategy, onComputationFinished: () -> Unit) {
        // Ensure any previous computation is properly cleaned up
        computationCallback?.invoke()
        computationCallback = null

        computationCallback = onComputationFinished

        val safeFinish = {
            computationCallback?.invoke()
            computationCallback = null
        }

        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    // Only clear logs if we're not in the middle of a computation
                    if (computationCallback == null) {
                        logs.clear()
                    }
                    logs.add("Starting new computation...")
                }

                withTimeout(OPERATION_TIMEOUT_MS) {
                    val channel = createGrpcChannel(LOCALHOST)
                    try {
                        val stub = createServiceStub(channel)

                        val client = Worker(
                            stub = stub,
                            channel = channel,
                            scope = scope,
                            logs = logs,
                            onFinish = safeFinish,
                            strategy = strategy,
                            workerEvents = this@GrpcNetwork
                        )
                        client.start()
                    } catch (e: Exception) {
                        shutdownChannel(channel)
                        throw e
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    logs.add("Client error: ${e.message}")
                }
                safeFinish()
            }
        }
    }


    /**
     * Creates a gRPC ManagedChannel targeting the specified host and port with plaintext communication.
     *
     * @return Configured ManagedChannel for gRPC calls.
     */
    private fun createGrpcChannel(host: String): ManagedChannel {
        return ManagedChannelBuilder.forAddress(host, COORDINATOR_PORT)
            .usePlaintext()
            .build()
    }


    /**
     * Logs details about which workers computed which portions in the task.
     *
     * Iterates over the worker info map from the response, adding the worker ID and computed portion indices to logs.
     *
     * @param response The AssignTaskResponse protobuf containing worker contribution info.
     */
    override fun logWorkerContributions(response: AssignTaskResponse) {
        // First show the redistribution events from the coordinator
        val redistributionLogs = logs.filter { it.contains("Successfully redistributed portions") }
            .distinct() // Filter out duplicate messages
        if (redistributionLogs.isNotEmpty()) {
            logs.add("\nTask redistribution events:")
            redistributionLogs.forEach { logs.add(it) }
        }
        
        // Then show the final combined results
        logs.add("\nFinal computation results:")
        
        // Create a map to store all portions computed by each worker (by address)
        val workersByAddress = mutableMapOf<String, MutableSet<Int>>()
        
        // First, add all portions from the original assignments
        response.workerInfoMap.forEach { (workerId, indices) ->
            workersByAddress.getOrPut(workerId) { mutableSetOf() }.addAll(indices.valuesList)
        }
        
        // Create a map from friendly name to address for easier lookup
        val nameToAddress = response.friendlyNamesMap.entries.associate { (address, name) -> name to address }
        
        // Add any redistributed portions from the logs
        redistributionLogs.forEach { log ->
            val match = Regex("Successfully redistributed portions \\[(\\d+(?:, \\d+)*)] to (.+)$").find(log)
            if (match != null) {
                val (portionsStr, workerName) = match.destructured
                val portions = portionsStr.split(", ").map { it.toInt() }
                // Find the worker's address using their friendly name
                val workerAddress = nameToAddress.entries.find { it.key.trim() == workerName.trim() }?.value
                if (workerAddress != null) {
                    // Get existing set or create new one, then add the redistributed portions
                    val existingPortions = workersByAddress.getOrPut(workerAddress) { mutableSetOf() }
                    existingPortions.addAll(portions)
                }
            }
        }

        // Sort workers by their first portion for consistent display
        val sortedWorkers = workersByAddress.entries.sortedBy { (_, portions) ->
            portions.minOrNull() ?: Int.MAX_VALUE
        }

        // Display combined results for each worker
        sortedWorkers.forEach { (workerId, portions) ->
            val sortedPortions = portions.sorted()
            // Always use the friendly name from the response
            val workerName = response.friendlyNamesMap[workerId] ?: workerId
            val computationTime = workerPerformanceMetrics[workerId] ?: 0L

            // Get original portions for this worker
            val originalPortions = response.workerInfoMap[workerId]?.valuesList?.sorted() ?: emptyList()
            
            // Get redistributed portions for this worker
            val redistributedPortions = sortedPortions.filter { !originalPortions.contains(it) }.sorted()

            // Format the portions list for display
            val portionStr = if (redistributedPortions.isNotEmpty()) {
                // If there are redistributed portions, show both original and redistributed
                val originalStr = originalPortions.joinToString(", ")
                val redistributedStr = redistributedPortions.joinToString(", ")
                "Original portions: [$originalStr], Redistributed portions: [$redistributedStr]"
            } else {
                // If no redistributed portions, just show original ones
                sortedPortions.joinToString(", ")
            }
            
            logs.add("Worker '$workerName' computed portions: [$portionStr]")

            // Post a single completion event with all portions
            if (sortedPortions.isNotEmpty()) {
                WorkEventBus.post(
                    UiEventWorkStatus.TaskCompleted(
                        humanName = workerName,
                        portions = sortedPortions,
                        computationTime = computationTime
                    )
                )
            }
        }
    }


    /**
     * Gracefully shuts down the provided gRPC ManagedChannel.
     *
     * Attempts to shutdown immediately and waits up to 5 seconds for termination.
     *
     * @param channel The ManagedChannel to shut down.
     */
    private fun shutdownChannel(channel: ManagedChannel) {
        try {
            channel.shutdown()
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow()
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    logs.add("Channel did not terminate")
                }
            }
        } catch (e: InterruptedException) {
            channel.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    override fun onWorkerMetricsUpdated(workerId: String, computationTime: Long) {
        workerPerformanceMetrics[workerId] = computationTime
    }

    override fun shutdownWorkerChannel(channel: ManagedChannel) {
        shutdownChannel(channel)
    }
    override fun getAvailableWorkers(): List<Pair<String, String>> {
        val workers = discoveredCoordinators.mapNotNull { coordinatorAddress ->
            val workerAddress = coordinatorAddress.replace(":$COORDINATOR_PORT", ":$WORKER_PORT")
            val friendlyName = addressToFriendlyName[coordinatorAddress] // or use a separate map for worker addresses
            val health = workerHealthStatus[coordinatorAddress]
            logs.add("DEBUG: coordinator=$coordinatorAddress, worker=$workerAddress, name=$friendlyName, health=$health")
            if (friendlyName != null && health == true) {
                workerAddress to friendlyName
            } else null
        }.toMutableList()

        // Add self as worker
        val selfWorkerAddress = "${getLocalIpAddress()}:$WORKER_PORT"
        val selfFriendlyName = context.loadFriendlyName()
        if (workers.none { it.first == selfWorkerAddress }) {
            workers.add(selfWorkerAddress to selfFriendlyName)
        }
        return workers
    }

    override fun executeTask(workerAddress: String, request: AssignTaskRequest): AssignTaskResponse {
        val channel = createChannel(workerAddress)
        val stub = TaskServiceGrpc.newBlockingStub(channel)

        return try {
            // Create a task ID and requester ID
            val taskId = java.util.UUID.randomUUID().toString()
            val requesterId = context.loadFriendlyName()

            // Create the assign task request
            val assignRequest = AssignTaskRequest.newBuilder()
                .setTaskRequest(request.taskRequest)
                .setTaskId(taskId)
                .setRequesterId(requesterId)
                .build()

            // Execute the task
            val response = stub.assignTask(assignRequest)

            // Verify task status
            if (response.status != TaskStatus.COMPLETED) {
                throw Exception("Task failed with status: ${response.status}")
            }

            channel.shutdown()
            response
        } catch (e: StatusRuntimeException) {
            logs.add("Error executing task on worker ${workerAddress}: ${e.message}")
            channel.shutdown()
            throw e
        } catch (e: Exception) {
            logs.add("General error executing task on worker ${workerAddress}: ${e.message}")
            channel.shutdown()
            throw e
        }
    }

    /**
     * Creates a gRPC ManagedChannel for the given address.
     * @param address The address to connect to.
     * @return The ManagedChannel instance.
     */
    private fun createChannel(address: String): ManagedChannel {
        return ManagedChannelBuilder.forTarget(address)
            .usePlaintext()
            .build()
    }

    /**
     * Starts a coroutine that periodically attempts to register this worker with all known coordinators.
     * Ensures robust registration in dynamic network environments.
     */
    private fun startPeriodicRegistration() {
        periodicRegistrationJob?.cancel()
        periodicRegistrationJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val myAddress = "${getLocalIpAddress()}:$WORKER_PORT"
                    val coordinatorsCopy = discoveredCoordinators.toList()
                    for (coordinator in coordinatorsCopy) {
                        val host = coordinator.substringBefore(":")
                        registerWithCoordinator(host, myAddress)
                    }
                } catch (e: Exception) {
                    logs.add("Error during periodic registration: ${e.message}")
                }
                delay(10_000)
            }
        }
    }

    /**
     * Stops the periodic registration coroutine if it is running.
     */
    fun stopPeriodicRegistration() {
        periodicRegistrationJob?.cancel()
    }
}