# CAUSA Backend Design Documentation (Phase 2)

This documentation describes the architecture and algorithms implemented in the Spring Boot backend (`causa-backend`) to assist future coding agents and developers.

---

## 1. Data Ingestion Models & Schema

The backend utilizes an in-memory H2 database (`causadb`) for speed and zero-setup deployment. Three main models are mapped using Hibernate/JPA:

1. **`DbSpan` (Table: `spans`)**
   - Represents trace spans conforming to the OpenTelemetry schema.
   - Primary key is auto-incremented `Long id`.
   - Core columns: `traceId`, `spanId`, `parentSpanId`, `serviceName`, `name`, `kind`, `startTimeUnixNano`, `endTimeUnixNano`, `durationMs`, and `statusCode`.
   - An index is placed implicitly on `startTimeUnixNano` for rolling window queries.

2. **`DbLog` (Table: `logs`)**
   - Stores log payloads.
   - Core columns: `serviceName`, `severity`, `body`, and `timestampMs`.

3. **`DbMetric` (Table: `metrics`)**
   - Stores system resource usage metrics like `cpu_usage` or `memory_usage`.
   - Core columns: `serviceName`, `metricName`, `value`, and `timestampMs`.

---

## 2. Dynamic Call Graph Topology Building

The `TopologyService` dynamically builds the microservice call graph by inspecting traces stored in the H2 database:
1. **Nodes Discovery**:
   - Distinct service names are queried from spans via `SELECT DISTINCT s.serviceName FROM DbSpan s`.
   - For each service, we create a logical **Service Node** and a **Pod Node** (representing runtime instances).
   - Containment links (`node-worker-1` -> `pod`) and mapping links (`service` -> `pod`) are generated.
   - A single global `cluster` and `node` are modeled to complete the topology.
   - Latest metric values for `cpu_usage` and `memory_usage` are dynamically fetched from the database and bound to pod properties.

2. **Link Discovery (Edges)**:
   - Spans are loaded and indexed in-memory by `spanId`.
   - We scan for child spans that have a parent span in another service (where `parentSpan.serviceName != childSpan.serviceName`).
   - Each cross-service boundary crossing represents a call link (e.g. Service A $\rightarrow$ Service B).
   - Link `strength` is calculated by normalizing the call frequency:
     $$\text{Strength} = 0.6 + 0.35 \times \frac{\text{Calls}(A \rightarrow B)}{\text{MaxCalls}}$$

---

## 3. Heuristic Anomaly Detection (Alerts Generator)

The `AnomalyDetectionService` aggregates spans dynamically over a **5-minute rolling window**:
1. **Latency Threshold**:
   - If a service's average span duration exceeds **1500ms** (1.5 seconds), a warning alert is triggered.
   - Name: `${ServiceName}LatencyHigh`.
   - Severity: `warning`.
2. **Error Rate Threshold**:
   - If a service's error span percentage (spans with `statusCode == "ERROR"` divided by total spans) exceeds **5.0%**, a critical alert is triggered.
   - Name: `${ServiceName}HighErrorRate`.
   - Severity: `critical`.

Alerts are returned at `/v1/alerts` and also represented as alert nodes connected to their respective pods in `/v1/graph`.

---

## 4. Root Cause Analysis (RCA) Scoring Algorithm

When a user triggers analysis for a symptom alert (e.g. `alert-checkout-high-error-rate`), the `RcaService` scores candidate causes:
1. **Breadth-First Search (BFS) Path Finding**:
   - Reconstructs a directed pod call graph.
   - Searches for the shortest path from the symptom pod (e.g. `pod-checkout-api`) to each candidate pod having an active alert (e.g. `pod-payment-service`).
2. **Heuristic Scoring Formula**:
   - If a path of length $d$ (hops) exists, a propagation score is calculated:
     $$\text{Score} = (\text{BaseScore} \times \alpha^{d}) + \beta$$
     - $\text{BaseScore} = 0.75$
     - $\alpha = 0.9$ (distance decay multiplier per hop)
     - $\beta = 0.15$ if the candidate alert is `critical`, else $0.05$ (severity modifier)
     - Score is capped at $0.99$.
3. **Trajectory Generation**:
   - Generates the path nodes array: `[SymptomAlert, SymptomPod, ...TransitPods..., CandidatePod, CandidateAlert]`.
   - Generates links connecting the nodes.
   - Sorts trajectories descending by score (highest likelihood of root cause first).

---

## 5. REST API Endpoints

### 1. Ingestion Endpoints (CORS-enabled)
* `POST /v1/traces`: Accepts OpenTelemetry HTTP/JSON payload containing resource/scope spans.
* `POST /v1/metrics`: Accepts List of `{ serviceName, metricName, value, timestampMs }`.
* `POST /v1/logs`: Accepts List of `{ serviceName, severity, body, timestampMs }`.

### 2. Query Endpoints
* `GET /v1/alerts`: Returns array of active `ServiceAlert` objects.
* `GET /v1/graph`: Returns `nodes` and `links` showing current topology and active alerts.
* `GET /v1/rca?source={alertId}`: Returns a ranked list of trajectories pointing to likely root causes.

---

## 6. How to Build and Run Locally

### Port Allocation
- The server runs on **port 5000** (matches frontend config `REACT_APP_BACKEND_HOST=http://localhost:5000`).

### Compilation Command
To build and package the jar file, run the Maven compiler command inside `causa-backend`:
```powershell
.\tools\apache-maven-3.9.6\bin\mvn.cmd clean package -DskipTests
```

### Execution Command
To start the Spring Boot web application, run:
```powershell
java -jar .\target\causa-backend-0.0.1-SNAPSHOT.jar
```

### Populating Telemetry Data
Once the server is running, populate the call graph and active alerts database using the PowerShell script:
```powershell
.\send_mock_spans.ps1
```

> [!CAUTION]
> **In-Memory Storage Warning**: Since the H2 database runs in-memory (`jdbc:h2:mem:causadb`), any termination or restart of the Spring Boot Java process will completely clear all database tables. You must re-run the `.\send_mock_spans.ps1` script each time the server starts to re-populate the graph, metrics, and incidents.
