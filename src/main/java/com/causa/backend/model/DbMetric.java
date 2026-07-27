package com.causa.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "metrics")
public class DbMetric {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long timestampMs;
    private String serviceName;
    private String metricName; // e.g. "cpu_usage", "memory_usage"
    
    @Column(name = "metric_val")
    private Double value;

    public DbMetric() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTimestampMs() { return timestampMs; }
    public void setTimestampMs(Long timestampMs) { this.timestampMs = timestampMs; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }

    public Double getValue() { return value; }
    public void setValue(Double value) { this.value = value; }
}
