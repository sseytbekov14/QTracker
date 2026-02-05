package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.ControlPerformance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface PerformanceRepository extends JpaRepository<ControlPerformance, Long> {
    Optional<ControlPerformance> findByControlId(Long controlId);
}