# Phase 4 Architecture & Design Specification

> [!IMPORTANT]
> **Phase 4 - Planned, Not Yet Implemented**
> 
> This document specifies the architectural design for CAUSA Phase 4 features. Implementation will proceed feature-by-feature after this design document is reviewed and approved. No production code changes are included in this specification task.

---

## Overview

Phase 4 expands the CAUSA platform beyond telemetry visualization and heuristic Root Cause Analysis (RCA) by introducing:
1. **Java Instrumentation Plugin**: Zero-code telemetry collection starter for Spring Boot applications.
2. **Fix Suggestion Service**: On-demand AI-assisted root cause diagnosis and remedy generation via Azure AI Foundry.
3. **API Key Authentication**: Cross-cutting request authentication to secure telemetry ingestion and diagnostic endpoints.

---

## 1. Feature 1: Java Instrumentation Plugin

### 1.1 Goal & Value Proposition
Currently, microservices in `Causa-test-services` manually configure OpenTelemetry SDK beans and HTTP interceptors. The Java Instrumentation Plugin (`causa-spring-boot-starter`) allows developers to add a single Maven/Gradle dependency to any Spring Boot project to auto-capture traces, metrics, and logs and stream them to CAUSA's ingestion endpoints (`/v1/traces`, `/v1/metrics`, `/v1/logs`) without writing custom telemetry code.

### 1.2 Reference Architecture & Component Generalization
The plugin refactors and generalizes the manual instrumentation patterns currently implemented in `Causa-test-services`:

