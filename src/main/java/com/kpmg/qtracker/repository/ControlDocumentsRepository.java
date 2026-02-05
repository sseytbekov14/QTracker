package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.ControlDocuments;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ControlDocumentsRepository extends JpaRepository<ControlDocuments, Long> {
    Optional<ControlDocuments> findByControlId(Long controlId);
}