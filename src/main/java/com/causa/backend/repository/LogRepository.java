package com.causa.backend.repository;

import com.causa.backend.model.DbLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LogRepository extends JpaRepository<DbLog, Long> {
    List<DbLog> findByTimestampMsBetween(Long startMs, Long endMs);
    List<DbLog> findByServiceNameAndTimestampMsBetween(String serviceName, Long startMs, Long endMs);
}
