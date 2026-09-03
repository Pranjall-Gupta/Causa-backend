package com.causa.backend.service;

import com.causa.backend.model.DbMetric;
import com.causa.backend.model.DbSpan;
import com.causa.backend.repository.MetricRepository;
import com.causa.backend.repository.SpanRepository;
import com.causa.backend.service.AnomalyDetectionService.ServiceAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class TopologyService {

    private static final Logger logger = LoggerFactory.getLogger(TopologyService.class);

    @Autowired
    private SpanRepository spanRepository;

    @Autowired
    private MetricRepository metricRepository;

    @Autowired
    private AnomalyDetectionService anomalyDetectionService;

    private volatile TopologyGraph cachedGraph;
    private volatile long lastCacheTimeMs = 0;
    private static final long CACHE_TTL_MS = 5000;

    public static class TopologyGraph {
        private List<Map<String, Object>> nodes = new ArrayList<>();
        private List<Map<String, Object>> links = new ArrayList<>();

        public TopologyGraph() {}

        public List<Map<String, Object>> getNodes() { return nodes; }
        public void setNodes(List<Map<String, Object>> nodes) { this.nodes = nodes; }

        public List<Map<String, Object>> getLinks() { return links; }
        public void setLinks(List<Map<String, Object>> links) { this.links = links; }
    }

    public synchronized TopologyGraph buildTopology() {
        return buildTopology(false);
    }

    public synchronized TopologyGraph buildTopology(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        if (!forceRefresh && cachedGraph != null && (now - lastCacheTimeMs) < CACHE_TTL_MS) {
            return cachedGraph;
        }

        TopologyGraph graph = new TopologyGraph();
        
        long nowNano = System.currentTimeMillis() * 1000000;
        long fifteenMinutesAgoNano = nowNano - (15L * 60 * 1000 * 1000000);

        // 1. Fetch distinct services from spans in the last 15 minutes
        List<String> services = spanRepository.findDistinctServiceNamesSince(fifteenMinutesAgoNano);
        if (services.isEmpty()) {
            this.cachedGraph = graph;
            this.lastCacheTimeMs = now;
            return graph;
        }

        // Add Cluster Node
        Map<String, Object> clusterNode = createNode("cluster-causa", "kubernetes", "cluster", "causa-cluster", "{}");
        graph.getNodes().add(clusterNode);

        // Add Worker Node
        Map<String, Object> workerNode = createNode("node-worker-1", "kubernetes", "node", "causa-worker-1", "{}");
        graph.getNodes().add(workerNode);

        // Fetch latest metrics to populate CPU/Memory properties on pods
        List<DbMetric> latestMetrics = metricRepository.findLatestMetrics();
        Map<String, Map<String, Double>> metricsByService = new HashMap<>();
        for (DbMetric m : latestMetrics) {
            metricsByService.computeIfAbsent(m.getServiceName(), k -> new HashMap<>())
                    .put(m.getMetricName(), m.getValue());
        }

        // 2. Generate Logical Service and Pod nodes
        int podIndex = 15;
        for (String serviceName : services) {
            String serviceId = "service-" + serviceName;
            String podId = "pod-" + serviceName;

            // Service Node
            graph.getNodes().add(createNode(serviceId, "kubernetes", "service", serviceName, "causa"));

            // Pod Node with properties
            Map<String, Object> pod = createNode(podId, "kubernetes", "pod", serviceName + "-pod", "causa");
            Map<String, Object> properties = (Map<String, Object>) pod.get("properties");
            properties.put("status", "Running");
            properties.put("ip", "10.244.1." + podIndex++);
            
            // Populate metrics if available
            Map<String, Double> serviceMetrics = metricsByService.get(serviceName);
            if (serviceMetrics != null) {
                if (serviceMetrics.containsKey("cpu_usage")) {
                    properties.put("cpu_usage", String.format("%.0f%%", serviceMetrics.get("cpu_usage")));
                } else {
                    properties.put("cpu_usage", "12%");
                }
                if (serviceMetrics.containsKey("memory_usage")) {
                    properties.put("memory_usage", String.format("%.0fMi", serviceMetrics.get("memory_usage")));
                } else {
                    properties.put("memory_usage", "256Mi");
                }
            } else {
                properties.put("cpu_usage", "10%");
                properties.put("memory_usage", "180Mi");
            }
            graph.getNodes().add(pod);

            // Containment links: node-worker-1 -> pod
            graph.getLinks().add(createLink("link-node-" + serviceName, "node-worker-1", podId, 1.0));

            // Mapping links: service -> pod
            graph.getLinks().add(createLink("link-service-" + serviceName, serviceId, podId, 1.0));
        }

        // 3. Establish communication links by tracking traces call patterns
        // We find spans from the last 15 minutes, map by traceId and spanId
        List<DbSpan> allSpans = spanRepository.findByStartTimeUnixNanoBetween(fifteenMinutesAgoNano, nowNano);
        Map<String, DbSpan> spansById = new HashMap<>();
        for (DbSpan span : allSpans) {
            spansById.put(span.getSpanId(), span);
        }

        // Keep track of call frequencies between services
        // Key: "callerService->calleeService", Value: count
        Map<String, Integer> serviceCalls = new HashMap<>();
        for (DbSpan childSpan : allSpans) {
            String parentId = childSpan.getParentSpanId();
            if (parentId != null && spansById.containsKey(parentId)) {
                DbSpan parentSpan = spansById.get(parentId);
                String parentService = parentSpan.getServiceName();
                String childService = childSpan.getServiceName();
                
                // If call crossed service boundary
                if (!parentService.equals(childService)) {
                    String edgeKey = parentService + "->" + childService;
                    serviceCalls.put(edgeKey, serviceCalls.getOrDefault(edgeKey, 0) + 1);
                }
            }
        }

        // Find max calls to normalize link strength
        int maxCalls = 1;
        for (int count : serviceCalls.values()) {
            maxCalls = Math.max(maxCalls, count);
        }

        for (Map.Entry<String, Integer> call : serviceCalls.entrySet()) {
            String[] parts = call.getKey().split("->");
            String caller = parts[0];
            String callee = parts[1];
            int count = call.getValue();
            
            // Normalize strength between 0.6 and 0.95
            double strength = 0.6 + (0.35 * ((double) count / maxCalls));

            String linkId = "link-" + caller + "-" + callee;
            String sourcePod = "pod-" + caller;
            String targetPod = "pod-" + callee;

            graph.getLinks().add(createLink(linkId, sourcePod, targetPod, strength));
        }

        // 4. Ingest Alerts and link them to their respective pods
        Set<String> existingNodeIds = new HashSet<>();
        for (Map<String, Object> node : graph.getNodes()) {
            existingNodeIds.add((String) node.get("id"));
        }

        List<ServiceAlert> activeAlerts = anomalyDetectionService.detectAnomalies();
        for (ServiceAlert alert : activeAlerts) {
            // Add Alert Node
            Map<String, Object> alertNode = new HashMap<>();
            alertNode.put("id", alert.getId());
            alertNode.put("origin", alert.getOrigin());
            alertNode.put("kind", "alert");
            
            Map<String, Object> alertProperties = new HashMap<>();
            alertProperties.put("name", alert.getName());
            alertProperties.put("message", alert.getMessage());
            alertProperties.put("severity", alert.getSeverity());
            alertProperties.put("namespace", "causa");
            alertNode.put("properties", alertProperties);
            graph.getNodes().add(alertNode);

            // Connect Alert -> Pod
            // Parse pod name from the alert source
            Map<String, Object> source = alert.getSource();
            if (source != null) {
                Map<String, String> props = (Map<String, String>) source.get("properties");
                if (props != null && props.containsKey("name")) {
                    String podName = props.get("name");
                    // Format: pod-checkout-api
                    String podId = "pod-" + podName.replace("-pod", "");
                    if (existingNodeIds.contains(podId)) {
                        graph.getLinks().add(createLink("link-alert-" + alert.getId(), alert.getId(), podId, 1.0));
                    } else {
                        logger.warn("Skipping alert link for alert '{}': expected podId '{}' not present in topology graph nodes", alert.getId(), podId);
                    }
                }
            }
        }

        this.cachedGraph = graph;
        this.lastCacheTimeMs = now;
        return graph;
    }

    private Map<String, Object> createNode(String id, String origin, String kind, String name, String namespace) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("origin", origin);
        node.put("kind", kind);
        
        Map<String, Object> properties = new HashMap<>();
        properties.put("name", name);
        properties.put("namespace", namespace);
        node.put("properties", properties);
        
        return node;
    }

    private Map<String, Object> createLink(String id, String source, String target, double strength) {
        Map<String, Object> link = new HashMap<>();
        link.put("id", id);
        link.put("source", source);
        link.put("target", target);
        
        Map<String, Object> properties = new HashMap<>();
        properties.put("strength", strength);
        link.put("properties", properties);
        
        return link;
    }
}
