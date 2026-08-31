# CAUSA Project Status & Roadmap

> **Instructions**: This document serves as the single source of truth for the overall CAUSA project status across all three repositories. Update this file whenever a phase's status changes or milestone updates occur.
>
> **Last updated**: 2026-08-27

---

## Repositories Overview

The CAUSA system consists of three interconnected repositories:

1. **[Causa Frontend](https://github.com/causa-project/Causa-main)** (`Causa-main`): Standalone React + D3.js dashboard UI.
2. **[Causa Backend](https://github.com/causa-project/Causa-backend-main)** (`Causa-backend-main`): Spring Boot 3 telemetry ingestion, topology compilation, anomaly detection, and RCA engine.
3. **[Causa Test Services](https://github.com/causa-project/Causa-test-services-main)** (`Causa-test-services-main`): Simulated 4-microservice cluster with OTel instrumentation and chaos injection endpoints.

---

## Phase 1 - Frontend (Status: Done)

- **Status**: Completed
- **Details**: Built as a modernized, standalone React (v16.13) + D3.js (v5.16) visualization dashboard forked from `orca-ui`. Renders active incident alert lists (`/alerts`), dynamic service topology graphs with automated red fault-propagation lines (`/graph`), node metadata sidebar cards, and root cause trajectory sub-graphs (`/rca`).
- **Documentation**: The frontend API contract and component design are documented in the frontend's [`walkthrough.md`](file:///c:/Users/soham/OneDrive/Desktop/New%20folder/Causa-main/walkthrough.md).

---

## Phase 2 - Backend RCA Engine (Status: Done)

- **Status**: Completed
- **Details**: Implemented the Spring Boot 3 (Java 21) REST backend (`causa-backend`). Provides OpenTelemetry telemetry ingestion (`/v1/traces`, `/v1/metrics`, `/v1/logs`), dynamic topology compilation (`TopologyService`), heuristic anomaly detection (`AnomalyDetectionService`), and Breadth-First Search (BFS) distance-decay RCA trajectory scoring (`RcaService`).
- **Documentation**: Fully documented in [`BACKEND_DESIGN.md`](file:///c:/Users/soham/OneDrive/Desktop/New%20folder/Causa-backend-main/BACKEND_DESIGN.md).

---

## Phase 2.5 - Backend Optimization (Status: Done)

- **Status**: Completed
- **Details**: Completed major data layer, performance, and security optimizations, including `@Transactional` batch persistence (`saveAll()`), 15-minute time-bounded topology compilation with 5-second graph caching, 10-second `@Scheduled` background anomaly detection, persistent file-based H2 database (`jdbc:h2:file:./data/causadb`), Spring profile-gated H2 web console access (`application-dev.properties`), and pre-flight HTTP 400 payload validation with server-side SLF4J logging.
- **Documentation**: See the [Phase 2.5 - Backend Optimization](file:///c:/Users/soham/OneDrive/Desktop/New%20folder/Causa-backend-main/BACKEND_DESIGN.md#phase-25---backend-optimization) section in `BACKEND_DESIGN.md` for full implementation details.

---

## Phase 3 - Frontend/Backend Integration (Status: Done)

- **Status**: Completed
- **Details**: Full end-to-end integration verified live in the browser. Real backend telemetry data (active incident alerts and dynamic topology graph) renders correctly in the React UI with `REACT_APP_USE_MOCK=false`. Confirmed that alerts and graph elements correctly disappear once telemetry ages out of the backend's time-windowing rules (30-minute alert TTL, 15-minute topology window) and seamlessly reappear once fresh traffic is generated.

---

## Phase 4 - Plugin Feature (Status: In Progress)

### Phase 4, Step 1 - API Key Authentication (Status: Done)
Implemented DbProject entity, ProjectRepository, ApiKeyAuthFilter (protecting /v1/traces, /v1/metrics, /v1/logs, /v1/fix-suggestion via the X-Causa-Api-Key header), and AdminController (POST /v1/admin/projects, gated by a separate master admin key) for issuing per-project API keys. Verified working end-to-end.

### Phase 4, Step 2 - Java Instrumentation Plugin (Status: Done)
Generalized the OTel instrumentation originally in Causa-test-services into a standalone, reusable Spring Boot auto-configuration library: [Causa-plugin-java](https://github.com/soham-kolhe/Causa-plugin-java). Consuming projects add it as a Maven dependency and configure causa.backend.url, causa.api-key, and causa.service-name. Causa-test-services was migrated to consume this plugin (replacing its own hardcoded OTel classes) and verified working end-to-end, including full chaos-scenario testing with real trace ingestion, topology graph generation, and alert detection.

### Phase 4, Step 3 - Fix Suggestion Service (Status: Done)
FixSuggestionController (POST /v1/fix-suggestion), FixSuggestionProvider interface, and AzureFoundryFixSuggestionProvider are implemented per the design in PHASE4_DESIGN.md. Verified live against a real Azure AI Foundry `gpt-5-mini` deployment, returning a coherent, well-structured diagnosis and remediation plan for a real `checkout-api` high-error-rate alert.

### Phase 4, Step 4 - Self-Service Project Onboarding (Status: Planned, Frontend Team)
Not yet started. Scope, for the frontend team to pick up:
- **4a: Self-service project creation page.** A UI page wrapping the existing POST /v1/admin/projects endpoint, so a user can create a project and get an API key through the website instead of a manual admin API call. Should display the generated key along with copy-paste Maven dependency and application.properties snippets, plus a live "waiting for data..." indicator that polls /v1/graph until the user's first trace arrives.
- **4b: Publish the plugin for public consumption** (backend/infra work, not frontend) - publish causa-plugin-java to GitHub Packages so it can be added as a dependency from any machine, without requiring a local `mvn install` from a cloned copy of the source. This is a prerequisite for 4a to be useful to anyone outside the core team.
- **4c: Config-snippet generator** (future, lower priority) - a tool where a user pastes their existing pom.xml and receives it back with the Causa plugin dependency merged in, removing the need to manually edit the file. Deliberately scoped smaller than a full "upload your whole project" auto-patching system, which was considered and set aside as too large/fragile for this stage (parsing arbitrary Maven/Gradle project structures) - and full hosted execution of user-uploaded code was ruled out entirely due to the security implications of running arbitrary third-party code.
