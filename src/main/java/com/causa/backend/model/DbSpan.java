package com.causa.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "spans")
public class DbSpan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String spanId;
    private String traceId;
    private String parentSpanId;
    private String serviceName;
    private String name;
    private String kind;
    private Long startTimeUnixNano;
    private Long endTimeUnixNano;
    private Double durationMs;
    private String statusCode; // e.g. "ERROR", "OK", "UNSET"
    private String statusMessage;

    public DbSpan() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public Long getStartTimeUnixNano() { return startTimeUnixNano; }
    public void setStartTimeUnixNano(Long startTimeUnixNano) { this.startTimeUnixNano = startTimeUnixNano; }

    public Long getEndTimeUnixNano() { return endTimeUnixNano; }
    public void setEndTimeUnixNano(Long endTimeUnixNano) { this.endTimeUnixNano = endTimeUnixNano; }

    public Double getDurationMs() { return durationMs; }
    public void setDurationMs(Double durationMs) { this.durationMs = durationMs; }

    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
}
