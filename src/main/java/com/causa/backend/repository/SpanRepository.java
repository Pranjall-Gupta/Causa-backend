package com.causa.backend.repository;

import com.causa.backend.model.DbSpan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SpanRepository extends JpaRepository<DbSpan, Long> {
    
    // Find spans whose start time falls within the given range (Unix nanoseconds)
    List<DbSpan> findByStartTimeUnixNanoBetween(Long startNano, Long endNano);
    
    // Find spans for a specific service in a time range
    List<DbSpan> findByServiceNameAndStartTimeUnixNanoBetween(String serviceName, Long startNano, Long endNano);
    
    // Get unique service names observed in spans
    @Query("SELECT DISTINCT s.serviceName FROM DbSpan s")
    List<String> findDistinctServiceNames();

    // Get unique service names observed in spans since a given timestamp (Unix nanoseconds)
    @Query("SELECT DISTINCT s.serviceName FROM DbSpan s WHERE s.startTimeUnixNano >= :sinceNano")
    List<String> findDistinctServiceNamesSince(@Param("sinceNano") Long sinceNano);
}
