package com.causa.backend.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FixSuggestionRequest {

    private String symptomAlertId;
    private Double trajectoryScore;
    private TrajectoryData trajectory;

    public FixSuggestionRequest() {
    }

    public FixSuggestionRequest(String symptomAlertId, Double trajectoryScore, TrajectoryData trajectory) {
        this.symptomAlertId = symptomAlertId;
        this.trajectoryScore = trajectoryScore;
        this.trajectory = trajectory;
    }

    public String getSymptomAlertId() {
        return symptomAlertId;
    }

    public void setSymptomAlertId(String symptomAlertId) {
        this.symptomAlertId = symptomAlertId;
    }

    public Double getTrajectoryScore() {
        return trajectoryScore;
    }

    public void setTrajectoryScore(Double trajectoryScore) {
        this.trajectoryScore = trajectoryScore;
    }

    public TrajectoryData getTrajectory() {
        return trajectory;
    }

    public void setTrajectory(TrajectoryData trajectory) {
        this.trajectory = trajectory;
    }

    public static class TrajectoryData {
        private List<Map<String, Object>> nodes = new ArrayList<>();
        private List<Map<String, Object>> links = new ArrayList<>();

        public TrajectoryData() {
        }

        public TrajectoryData(List<Map<String, Object>> nodes, List<Map<String, Object>> links) {
            this.nodes = nodes;
            this.links = links;
        }

        public List<Map<String, Object>> getNodes() {
            return nodes;
        }

        public void setNodes(List<Map<String, Object>> nodes) {
            this.nodes = nodes;
        }

        public List<Map<String, Object>> getLinks() {
            return links;
        }

        public void setLinks(List<Map<String, Object>> links) {
            this.links = links;
        }
    }
}
