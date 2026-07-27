package com.causa.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "logs")
public class DbLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long timestampMs;
    private String serviceName;
    private String severity;
    @Column(length = 2048)
    private String body;

    public DbLog() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTimestampMs() { return timestampMs; }
    public void setTimestampMs(Long timestampMs) { this.timestampMs = timestampMs; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
}
