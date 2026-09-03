package com.causa.backend.controller;

import com.causa.backend.service.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
@CrossOrigin(origins = "http://localhost:3000")
public class IngestionController {

    private static final Logger logger = LoggerFactory.getLogger(IngestionController.class);

    @Autowired
    private IngestionService ingestionService;

    @PostMapping("/traces")
    public ResponseEntity<String> ingestTraces(@RequestBody Map<String, Object> payload) {
        if (payload == null || !(payload.get("resourceSpans") instanceof List)) {
            return ResponseEntity.badRequest().body("Missing or invalid resourceSpans field");
        }
        try {
            ingestionService.ingestTraces(payload);
            return ResponseEntity.ok("Traces ingested successfully");
        } catch (Exception e) {
            logger.error("Error ingesting traces", e);
            return ResponseEntity.badRequest().body("Error ingesting traces");
        }
    }

    @PostMapping("/metrics")
    public ResponseEntity<String> ingestMetrics(@RequestBody List<Map<String, Object>> metrics) {
        if (metrics == null) {
            return ResponseEntity.badRequest().body("Missing or invalid metrics payload");
        }
        try {
            ingestionService.ingestMetrics(metrics);
            return ResponseEntity.ok("Metrics ingested successfully");
        } catch (Exception e) {
            logger.error("Error ingesting metrics", e);
            return ResponseEntity.badRequest().body("Error ingesting metrics");
        }
    }

    @PostMapping("/logs")
    public ResponseEntity<String> ingestLogs(@RequestBody List<Map<String, Object>> logs) {
        if (logs == null) {
            return ResponseEntity.badRequest().body("Missing or invalid logs payload");
        }
        try {
            ingestionService.ingestLogs(logs);
            return ResponseEntity.ok("Logs ingested successfully");
        } catch (Exception e) {
            logger.error("Error ingesting logs", e);
            return ResponseEntity.badRequest().body("Error ingesting logs");
        }
    }
}