| Test Services Source File | Plugin Component | Responsibility |
| :--- | :--- | :--- |
| [`OTelConfig.java`](file:///c:/Users/soham/OneDrive/Desktop/Project/Causa-test-services/src/main/java/com/causa/testservices/otel/OTelConfig.java) | `CausaAutoConfiguration` & `CustomJsonSpanExporter` | Initializes OTel SDK, manages background batch queues, and posts JSON telemetry payloads to CAUSA backend. |
| [`OTelTraceFilter.java`](file:///c:/Users/soham/OneDrive/Desktop/Project/Causa-test-services/src/main/java/com/causa/testservices/otel/OTelTraceFilter.java) | `CausaTraceFilter` | Servlet filter extracting incoming W3C `traceparent` headers and creating server spans for incoming HTTP requests. |
| [`OTelRestTemplateInterceptor.java`](file:///c:/Users/soham/OneDrive/Desktop/Project/Causa-test-services/src/main/java/com/causa/testservices/otel/OTelRestTemplateInterceptor.java) | `CausaRestTemplateInterceptor` & `CausaWebClientCustomizer` | Client interceptor injecting outgoing W3C trace context into HTTP headers across service boundaries. |

### 1.3 Configuration Surface & Auto-Configuration
Consuming Spring Boot applications configure the plugin entirely via `application.properties` or environment variables:

```properties
# CAUSA Telemetry Starter Configuration
causa.backend.url=http://localhost:5000
causa.api.key=causa_proj_sec_9f8e7d6c5b4a
causa.service.name=${spring.application.name:unknown-service}
causa.enabled=true
```

The plugin utilizes Spring Boot 3 auto-configuration (`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` referencing `@AutoConfiguration` classes). When `causa.enabled=true` is set, all filters, interceptors, and span exporters register automatically into the Spring application context with zero boilerplate code.

### 1.4 Scope Boundaries & Distribution
- **MVP Scope**: Java and Spring Boot (v3.x / Java 17+) only.
- **Language Agnostic Endpoints (Future Roadmap)**: CAUSA's backend ingestion endpoints (`/v1/traces`, `/v1/metrics`, `/v1/logs`) receive standard OpenTelemetry-shaped JSON payloads. Multi-language SDK starters (Node.js, Python, Go) are out-of-scope for MVP but are purely additive and require zero backend re-architecture.
- **Distribution Non-Goal**: Publishing to Maven Central is out of scope for MVP. The starter will be packaged as a JAR and consumed locally (`mvn install`) or via GitHub Packages / Git submodules.

---

## 2. Feature 2: Fix Suggestion Service

### 2.1 Goal & Endpoint Contract
The Fix Suggestion Service introduces an on-demand REST endpoint (`POST /v1/fix-suggestion`) that accepts an incident alert or RCA trajectory context and returns an AI-generated diagnosis, actionable root cause analysis, and step-by-step remediation advice.

#### API Contract: `POST /v1/fix-suggestion`
- **Request Payload**:
  ```json
  {
    "symptomAlertId": "alert-payment-service-high-error-rate",
    "trajectoryScore": 0.95,
    "trajectory": {
      "nodes": [...],
      "links": [...]
    }
  }
  ```
- **Response Payload**:
  ```json
  {
    "alertId": "alert-payment-service-high-error-rate",
    "provider": "AzureFoundry",
    "summary": "Database connection pool exhaustion in payment-service leading to downstream cascading HTTP 500 errors.",
    "suggestedFix": "1. Increase HikariCP maximum-pool-size from 10 to 30 in payment-service application.properties.\n2. Apply circuit breaker pattern on order-service -> payment-service calls.",
    "confidence": "HIGH"
  }
  ```

### 2.2 Domain Schema & Provider Interface Architecture
The fix suggestion engine consumes existing backend domain models:
- `ServiceAlert` from [`AnomalyDetectionService.java`](file:///c:/Users/soham/OneDrive/Desktop/Project/Causa-backend/src/main/java/com/causa/backend/service/AnomalyDetectionService.java)
- `Trajectory` and `TopologyGraph` from [`RcaService.java`](file:///c:/Users/soham/OneDrive/Desktop/Project/Causa-backend/src/main/java/com/causa/backend/service/RcaService.java) and [`TopologyService.java`](file:///c:/Users/soham/OneDrive/Desktop/Project/Causa-backend/src/main/java/com/causa/backend/service/TopologyService.java)

To prevent vendor lock-in, the service decouples LLM inference behind a provider interface:

```java
public interface FixSuggestionProvider {
    FixSuggestionResponse generateFix(FixSuggestionRequest request);
}
```

The primary concrete implementation for Phase 4 is `AzureFoundryFixSuggestionProvider`, which connects to Azure AI Foundry's OpenAI-compatible Chat Completions endpoint (`/deployments/{model}/chat/completions`).

### 2.3 Architectural Decoupling from `/v1/rca`
The fix suggestion logic is intentionally implemented as an independent endpoint (`POST /v1/fix-suggestion`) rather than bundled directly into `POST /v1/rca`:
- **Latency Control**: `/v1/rca` is executed rapidly using BFS graph pathfinding (<10ms) and polled regularly by the frontend dashboard. Including multi-second LLM inference in `/v1/rca` would severely degrade dashboard responsiveness.
- **Cost Efficiency**: `/v1/fix-suggestion` is invoked strictly on-demand when an operator explicitly requests an AI diagnosis for a specific alert, avoiding unnecessary API token expenditure during routine polling.

### 2.4 Prompt Context & Future Enhancements
- **MVP Context**: The prompt sent to Azure AI Foundry includes alert metadata (name, severity, error message), graph trajectory path (caller $\rightarrow$ callee propagation sequence), and recent log error trace snippets.
- **Future Roadmap (Out-of-Scope for MVP)**: Repository-linked source code analysis (Git AST parsing, file diff generation) is reserved for future phases.

### 2.5 Configuration Surface
Externalized LLM parameters configured via environment variables or `application.properties`:

```properties
causa.llm.provider=azure-foundry
causa.llm.azure.endpoint=https://<your-foundry-resource>.openai.azure.com
causa.llm.azure.deployment-name=gpt-4o
causa.llm.azure.api-key=${AZURE_AI_FOUNDRY_API_KEY}
causa.llm.azure.api-version=2024-02-15-preview
```

---

## 3. Cross-cutting: API Key Authentication

### 3.1 Security Gap Analysis
Currently, CAUSA backend ingestion endpoints (`/v1/traces`, `/v1/metrics`, `/v1/logs`) accept unauthenticated HTTP requests. While suitable for initial test microservices in isolated local environments, unauthenticated ingestion is unsafe once external user applications consume the Java Instrumentation Plugin.

### 3.2 MVP Authentication Scheme
Phase 4 introduces per-project API key authentication enforced via an HTTP header:
- Header Name: `X-Causa-Api-Key`
- Protected Endpoints:
  - `POST /v1/traces`
  - `POST /v1/metrics`
  - `POST /v1/logs`
  - `POST /v1/fix-suggestion`

### 3.3 Persistence Model & Enforcement Filter
A minimal database entity and JPA repository store and validate project keys:

```java
@Entity
@Table(name = "projects")
public class DbProject {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @Column(unique = true, nullable = false)
    private String apiKey;
    private Boolean active;
    private Long createdAtMs;
    // Getters and setters...
}
```

A Spring `OncePerRequestFilter` (`ApiKeyAuthFilter`) intercepts incoming requests on protected endpoints:
1. Extracts `X-Causa-Api-Key` header value.
2. Validates existence and `active == true` status against `ProjectRepository`.
3. Returns `401 Unauthorized` for missing or invalid keys.
4. Passes valid requests downstream with project context bound to request attributes.

*Note: This architecture focuses strictly on gating ingestion per project without introducing a full multi-tenant user management / IAM system for MVP.*

---

## Implementation Sequence

Implementation will proceed feature-by-feature in the following order:
1. **Step 1**: API Key Authentication & `DbProject` persistence.
2. **Step 2**: Java Instrumentation Plugin (`causa-spring-boot-starter`).
3. **Step 3**: Fix Suggestion Service (`POST /v1/fix-suggestion` & `AzureFoundryFixSuggestionProvider`).
