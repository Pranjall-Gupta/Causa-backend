# CAUSA Project Status & Roadmap

> **Instructions**: This document serves as the single source of truth for the overall CAUSA project status across all three repositories. Update this file whenever a phase's status changes or milestone updates occur.
>
> **Last updated**: 2026-08-10

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

## Phase 3 - Frontend/Backend Integration (Status: In Progress)

- **Status**: In Progress (Active Work)
- **Details**: Mock mode on the frontend is now toggleable via the `REACT_APP_USE_MOCK` environment variable in `.env` (when `true`, loads `src/mock.js` interceptors; when `false`, routes requests directly to the Spring Boot backend at `REACT_APP_BACKEND_HOST`). Active work is focused on end-to-end integration testing of live telemetry ingested from `Causa-test-services` flowing through `Causa-backend` to the React frontend UI.

---

## Phase 4 - Plugin Feature (Status: Planned, Not Started)

- **Status**: Planned (Not Yet Scoped or Started)
- **Details**: The intended scope for Phase 4 includes developing an open-source instrumentation plugin/SDK (generalizing the OpenTelemetry tracing, metrics, and context propagation patterns established in `Causa-test-services`) that users can attach to their own custom projects. Additionally, an LLM-driven fix-suggestion service will be built on top of the existing `RcaService` output to provide automated remediation recommendations for identified root cause trajectories. This phase has not yet been scoped or started.
