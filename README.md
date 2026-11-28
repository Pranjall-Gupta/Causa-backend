# CAUSA Backend (Phase 2)

Spring Boot backend for **CAUSA**, an architecture-aware failure diagnosis system for microservices. It ingests OpenTelemetry-compatible traces, logs, and metrics; dynamically builds the live service topology; detects anomalies heuristically; and scores likely root causes for incidents.

> **Update (deployment):** the backend is deployed on Heroku with PostgreSQL (Supabase), secured with per-project API keys, and generates LLM-powered fix suggestions through Azure AI Foundry. An opt-in demo mode generates simulated traffic for public demos. See the sections below.

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

> **Update:** H2 is the local default. In production the database is **PostgreSQL**, configured through `SPRING_DATASOURCE_*` environment variables (see [Configuration](#configuration)), so data survives restarts. Projects and their API keys are stored in a `projects` table. If no datasource variables are set, the `prod` profile falls back to a local H2 file database, which is ephemeral on platforms like Heroku.

### 2. Dynamic Topology Building
`TopologyService` builds the live call graph from stored traces (spans from the **last 15 minutes**):
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

### 5. LLM Fix Suggestions
`POST /v1/fix-suggestion` builds a prompt from the alert, recent error logs, and the RCA trajectory, and calls an Azure AI Foundry chat deployment. The model must reply with JSON (`summary`, `suggestedFix`, `confidence`). The reply is normalized: `suggestedFix` is returned as numbered steps in one string, and `confidence` is one of `HIGH`, `MEDIUM`, `LOW`.

If the Azure call fails or the output cannot be parsed, a rule-based template is returned instead, labeled `provider: "Fallback"` with `confidence: "LOW"`. Check `provider` before treating a suggestion as LLM output.

### 6. Demo Mode (optional)
Setting `CAUSA_DEMO_ENABLED=true` starts `DemoTrafficGenerator`, which keeps the dashboard populated without any real services:
- Every minute it writes simulated traces (healthy browsing and purchases, plus failing checkouts where `payment-service` times out and errors) and CPU/memory metrics across `checkout-api`, `order-service`, `payment-service`, and `inventory-service`. The mix produces `PaymentServiceHighErrorRate`, `PaymentServiceLatencyHigh`, and `CheckoutApiHighErrorRate` alerts.
- On startup, if there are no spans from the last 2 minutes, it backfills the previous 14 minutes.
- Every 30 minutes it deletes spans and metrics older than the retention window (`causa.demo.retention-minutes`, default 120).

> **Warning:** while demo mode is on, **all** spans and metrics older than the retention window are deleted, and the generated data is not tied to any project. Do not enable it on a deployment that stores real telemetry. The flag is off by default.

---

## REST API

### Authentication

| Header | Used for |
|---|---|
| `X-Causa-Api-Key` | Ingestion endpoints (`/v1/traces`, `/v1/metrics`, `/v1/logs`) and `/v1/fix-suggestion`. A project API key (`causa_proj_...`). |
| `X-Causa-Admin-Key` | `POST /v1/admin/projects`. Must equal the `CAUSA_ADMIN_KEY` configured on the server. |

Query endpoints (`/v1/alerts`, `/v1/graph`, `/v1/rca`) require no key.

### Create a project
```
POST /v1/admin/projects
X-Causa-Admin-Key: <admin key>
Content-Type: application/json

{ "name": "my-project" }
```
Returns `201` with `{ "id": <number>, "name": "my-project", "apiKey": "causa_proj_<uuid>" }`. A missing or blank `name` returns `400`; a wrong or missing admin key returns `401`.

PowerShell example (the admin key is read without echoing it):
```powershell
$base = "https://<your-backend>/v1"
$sec = Read-Host "Admin key" -AsSecureString
$adminKey = [System.Net.NetworkCredential]::new("", $sec).Password
$r = Invoke-RestMethod -Method Post -Uri "$base/admin/projects" `
  -Headers @{ "X-Causa-Admin-Key" = $adminKey } `
  -ContentType "application/json" -Body '{"name":"my-project"}'
$r.apiKey   # store this somewhere safe
```

### Ingestion (CORS-enabled)
| Method | Endpoint | Body |
|---|---|---|
| POST | `/v1/traces` | OpenTelemetry HTTP/JSON resource/scope spans |
| POST | `/v1/metrics` | `List<{ serviceName, metricName, value, timestampMs }>` |
| POST | `/v1/logs` | `List<{ serviceName, severity, body, timestampMs }>` |

These require the `X-Causa-Api-Key` header.

### Query
| Method | Endpoint | Returns |
|---|---|---|
| GET | `/v1/alerts` | Array of active `ServiceAlert` objects |
| GET | `/v1/graph` | `nodes` and `links` for current topology + active alerts |
| GET | `/v1/rca?source={alertId}` | Ranked list of root-cause trajectories |

> The frontend also sends an optional `time_point` query param on `/v1/graph` and `/v1/rca`. It is accepted but currently ignored: results always reflect the present.

### Fix suggestion
```
POST /v1/fix-suggestion
Content-Type: application/json

{
  "symptomAlertId": "alert-payment-service-high-error-rate",
  "trajectoryScore": 0.8,
  "trajectory": { "nodes": [], "links": [] }
}
```
Only `symptomAlertId` is required (blank returns `400`). Response:
```json
{
  "alertId": "alert-payment-service-high-error-rate",
  "provider": "AzureFoundry",
  "summary": "...",
  "suggestedFix": "1. ...\n2. ...",
  "confidence": "MEDIUM"
}
```

---

## Configuration

Set these as environment variables (on Heroku: Config Vars). Never commit values.

| Variable | Purpose |
|---|---|
| `CAUSA_ADMIN_KEY` | Admin key for creating projects. **Always set this in production**: if unset, a well-known development default is used. |
| `SPRING_DATASOURCE_URL` | JDBC URL, for example `jdbc:postgresql://<host>:5432/postgres?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | Database user |
| `SPRING_DATASOURCE_PASSWORD` | Database password (keep it out of the URL) |
| `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | `org.postgresql.Driver` (optional, normally inferred from the URL) |
| `AZURE_AI_FOUNDRY_ENDPOINT` | Azure AI Foundry / Azure OpenAI resource endpoint |
| `AZURE_AI_FOUNDRY_API_KEY` | API key for that resource |
| `AZURE_AI_FOUNDRY_DEPLOYMENT_NAME` | Name of your chat model deployment; must match the deployment name in Azure |
| `AZURE_AI_FOUNDRY_API_VERSION` | Azure OpenAI API version |
| `CAUSA_DEMO_ENABLED` | `true` turns on demo mode (see above). Default off. |
| `CAUSA_DEMO_RETENTION_MINUTES` | Demo-mode retention window in minutes (property `causa.demo.retention-minutes`, default 120). |
| `PORT` | Provided by the platform (Heroku); defaults to 5000 locally. |

---

## Build & Run Locally

**Port**: runs on **5000** (matches frontend's `REACT_APP_BACKEND_HOST=http://localhost:5000`)

```powershell
# Build
.\tools\apache-maven-3.9.6\bin\mvn.cmd clean package -DskipTests

# Or, with the Maven wrapper:
.\mvnw.cmd clean package -DskipTests

# Run
java -jar .\target\causa-backend-0.0.1-SNAPSHOT.jar

# Populate mock telemetry data (run after the server starts)
.\send_mock_spans.ps1
```

The first Maven run downloads dependencies and can take a few minutes.

**Quickest way to get data locally:** start the server with demo mode on, and skip the script.
```powershell
$env:CAUSA_DEMO_ENABLED = "true"
java -jar .\target\causa-backend-0.0.1-SNAPSHOT.jar
```

**Using `send_mock_spans.ps1`:**
1. Set `$baseUrl` at the top of the script to end in `/v1`, with **no trailing slash**.
2. Ingestion needs a project API key. If your copy of the script does not already send `X-Causa-Api-Key`, set it for the session first:
   ```powershell
   $sec = Read-Host "API key" -AsSecureString
   $k = [System.Net.NetworkCredential]::new("", $sec).Password
   $PSDefaultParameterValues['Invoke-RestMethod:Headers'] = @{ "X-Causa-Api-Key" = $k }
   ```
3. Run it with `powershell -ExecutionPolicy Bypass -File .\send_mock_spans.ps1`. The script prints a success line even if requests fail, so watch the output for red errors.

> [!CAUTION]
> **In-memory storage**: the H2 database (`jdbc:h2:mem:causadb`) is wiped on every restart. Re-run `send_mock_spans.ps1` each time the server starts to repopulate the graph, metrics, and incidents.

> **Update:** this applies to the local H2 default. A deployment configured with PostgreSQL keeps its data across restarts. Topology uses the last 15 minutes of spans and alerts use the last 5, so a quiet deployment still looks empty unless new telemetry (or demo mode) keeps arriving.

---

## Deployment (Heroku + Supabase Postgres)

- **Heroku:** the `Procfile` runs the jar on `$PORT`, and the app runs with the `prod` Spring profile. Set the variables above in Config Vars. Automatic deploys can follow the `main` branch.
- **Postgres (Supabase):** use the **Session pooler** connection string (port 5432), which works over IPv4. Convert it to JDBC format and put the username and password in their own variables. Optionally cap the connection pool with `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5`.
- **Supabase RLS:** tables in the `public` schema are exposed through Supabase's REST API. Enable row level security on all of them (with no policies) so only the direct database connection can read them. Repeat this when new tables appear, because Hibernate creates them with `ddl-auto=update`.
- Rotate `CAUSA_ADMIN_KEY` if it is ever exposed.

---

## Known Limitations

- **Single-tenant data:** graph, alerts, and RCA are not filtered by project, so data from different projects is mixed.
- **Public read endpoints:** `/v1/graph`, `/v1/alerts`, and `/v1/rca` need no key.
- **Fix suggestions are not strictly key-gated:** `/v1/fix-suggestion` falls back to the most recent active project when `X-Causa-Api-Key` is missing, so anyone who can reach the URL can spend your LLM quota. Set a spend limit on your Azure resource.
- **Open CORS:** all origins are allowed.
- **Simulated infrastructure details:** cluster/node layers, pod status (`Running`), and pod IPs are generated, not read from Kubernetes. Pod CPU/memory fall back to placeholder values when no metrics exist.
- **Heuristic RCA:** scoring is based on graph distance and alert severity, not causal inference. LLM fix suggestions are unverified and can be wrong or generic.
- **Short data window:** with no recent telemetry, the graph and alerts are empty.
- **Fixed thresholds:** the 1500 ms latency threshold, 5% error rate, and the window sizes are hard-coded.
- **Demo mode data is simulated** and deletes old spans and metrics (see Demo Mode).
