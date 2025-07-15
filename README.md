# gRPC Android Demo: Distributed Matrix Computation

## Overview
This project is a prototype Android application demonstrating a peer-to-peer distributed computation system using gRPC, written in Kotlin. The app showcases how multiple Android devices can collaborate over a local network to perform distributed matrix multiplication, with each device acting as both a coordinator and a worker. The system leverages gRPC for communication and mDNS (multicast DNS) for service discovery, making it a robust example for distributed systems, networking, and Kotlin multiplatform development.

## Features
- **Distributed Matrix Multiplication:** Devices on the same network discover each other and share the workload of multiplying large matrices.
- **Peer-to-Peer Architecture:** Each device can act as both a coordinator (distributing tasks) and a worker (executing tasks).
- **gRPC Communication:** Uses gRPC for efficient, type-safe, and scalable RPC communication between devices.
- **Service Discovery:** Utilizes mDNS (via JmDNS) for automatic peer discovery without manual configuration.
- **Modern Android UI:** Built with Jetpack Compose for a responsive, modern user interface.
- **Real-Time Logs and Status:** View worker status, computation progress, and debug logs in real time.

## Architecture
```
[Android Device 1] <--gRPC/mDNS--> [Android Device 2] <--gRPC/mDNS--> [Android Device 3]
        |                                 |                                 |
   [Coordinator/Worker]            [Coordinator/Worker]              [Coordinator/Worker]
```
- **Modules:**
  - `app`: Main Android application and UI.
  - `network`: Distributed computation logic, gRPC networking, and service discovery.
  - `protos`: Protobuf definitions and generated gRPC code.

## Matrix Computation Flow
1. **Startup:** Each device starts both a gRPC server (for coordination) and a worker server, registering itself via mDNS.
2. **Discovery:** Devices discover each other automatically on the local network.
3. **Task Distribution:** When a computation is started, the coordinator splits the matrix multiplication task and assigns slices to available workers.
4. **Computation:** Workers compute their assigned matrix slices and return results via gRPC.
5. **Aggregation:** The coordinator collects results, aggregates them, and displays progress and logs in the UI.

## Getting Started
### Prerequisites
- Android Studio (Giraffe or newer recommended)
- Android device or emulator (API 29+)
- Java 11 or newer

### Building the Project
1. **Clone the repository:**
   ```sh
   git clone <repo-url>
   cd gRPC_Android_Demo/grpc_Android_App
   ```
2. **Open in Android Studio:**
   - Open the `grpc_Android_App` directory as a project.
3. **Build the project:**
   - The project uses the Gradle wrapper (`./gradlew`). All dependencies will be resolved automatically.
   - Protobuf and gRPC code will be generated during the build process.

### Running the App
- Deploy the app to two or more Android devices (or emulators) on the same Wi-Fi network.
- Launch the app on each device.
- Enter a matrix size (between 2 and 100) and start the computation.
- Watch as devices discover each other, distribute the workload, and collaboratively compute the matrix product.

### Permissions
The app requires the following permissions for networking and service discovery:
- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `ACCESS_WIFI_STATE`
- `CHANGE_WIFI_MULTICAST_STATE`

These are declared in `app/src/main/AndroidManifest.xml`.

## Project Structure
```
grpc_Android_App/
  ├── app/        # Main Android app (UI, entry point)
  ├── network/    # Distributed computation, gRPC, mDNS logic
  ├── protos/     # Protobuf definitions and generated code
  ├── gradle/     # Gradle wrapper and version catalogs
  └── ...
```

## Technologies Used
- **Kotlin** (Android, multiplatform)
- **gRPC** (Java/Kotlin)
- **Protocol Buffers** (Protobuf)
- **Jetpack Compose** (UI)
- **JmDNS** (mDNS service discovery)
- **Coroutines** (asynchronous programming)

## Customization & Extensibility
- The computation strategy is modular—extend `IComputationStrategy` to implement other distributed tasks.
- Protobuf definitions can be modified in `protos/src/main/proto/com/example/protos/task.proto`.

## Documentation
- See the included PDF: `Build gRPC System using Kotlin.pdf` for a detailed design and implementation guide.

## Acknowledgements
- Built as part of a graduation project to demonstrate distributed systems and peer-to-peer networking on Android.

---
*For questions or contributions, please open an issue or pull request.*
