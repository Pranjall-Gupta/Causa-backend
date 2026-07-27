package com.causa.backend.service;

import com.causa.backend.service.AnomalyDetectionService.ServiceAlert;
import com.causa.backend.service.TopologyService.TopologyGraph;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RcaService {

    @Autowired
    private TopologyService topologyService;

    @Autowired
    private AnomalyDetectionService anomalyDetectionService;

    public static class Trajectory {
        private double score;
        private List<Map<String, Object>> nodes = new ArrayList<>();
        private List<Map<String, Object>> links = new ArrayList<>();

        public Trajectory() {}

        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }

        public List<Map<String, Object>> getNodes() { return nodes; }
        public void setNodes(List<Map<String, Object>> nodes) { this.nodes = nodes; }

        public List<Map<String, Object>> getLinks() { return links; }
        public void setLinks(List<Map<String, Object>> links) { this.links = links; }
    }

    @SuppressWarnings("unchecked")
    public List<Trajectory> analyzeRootCauses(String symptomAlertId) {
        List<Trajectory> trajectories = new ArrayList<>();
        
        // 1. Build latest topology to search paths
        TopologyGraph topology = topologyService.buildTopology();
        List<ServiceAlert> activeAlerts = anomalyDetectionService.detectAnomalies();
        
        // Find the symptom alert details
        ServiceAlert symptomAlert = null;
        for (ServiceAlert alert : activeAlerts) {
            if (alert.getId().equals(symptomAlertId)) {
                symptomAlert = alert;
                break;
            }
        }

        if (symptomAlert == null) {
            symptomAlert = anomalyDetectionService.getAlertFromCache(symptomAlertId);
        }

        if (symptomAlert == null) {
            // Construct fallback alert DTO dynamically
            String serviceName = "unknown-service";
            if (symptomAlertId.startsWith("alert-")) {
                serviceName = symptomAlertId.replace("alert-", "")
                                             .replace("-high-error-rate", "")
                                             .replace("-latency-high", "");
            }
            symptomAlert = new ServiceAlert();
            symptomAlert.setId(symptomAlertId);
            symptomAlert.setOrigin("prometheus");
            symptomAlert.setName(camelCase(serviceName) + (symptomAlertId.contains("error") ? "HighErrorRate" : "LatencyHigh"));
            symptomAlert.setMessage(serviceName + " generated cascading symptom (" + symptomAlertId + ")");
            symptomAlert.setSeverity(symptomAlertId.contains("error") ? "critical" : "warning");
            
            Map<String, Object> srcProps = new HashMap<>();
            srcProps.put("origin", "kubernetes");
            srcProps.put("kind", "pod");
            Map<String, String> properties = new HashMap<>();
            properties.put("name", serviceName + "-pod");
            properties.put("namespace", "causa");
            srcProps.put("properties", properties);
            symptomAlert.setSource(srcProps);
        }

        // Determine symptom pod ID
        String symptomPodId = null;
        Map<String, Object> source = symptomAlert.getSource();
        if (source != null) {
            Map<String, String> props = (Map<String, String>) source.get("properties");
            if (props != null && props.containsKey("name")) {
                symptomPodId = "pod-" + props.get("name").replace("-pod", "");
            }
        }

        if (symptomPodId == null) {
            return trajectories;
        }

        // Build adjacency list for pod-to-pod links
        // We only care about links between pods (source starts with "pod-", target starts with "pod-")
        Map<String, List<Map<String, Object>>> adjList = new HashMap<>();
        Map<String, Map<String, Object>> linksById = new HashMap<>();
        for (Map<String, Object> link : topology.getLinks()) {
            String src = (String) link.get("source");
            String dst = (String) link.get("target");
            linksById.put(src + "->" + dst, link);
            if (src.startsWith("pod-") && dst.startsWith("pod-")) {
                adjList.computeIfAbsent(src, k -> new ArrayList<>()).add(link);
            }
        }

        // Map pod IDs to their node objects for quick lookup
        Map<String, Map<String, Object>> nodesById = new HashMap<>();
        for (Map<String, Object> node : topology.getNodes()) {
            nodesById.put((String) node.get("id"), node);
        }

        // Add dynamic representation of symptom alert if missing in graph nodes
        if (!nodesById.containsKey(symptomAlertId)) {
            Map<String, Object> node = new HashMap<>();
            node.put("id", symptomAlert.getId());
            node.put("origin", symptomAlert.getOrigin());
            node.put("kind", "alert");
            Map<String, Object> properties = new HashMap<>();
            properties.put("name", symptomAlert.getName());
            properties.put("message", symptomAlert.getMessage());
            properties.put("severity", symptomAlert.getSeverity());
            properties.put("namespace", "causa");
            node.put("properties", properties);
            nodesById.put(symptomAlertId, node);
        }

        // Ensure all candidate alerts are also mapped in nodesById
        for (ServiceAlert candidateAlert : activeAlerts) {
            if (!nodesById.containsKey(candidateAlert.getId())) {
                Map<String, Object> node = new HashMap<>();
                node.put("id", candidateAlert.getId());
                node.put("origin", candidateAlert.getOrigin());
                node.put("kind", "alert");
                Map<String, Object> properties = new HashMap<>();
                properties.put("name", candidateAlert.getName());
                properties.put("message", candidateAlert.getMessage());
                properties.put("severity", candidateAlert.getSeverity());
                properties.put("namespace", "causa");
                node.put("properties", properties);
                nodesById.put(candidateAlert.getId(), node);
            }
        }

        // 2. Score other alerting pods as candidate root causes
        for (ServiceAlert candidateAlert : activeAlerts) {
            // Skip the symptom alert itself
            if (candidateAlert.getId().equals(symptomAlertId)) {
                continue;
            }

            String candidatePodId = null;
            Map<String, Object> candidateSource = candidateAlert.getSource();
            if (candidateSource != null) {
                Map<String, String> props = (Map<String, String>) candidateSource.get("properties");
                if (props != null && props.containsKey("name")) {
                    candidatePodId = "pod-" + props.get("name").replace("-pod", "");
                }
            }

            if (candidatePodId == null || !nodesById.containsKey(candidatePodId)) {
                continue;
            }

            // Find shortest path from symptomPodId to candidatePodId in the call graph
            List<String> path = findShortestPath(symptomPodId, candidatePodId, adjList);
            if (path != null) {
                // We found a propagation path! Build the trajectory
                Trajectory traj = new Trajectory();
                
                // Score formula:
                // Base score: 0.8
                // Distance penalty: 0.9^d where d is distance (path size - 1)
                // Severity bonus: +0.15 for critical candidate alert, +0.0 for warning
                int distance = path.size() - 1;
                double baseScore = 0.75;
                double distanceMultiplier = Math.pow(0.9, distance);
                double severityBonus = "critical".equalsIgnoreCase(candidateAlert.getSeverity()) ? 0.15 : 0.05;
                
                double score = (baseScore * distanceMultiplier) + severityBonus;
                score = Math.min(0.99, Math.max(0.1, score)); // Cap between 0.1 and 0.99
                
                traj.setScore(Double.parseDouble(String.format("%.2f", score)));

                // 3. Assemble trajectory nodes
                // Add symptom alert node at start
                traj.getNodes().add(nodesById.get(symptomAlertId));
                
                // Add all pods along the path
                for (String podId : path) {
                    traj.getNodes().add(nodesById.get(podId));
                }
                
                // Add candidate root cause alert node at end
                traj.getNodes().add(nodesById.get(candidateAlert.getId()));

                // 4. Assemble trajectory links
                // Add link: symptomAlert -> symptomPod
                traj.getLinks().add(findLink(topology.getLinks(), symptomAlertId, symptomPodId));
                
                // Add pod-to-pod links along the path
                for (int i = 0; i < path.size() - 1; i++) {
                    traj.getLinks().add(findLink(topology.getLinks(), path.get(i), path.get(i + 1)));
                }
                
                // Add link: candidateAlert -> candidatePod
                traj.getLinks().add(findLink(topology.getLinks(), candidateAlert.getId(), candidatePodId));

                trajectories.add(traj);
            }
        }

        // 5. Always add a "Local" baseline trajectory (symptom alert -> symptom pod) representing a local issue
        Trajectory localTraj = new Trajectory();
        localTraj.setScore(0.35);
        localTraj.getNodes().add(nodesById.get(symptomAlertId));
        localTraj.getNodes().add(nodesById.get(symptomPodId));
        localTraj.getLinks().add(findLink(topology.getLinks(), symptomAlertId, symptomPodId));
        trajectories.add(localTraj);

        // Sort trajectories descending by score
        trajectories.sort((t1, t2) -> Double.compare(t2.getScore(), t1.getScore()));

        return trajectories;
    }

    // Breadth-First Search (BFS) to find the shortest path in directed graph
    private List<String> findShortestPath(String start, String end, Map<String, List<Map<String, Object>>> adjList) {
        if (start.equals(end)) {
            return Collections.singletonList(start);
        }

        Queue<List<String>> queue = new LinkedList<>();
        queue.add(Collections.singletonList(start));
        
        Set<String> visited = new HashSet<>();
        visited.add(start);

        while (!queue.isEmpty()) {
            List<String> path = queue.poll();
            String lastNode = path.get(path.size() - 1);

            List<Map<String, Object>> edges = adjList.get(lastNode);
            if (edges != null) {
                for (Map<String, Object> edge : edges) {
                    String neighbor = (String) edge.get("target");
                    if (!visited.contains(neighbor)) {
                        List<String> newPath = new ArrayList<>(path);
                        newPath.add(neighbor);
                        
                        if (neighbor.equals(end)) {
                            return newPath;
                        }
                        
                        visited.add(neighbor);
                        queue.add(newPath);
                    }
                }
            }
        }

        return null; // No path found
    }

    private Map<String, Object> findLink(List<Map<String, Object>> links, String source, String target) {
        for (Map<String, Object> link : links) {
            String src = (String) link.get("source");
            String dst = (String) link.get("target");
            if (src.equals(source) && dst.equals(target)) {
                return link;
            }
        }
        
        // Fallback: Create dynamic link if not found
        Map<String, Object> link = new HashMap<>();
        link.put("id", "link-" + source + "-" + target);
        link.put("source", source);
        link.put("target", target);
        Map<String, Object> properties = new HashMap<>();
        properties.put("strength", 1.0);
        link.put("properties", properties);
        return link;
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
