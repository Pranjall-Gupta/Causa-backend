package com.causa.backend.controller;

import com.causa.backend.service.IngestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
@CrossOrigin(origins = "http://localhost:3000")
public class IngestionController {

    @Autowired
    private IngestionService ingestionService;

    @PostMapping("/traces")
    public ResponseEntity<String> ingestTraces(@RequestBody Map<String, Object> payload) {
        try {
            ingestionService.ingestTraces(payload);
            return ResponseEntity.ok("Traces ingested successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error ingesting traces: " + e.getMessage());
        }
    }

    @PostMapping("/metrics")
    public ResponseEntity<String> ingestMetrics(@RequestBody List<Map<String, Object>> metrics) {
        try {
            ingestionService.ingestMetrics(metrics);
            return ResponseEntity.ok("Metrics ingested successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error ingesting metrics: " + e.getMessage());
        }
    }

    @PostMapping("/logs")
    public ResponseEntity<String> ingestLogs(@RequestBody List<Map<String, Object>> logs) {
        try {
            ingestionService.ingestLogs(logs);
            return ResponseEntity.ok("Logs ingested successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error ingesting logs: " + e.getMessage());
        }
    }
}
