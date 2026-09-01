# CAUSA Backend (Phase 2)

Spring Boot backend for **CAUSA**, an architecture-aware failure diagnosis system for microservices. It ingests OpenTelemetry-compatible traces, logs, and metrics; dynamically builds the live service topology; detects anomalies heuristically; and scores likely root causes for incidents.

---

## Project Repositories

| Repo | What it is |
|---|---|
| **Causa-backend** (this repo) | Spring Boot backend — OTLP ingestion, dynamic topology, anomaly detection, heuristic RCA scoring |
| [Causa](https://github.com/Pranjall-Gupta/Causa) | React frontend — dashboards, topology graph, RCA views |
| [Causa-test-services](https://github.com/Pranjall-Gupta/Causa-test-services) | Sample microservices used to generate real trace/log/metric data for testing |
| [Causa-plugin-java](https://github.com/soham-kolhe/Causa-plugin-java) | Java plugin developers add to their own services to emit data to CAUSA |

---

## How It Works

### 1. Data Ingestion & Storage
Runs on an in-memory H2 database (`causadb`) for zero-setup deployment. Three models:
- **`DbSpan`** — OpenTelemetry-schema trace spans (`traceId`, `spanId`, `parentSpanId`, `serviceName`, timing, status)
- **`DbLog`** — log payloads (`serviceName`, `severity`, `body`, `timestampMs`)
- **`DbMetric`** — resource usage metrics like `cpu_usage`/`memory_usage`

### 2. Dynamic Topology Building
`TopologyService` builds the live call graph from stored traces:
- Discovers services from distinct `serviceName`s in spans, and creates a Service Node + Pod Node for each, plus a shared cluster/node layer
- Finds call links by scanning for parent/child spans that cross service boundaries
- Link strength is normalized by call frequency: `0.6 + 0.35 × (calls A→B / max calls)`
- Latest CPU/memory metrics are bound onto pod properties

### 3. Heuristic Anomaly Detection
`AnomalyDetectionService` aggregates spans over a **5-minute rolling window**:
- **Latency**: avg span duration > 1500ms → `warning` alert (`{Service}LatencyHigh`)
- **Error rate**: error spans > 5% of total → `critical` alert (`{Service}HighErrorRate`)

### 4. Root Cause Analysis (RCA) Scoring
`RcaService` scores candidate root causes for a given symptom alert:
- BFS finds the shortest path from the symptom pod to each pod with an active alert
- Score = `(0.75 × 0.9^d) + severity_modifier`, where `d` = hop distance and the modifier is `0.15` for critical candidates or `0.05` otherwise, capped at `0.99`
- Trajectories are returned sorted descending by score (most likely root cause first)

---

## REST API

### Ingestion (CORS-enabled)
| Method | Endpoint | Body |
|---|---|---|
| POST | `/v1/traces` | OpenTelemetry HTTP/JSON resource/scope spans |
| POST | `/v1/metrics` | `List<{ serviceName, metricName, value, timestampMs }>` |
| POST | `/v1/logs` | `List<{ serviceName, severity, body, timestampMs }>` |

### Query
| Method | Endpoint | Returns |
|---|---|---|
| GET | `/v1/alerts` | Array of active `ServiceAlert` objects |
| GET | `/v1/graph` | `nodes` and `links` for current topology + active alerts |
| GET | `/v1/rca?source={alertId}` | Ranked list of root-cause trajectories |

> The frontend also expects an optional `time_point` query param on `/v1/graph` and `/v1/rca` — confirm this is implemented, since it isn't listed above.

---

## Build & Run Locally

**Port**: runs on **5000** (matches frontend's `REACT_APP_BACKEND_HOST=http://localhost:5000`)

```powershell
# Build
.\tools\apache-maven-3.9.6\bin\mvn.cmd clean package -DskipTests

# Run
java -jar .\target\causa-backend-0.0.1-SNAPSHOT.jar

# Populate mock telemetry data (run after the server starts)
.\send_mock_spans.ps1
```

> [!CAUTION]
> **In-memory storage**: the H2 database (`jdbc:h2:mem:causadb`) is wiped on every restart. Re-run `send_mock_spans.ps1` each time the server starts to repopulate the graph, metrics, and incidents.
