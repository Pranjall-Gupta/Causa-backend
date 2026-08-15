package com.causa.backend.controller;

import com.causa.backend.model.FixSuggestionRequest;
import com.causa.backend.model.FixSuggestionResponse;
import com.causa.backend.service.FixSuggestionProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1")
@CrossOrigin(origins = "http://localhost:3000")
public class FixSuggestionController {

    @Autowired
    private FixSuggestionProvider fixSuggestionProvider;

    @PostMapping("/fix-suggestion")
    public ResponseEntity<?> getFixSuggestion(@RequestBody(required = false) FixSuggestionRequest request) {
        if (request == null || request.getSymptomAlertId() == null || request.getSymptomAlertId().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "symptomAlertId is required and cannot be blank."));
        }

        FixSuggestionResponse response = fixSuggestionProvider.generateFix(request);
        return ResponseEntity.ok(response);
    }
}
