package com.causa.backend.filter;

import com.causa.backend.model.DbProject;
import com.causa.backend.repository.ProjectRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/v1/traces",
            "/v1/metrics",
            "/v1/logs",
            "/v1/fix-suggestion"
    );

    @Autowired
    private ProjectRepository projectRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        if (request.getContextPath() != null && !request.getContextPath().isEmpty() && path.startsWith(request.getContextPath())) {
            path = path.substring(request.getContextPath().length());
        }

        if (!PROTECTED_PATHS.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader("X-Causa-Api-Key");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Missing X-Causa-Api-Key header\"}");
            return;
        }

        Optional<DbProject> projectOpt = projectRepository.findByApiKeyAndActiveTrue(apiKey.trim());
        if (projectOpt.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Invalid or inactive API key\"}");
            return;
        }

        DbProject project = projectOpt.get();
        request.setAttribute("causaProjectId", project.getId());

        filterChain.doFilter(request, response);
    }
}
