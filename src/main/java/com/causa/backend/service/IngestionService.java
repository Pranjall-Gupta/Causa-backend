package com.causa.backend.service;

import com.causa.backend.model.DbLog;
import com.causa.backend.model.DbMetric;
import com.causa.backend.model.DbSpan;
import com.causa.backend.repository.LogRepository;
import com.causa.backend.repository.MetricRepository;
import com.causa.backend.repository.SpanRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class IngestionService {

    @Autowired
    private SpanRepository spanRepository;

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private MetricRepository metricRepository;

    @SuppressWarnings("unchecked")
    public void ingestTraces(Map<String, Object> payload) {
        Object resourceSpansObj = payload.get("resourceSpans");
        if (!(resourceSpansObj instanceof List)) return;
        List<?> resourceSpans = (List<?>) resourceSpansObj;

        for (Object rsObj : resourceSpans) {
            if (!(rsObj instanceof Map)) continue;
            Map<String, Object> rs = (Map<String, Object>) rsObj;
            String serviceName = "unknown-service";
            
            Object resourceObj = rs.get("resource");
            if (resourceObj instanceof Map) {
                Map<String, Object> resource = (Map<String, Object>) resourceObj;
                Object attributesObj = resource.get("attributes");
                if (attributesObj instanceof List) {
                    List<?> attributes = (List<?>) attributesObj;
                    for (Object attrObj : attributes) {
                        if (attrObj instanceof Map) {
                            Map<String, Object> attr = (Map<String, Object>) attrObj;
                            if ("service.name".equals(attr.get("key"))) {
                                Object valObj = attr.get("value");
                                if (valObj instanceof Map) {
                                    Map<String, Object> valueMap = (Map<String, Object>) valObj;
                                    if (valueMap.containsKey("stringValue")) {
                                        serviceName = (String) valueMap.get("stringValue");
                                    } else if (valueMap.containsKey("value")) {
                                        serviceName = valueMap.get("value").toString();
                                    }
                                } else if (valObj != null) {
                                    serviceName = valObj.toString();
                                }
                            }
                        }
                    }
                }
            }

            Object scopeSpansObj = rs.get("scopeSpans");
            if (!(scopeSpansObj instanceof List)) continue;
            List<?> scopeSpans = (List<?>) scopeSpansObj;

            for (Object ssObj : scopeSpans) {
                if (!(ssObj instanceof Map)) continue;
                Map<String, Object> ss = (Map<String, Object>) ssObj;
                
                Object spansObj = ss.get("spans");
                if (!(spansObj instanceof List)) continue;
                List<?> spans = (List<?>) spansObj;

                for (Object sObj : spans) {
                    if (!(sObj instanceof Map)) continue;
                    Map<String, Object> s = (Map<String, Object>) sObj;
                    
                    DbSpan dbSpan = new DbSpan();
                    dbSpan.setServiceName(serviceName);
                    dbSpan.setSpanId(s.get("spanId") != null ? s.get("spanId").toString() : null);
                    dbSpan.setTraceId(s.get("traceId") != null ? s.get("traceId").toString() : null);
                    dbSpan.setParentSpanId(s.get("parentSpanId") != null ? s.get("parentSpanId").toString() : null);
                    dbSpan.setName(s.get("name") != null ? s.get("name").toString() : null);
                    dbSpan.setKind(s.get("kind") != null ? s.get("kind").toString() : "SPAN_KIND_INTERNAL");

                    long startTime = parseLong(s.get("startTimeUnixNano"));
                    long endTime = parseLong(s.get("endTimeUnixNano"));
                    dbSpan.setStartTimeUnixNano(startTime);
                    dbSpan.setEndTimeUnixNano(endTime);
                    dbSpan.setDurationMs(startTime > 0 && endTime > startTime ? (endTime - startTime) / 1000000.0 : 0.0);

                    // Parse status
                    Object statusObj = s.get("status");
                    String statusCode = "UNSET";
                    String statusMessage = "";
                    if (statusObj instanceof Map) {
                        Map<String, Object> status = (Map<String, Object>) statusObj;
                        Object codeObj = status.get("code");
                        if (codeObj != null) {
                            String codeStr = codeObj.toString();
                            if (codeStr.equals("2") || codeStr.contains("ERROR")) {
                                statusCode = "ERROR";
                            } else if (codeStr.equals("1") || codeStr.contains("OK")) {
                                statusCode = "OK";
                            }
                        }
                        statusMessage = status.get("message") != null ? status.get("message").toString() : "";
                    }
                    dbSpan.setStatusCode(statusCode);
                    dbSpan.setStatusMessage(statusMessage);

                    spanRepository.save(dbSpan);
                }
            }
        }
    }

    public void ingestMetrics(List<Map<String, Object>> metrics) {
        for (Map<String, Object> m : metrics) {
            DbMetric dbMetric = new DbMetric();
            dbMetric.setServiceName((String) m.get("serviceName"));
            dbMetric.setMetricName((String) m.get("metricName"));
            dbMetric.setValue(Double.parseDouble(m.get("value").toString()));
            
            long ts = m.get("timestampMs") != null ? parseLong(m.get("timestampMs")) : System.currentTimeMillis();
            dbMetric.setTimestampMs(ts);
            
            metricRepository.save(dbMetric);
        }
    }

    public void ingestLogs(List<Map<String, Object>> logs) {
        for (Map<String, Object> l : logs) {
            DbLog dbLog = new DbLog();
            dbLog.setServiceName((String) l.get("serviceName"));
            dbLog.setSeverity((String) l.get("severity"));
            dbLog.setBody((String) l.get("body"));
            
            long ts = l.get("timestampMs") != null ? parseLong(l.get("timestampMs")) : System.currentTimeMillis();
            dbLog.setTimestampMs(ts);
            
            logRepository.save(dbLog);
        }
    }

    private long parseLong(Object obj) {
        if (obj == null) return 0L;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (Exception e) {
            return 0L;
        }
    }
}
