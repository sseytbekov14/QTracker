package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.ControlDetails;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ControlDetailsRepository extends JpaRepository<ControlDetails, Long> {
    Optional<ControlDetails> findByControlId(Long controlId);
}