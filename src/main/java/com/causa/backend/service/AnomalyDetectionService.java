package com.causa.backend.service;

import com.causa.backend.model.DbSpan;
import com.causa.backend.repository.SpanRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.springframework.scheduling.annotation.Scheduled;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;

@Service
public class AnomalyDetectionService {

    @Autowired
    private SpanRepository spanRepository;

    private final Map<String, ServiceAlert> alertCache = new java.util.concurrent.ConcurrentHashMap<>();

    public static class ServiceAlert {
        private String id;
        private String origin;
        private String name;
        private String message;
        private String severity;
        private Map<String, Object> source;

        @JsonProperty("created_at")
        private long createdAt;

        @JsonProperty("updated_at")
        private long updatedAt;

        public ServiceAlert() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getOrigin() { return origin; }
        public void setOrigin(String origin) { this.origin = origin; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }

        public Map<String, Object> getSource() { return source; }
        public void setSource(Map<String, Object> source) { this.source = source; }

        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

        public long getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    }

    @Scheduled(fixedRate = 10000)
    public void scanForAnomalies() {
        List<ServiceAlert> computedAlerts = new ArrayList<>();
        
        // Scan spans from the last 5 minutes
        long nowNano = System.currentTimeMillis() * 1000000;
        long fiveMinutesAgoNano = nowNano - (5L * 60 * 1000 * 1000000);
        
        List<DbSpan> recentSpans = spanRepository.findByStartTimeUnixNanoBetween(fiveMinutesAgoNano, nowNano);
        if (!recentSpans.isEmpty()) {
            // Group spans by service name
            Map<String, List<DbSpan>> spansByService = new HashMap<>();
            for (DbSpan span : recentSpans) {
                spansByService.computeIfAbsent(span.getServiceName(), k -> new ArrayList<>()).add(span);
            }

            for (Map.Entry<String, List<DbSpan>> entry : spansByService.entrySet()) {
                String serviceName = entry.getKey();
                List<DbSpan> spans = entry.getValue();

                int totalSpans = spans.size();
                int errorSpans = 0;
                double totalLatencyMs = 0;
                long latestSpanTime = 0;
                long earliestSpanTime = Long.MAX_VALUE;

                for (DbSpan span : spans) {
                    if ("ERROR".equalsIgnoreCase(span.getStatusCode())) {
                        errorSpans++;
                    }
                    totalLatencyMs += span.getDurationMs();
                    latestSpanTime = Math.max(latestSpanTime, span.getStartTimeUnixNano());
                    earliestSpanTime = Math.min(earliestSpanTime, span.getStartTimeUnixNano());
                }

                double errorRate = (double) errorSpans / totalSpans * 100.0;
                double avgLatencyMs = totalLatencyMs / totalSpans;

                long createdSec = earliestSpanTime / 1000000000;
                long updatedSec = latestSpanTime / 1000000000;
                
                String podName = serviceName + "-pod";

                // Threshold rules
                // 1. Error Rate Rule: Critical alert if error rate > 5.0%
                if (errorRate > 5.0) {
                    ServiceAlert alert = new ServiceAlert();
                    alert.setId("alert-" + serviceName + "-high-error-rate");
                    alert.setOrigin("prometheus");
                    alert.setName(camelCase(serviceName) + "HighErrorRate");
                    alert.setMessage(serviceName + " HTTP 5xx error rate is above 5% (current: " + String.format("%.1f", errorRate) + "%)");
                    alert.setSeverity("critical");
                    alert.setCreatedAt(createdSec);
                    alert.setUpdatedAt(updatedSec);
                    alert.setSource(createSourceProperties(podName));
                    computedAlerts.add(alert);
                }

                // 2. Latency Rule: Warning alert if latency > 1500ms
                if (avgLatencyMs > 1500.0) {
                    ServiceAlert alert = new ServiceAlert();
                    alert.setId("alert-" + serviceName + "-latency-high");
                    alert.setOrigin("prometheus");
                    alert.setName(camelCase(serviceName) + "LatencyHigh");
                    alert.setMessage(serviceName + " response time is above 1.5s (current: " + String.format("%.2f", avgLatencyMs / 1000.0) + "s)");
                    alert.setSeverity("warning");
                    alert.setCreatedAt(createdSec);
                    alert.setUpdatedAt(updatedSec);
                    alert.setSource(createSourceProperties(podName));
                    computedAlerts.add(alert);
                }
            }
        }

        // Merge computed alerts into the cache
        for (ServiceAlert alert : computedAlerts) {
            alertCache.put(alert.getId(), alert);
        }
    }

    public List<ServiceAlert> detectAnomalies() {
        // Evict expired alerts from cache (TTL: 30 minutes = 1800 seconds)
        long nowSec = System.currentTimeMillis() / 1000;
        alertCache.entrySet().removeIf(entry -> (nowSec - entry.getValue().getUpdatedAt()) > 1800);

        return new ArrayList<>(alertCache.values());
    }

    public ServiceAlert getAlertFromCache(String alertId) {
        return alertCache.get(alertId);
    }

    private Map<String, Object> createSourceProperties(String podName) {
        Map<String, Object> source = new HashMap<>();
        source.put("origin", "kubernetes");
        source.put("kind", "pod");
        
        Map<String, String> properties = new HashMap<>();
        properties.put("name", podName);
        properties.put("namespace", "causa");
        
        source.put("properties", properties);
        return source;
    }

    private String camelCase(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : text.toCharArray()) {
            if (c == '-' || c == '_') {
                capitalizeNext = true;
            } else {
                if (capitalizeNext) {
                    sb.append(Character.toUpperCase(c));
                    capitalizeNext = false;
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
