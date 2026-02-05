package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.ControlAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ControlAssignmentRepository extends JpaRepository<ControlAssignment, Long> {
    Optional<ControlAssignment> findByControlId(Long controlId);

    @Query("SELECT ca.controlId FROM ControlAssignment ca WHERE ca.controlOperator LIKE %:email%")
    List<Long> findControlIdsByControlOperator(@Param("email") String email);

    @Query("SELECT ca.controlId FROM ControlAssignment ca WHERE ca.facilitator LIKE %:email%")
    List<Long> findControlIdsByFacilitator(@Param("email") String email);

    @Query("SELECT ca.controlId FROM ControlAssignment ca WHERE ca.processOwner LIKE %:email%")
    List<Long> findControlIdsByProcessOwner(@Param("email") String email);

    @Query("SELECT ca.controlId FROM ControlAssignment ca WHERE ca.soqmLead LIKE %:email%")
    List<Long> findControlIdsBySoqmLead(@Param("email") String email);
}