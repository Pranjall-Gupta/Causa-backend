package com.causa.backend.repository;

import com.causa.backend.model.DbMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MetricRepository extends JpaRepository<DbMetric, Long> {
    List<DbMetric> findByServiceNameAndMetricName(String serviceName, String metricName);
    
    @Query("SELECT m FROM DbMetric m WHERE m.timestampMs = (SELECT MAX(m2.timestampMs) FROM DbMetric m2 WHERE m2.serviceName = m.serviceName AND m2.metricName = m.metricName)")
    List<DbMetric> findLatestMetrics();
}
