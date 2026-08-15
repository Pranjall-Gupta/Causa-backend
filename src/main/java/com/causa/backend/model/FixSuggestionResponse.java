package com.causa.backend.model;

public class FixSuggestionResponse {

    private String alertId;
    private String provider;
    private String summary;
    private String suggestedFix;
    private String confidence;

    public FixSuggestionResponse() {
    }

    public FixSuggestionResponse(String alertId, String provider, String summary, String suggestedFix, String confidence) {
        this.alertId = alertId;
        this.provider = provider;
        this.summary = summary;
        this.suggestedFix = suggestedFix;
        this.confidence = confidence;
    }

    public String getAlertId() {
        return alertId;
    }

    public void setAlertId(String alertId) {
        this.alertId = alertId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSuggestedFix() {
        return suggestedFix;
    }

    public void setSuggestedFix(String suggestedFix) {
        this.suggestedFix = suggestedFix;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }
}
