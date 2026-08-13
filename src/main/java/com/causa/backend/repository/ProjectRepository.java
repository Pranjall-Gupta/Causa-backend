package com.causa.backend.repository;

import com.causa.backend.model.DbProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<DbProject, Long> {
    Optional<DbProject> findByApiKeyAndActiveTrue(String apiKey);
}
