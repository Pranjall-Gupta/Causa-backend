package com.causa.backend.controller;

import com.causa.backend.model.DbProject;
import com.causa.backend.repository.ProjectRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/v1/admin")
@CrossOrigin(origins = "http://localhost:3000")
public class AdminController {

    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);

    @Value("${causa.admin.key:}")
    private String adminKey;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private Environment environment;

    @PostConstruct
    public void checkAdminKeyConfig() {
        String[] activeProfiles = environment.getActiveProfiles();
        boolean isDev = Arrays.asList(activeProfiles).contains("dev");
        if (!isDev) {
            if (adminKey == null || adminKey.trim().isEmpty() || "changeme-local-dev-key".equals(adminKey)) {
                logger.warn("SECURITY WARNING: causa.admin.key is unset or using default development value in a non-dev profile!");
            }
        }
    }

    public static class CreateProjectRequest {
        private String name;

        public CreateProjectRequest() {}

        public CreateProjectRequest(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @PostMapping("/projects")
    public ResponseEntity<?> createProject(
            @RequestHeader(value = "X-Causa-Admin-Key", required = false) String headerAdminKey,
            @RequestBody(required = false) CreateProjectRequest request) {

        if (adminKey == null || adminKey.trim().isEmpty() || headerAdminKey == null || !adminKey.equals(headerAdminKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized: Invalid or missing X-Causa-Admin-Key"));
        }

        if (request == null || request.getName() == null || request.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing or invalid project name"));
        }

        String generatedKey = "causa_proj_" + UUID.randomUUID().toString();

        DbProject project = new DbProject();
        project.setName(request.getName().trim());
        project.setApiKey(generatedKey);
        project.setActive(true);
        project.setCreatedAtMs(System.currentTimeMillis());

        DbProject savedProject = projectRepository.save(project);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", savedProject.getId());
        response.put("name", savedProject.getName());
        response.put("apiKey", savedProject.getApiKey());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
