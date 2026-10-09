package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.ControlAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ControlAttachmentRepository extends JpaRepository<ControlAttachment, Long> {

    Optional<ControlAttachment> findByControlIdAndTabAndFileName(Long controlId, String tab, String fileName);

    boolean existsByControlIdAndFileName(Long controlId, String fileName);

    void deleteByControlIdAndTabAndFileName(Long controlId, String tab, String fileName);
}
