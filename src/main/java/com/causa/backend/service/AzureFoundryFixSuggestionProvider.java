package com.causa.backend.service;

import com.causa.backend.model.DbLog;
import com.causa.backend.model.FixSuggestionRequest;
import com.causa.backend.model.FixSuggestionResponse;
import com.causa.backend.repository.LogRepository;
import com.causa.backend.service.AnomalyDetectionService.ServiceAlert;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AzureFoundryFixSuggestionProvider implements FixSuggestionProvider {

    private static final Logger logger = LoggerFactory.getLogger(AzureFoundryFixSuggestionProvider.class);

    @Value("${causa.llm.azure.endpoint:}")
    private String endpoint;

    @Value("${causa.llm.azure.deployment-name:gpt-4o}")
    private String deploymentName;

    @Value("${causa.llm.azure.api-key:${AZURE_AI_FOUNDRY_API_KEY:}}")
    private String apiKey;

    @Value("${causa.llm.azure.api-version:2024-02-15-preview}")
    private String apiVersion;

    @Autowired
    private AnomalyDetectionService anomalyDetectionService;

    @Autowired
    private LogRepository logRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public FixSuggestionResponse generateFix(FixSuggestionRequest request) {
        String symptomAlertId = request != null ? request.getSymptomAlertId() : "unknown-alert";

        try {
            // 1. Fetch matching ServiceAlert
            ServiceAlert alert = anomalyDetectionService.getAlertFromCache(symptomAlertId);
            if (alert == null) {
                List<ServiceAlert> activeAlerts = anomalyDetectionService.detectAnomalies();
                if (activeAlerts != null) {
                    for (ServiceAlert a : activeAlerts) {
                        if (a.getId().equals(symptomAlertId)) {
                            alert = a;
                            break;
                        }
                    }
                }
            }

            // Determine service name
            String serviceName = "unknown-service";
            if (alert != null && alert.getSource() != null) {
                Object propsObj = alert.getSource().get("properties");
                if (propsObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> props = (Map<String, Object>) propsObj;
                    if (props.containsKey("name")) {
                        serviceName = String.valueOf(props.get("name")).replace("-pod", "");
                    }
                }
            }
            if ("unknown-service".equals(serviceName) && symptomAlertId != null && symptomAlertId.startsWith("alert-")) {
                serviceName = symptomAlertId.replace("alert-", "")
                        .replace("-high-error-rate", "")
                        .replace("-latency-high", "");
            }

            // Fetch last 5 minutes of ERROR logs for affected service (limit 10 most recent)
            long endMs = System.currentTimeMillis();
            long startMs = endMs - (5 * 60 * 1000L);
            List<DbLog> logs = logRepository.findByServiceNameAndTimestampMsBetween(serviceName, startMs, endMs);
            List<DbLog> errorLogs = logs.stream()
                    .filter(l -> l.getSeverity() != null && "ERROR".equalsIgnoreCase(l.getSeverity()))
                    .sorted(Comparator.comparing(DbLog::getTimestampMs, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(10)
                    .collect(Collectors.toList());

            // Build prompt
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("You are an expert site reliability engineer and software architect. Provide a root cause diagnosis and recommended remediation for the following incident.\n\n");
            promptBuilder.append("INCIDENT ALERT DETAILS:\n");
            promptBuilder.append("- Alert ID: ").append(symptomAlertId).append("\n");
            if (alert != null) {
                promptBuilder.append("- Alert Name: ").append(alert.getName()).append("\n");
                promptBuilder.append("- Severity: ").append(alert.getSeverity()).append("\n");
                promptBuilder.append("- Message: ").append(alert.getMessage()).append("\n");
                promptBuilder.append("- Origin: ").append(alert.getOrigin()).append("\n");
            } else {
                promptBuilder.append("- Severity: HIGH\n");
                promptBuilder.append("- Message: Symptom alert triggered on service ").append(serviceName).append("\n");
            }

            if (request != null && request.getTrajectoryScore() != null) {
                promptBuilder.append("- Trajectory Score: ").append(request.getTrajectoryScore()).append("\n");
            }

            promptBuilder.append("\nRCA PROPAGATION TRAJECTORY (CALLER -> CALLEE):\n");
            if (request != null && request.getTrajectory() != null) {
                if (request.getTrajectory().getNodes() != null && !request.getTrajectory().getNodes().isEmpty()) {
                    promptBuilder.append("Nodes:\n");
                    for (Map<String, Object> node : request.getTrajectory().getNodes()) {
                        promptBuilder.append("  * ").append(node.get("id")).append(" (kind: ").append(node.get("kind")).append(")\n");
                    }
                }
                if (request.getTrajectory().getLinks() != null && !request.getTrajectory().getLinks().isEmpty()) {
                    promptBuilder.append("Links (propagation flow):\n");
                    for (Map<String, Object> link : request.getTrajectory().getLinks()) {
                        promptBuilder.append("  * ").append(link.get("source")).append(" -> ").append(link.get("target")).append("\n");
                    }
                }
            }

            promptBuilder.append("\nRECENT ERROR LOGS (LAST 5 MINUTES):\n");
            if (errorLogs.isEmpty()) {
                promptBuilder.append("No ERROR-level logs recorded in the last 5 minutes.\n");
            } else {
                for (DbLog log : errorLogs) {
                    promptBuilder.append("  - [").append(log.getTimestampMs()).append("] [").append(log.getSeverity()).append("] ").append(log.getBody()).append("\n");
                }
            }

            promptBuilder.append("\nINSTRUCTIONS:\n");
            promptBuilder.append("Respond ONLY in raw JSON format matching this exact schema (no markdown formatting, no code fences ```, no preamble):\n");
            promptBuilder.append("{\n");
            promptBuilder.append("  \"summary\": \"Concise 1-2 sentence explanation of root cause and impact\",\n");
            promptBuilder.append("  \"suggestedFix\": \"Numbered step-by-step remediation instructions\",\n");
            promptBuilder.append("  \"confidence\": \"HIGH|MEDIUM|LOW\"\n");
            promptBuilder.append("}\n");

            if (endpoint == null || endpoint.trim().isEmpty() || apiKey == null || apiKey.trim().isEmpty()) {
                throw new IllegalStateException("Azure AI Foundry credentials not configured (causa.llm.azure.endpoint, causa.llm.azure.api-key).");
            }

            String cleanEndpoint = endpoint.trim().replaceAll("/+$", "");
            String cleanDeployment = deploymentName != null && !deploymentName.trim().isEmpty() ? deploymentName.trim() : "gpt-4o";
            String cleanApiVersion = apiVersion != null && !apiVersion.trim().isEmpty() ? apiVersion.trim() : "2024-02-15-preview";

            String requestUrl = cleanEndpoint + "/openai/deployments/" + cleanDeployment + "/chat/completions?api-version=" + cleanApiVersion;

            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", promptBuilder.toString());

            Map<String, Object> requestBodyMap = new HashMap<>();
            requestBodyMap.put("messages", Collections.singletonList(userMessage));
            requestBodyMap.put("max_completion_tokens", 2000);

            byte[] jsonPayload = objectMapper.writeValueAsBytes(requestBodyMap);

            logger.info("Azure request - URL: {}, deployment: {}, apiVersion: {}", requestUrl, cleanDeployment, cleanApiVersion);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .header("Content-Type", "application/json")
                    .header("api-key", apiKey.trim())
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Azure AI Foundry endpoint returned HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode rootNode = objectMapper.readTree(response.body());
            JsonNode choicesNode = rootNode.path("choices");
            if (!choicesNode.isArray() || choicesNode.isEmpty()) {
                throw new RuntimeException("No response choices returned by Azure AI Foundry.");
            }

            String rawContent = choicesNode.get(0).path("message").path("content").asText("");
            if (rawContent == null || rawContent.trim().isEmpty()) {
                throw new RuntimeException("Empty message content returned by Azure AI Foundry.");
            }

            String cleanJson = rawContent.trim();
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7);
            } else if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.substring(3);
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
            }
            cleanJson = cleanJson.trim();

            JsonNode parsedFix = objectMapper.readTree(cleanJson);
            String summary = parsedFix.path("summary").asText("No summary provided.");
            String suggestedFix = parsedFix.path("suggestedFix").asText("No suggested fix provided.");
            String confidence = parsedFix.path("confidence").asText("MEDIUM");

            return new FixSuggestionResponse(symptomAlertId, "AzureFoundry", summary, suggestedFix, confidence);

        } catch (Exception e) {
            logger.error("Failed to generate fix suggestion via Azure AI Foundry for alert '{}': ", symptomAlertId, e);
            String fallbackSummary = "AI diagnosis is temporarily unavailable: " + e.getMessage();
            String fallbackFix = "Please verify backend configuration (causa.llm.azure.endpoint, causa.llm.azure.api-key) and network connectivity to Azure AI Foundry.";
            return new FixSuggestionResponse(symptomAlertId, "AzureFoundry", fallbackSummary, fallbackFix, "LOW");
        }
    }
}
