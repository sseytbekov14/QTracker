package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.ControlAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ControlAssignmentRepository extends JpaRepository<ControlAssignment, Long> {
    Optional<ControlAssignment> findByControlId(Long controlId);
    List<ControlAssignment> findByNextControlOperationDate(LocalDate nextControlOperationDate);

    @Query(value = """
            SELECT a.*
            FROM controls a
            WHERE LOWER(TRIM(a.control_frequency)) = 'monthly'
              AND a.next_control_operation_date >= :startDateTime
              AND a.next_control_operation_date < :endDateTime
            """, nativeQuery = true)
    List<ControlAssignment> findMonthlyByNextControlOperationDateRange(
            @Param("startDateTime") java.time.LocalDateTime startDateTime,
            @Param("endDateTime") java.time.LocalDateTime endDateTime);

    @Query(value = """
            SELECT a.*
            FROM controls a
            WHERE LOWER(TRIM(a.control_frequency)) = 'quarterly'
              AND a.next_control_operation_date >= :startDateTime
              AND a.next_control_operation_date < :endDateTime
            """, nativeQuery = true)
    List<ControlAssignment> findQuarterlyByNextControlOperationDateRange(
            @Param("startDateTime") java.time.LocalDateTime startDateTime,
            @Param("endDateTime") java.time.LocalDateTime endDateTime);

    @Query(value = """
            SELECT a.*
            FROM controls a
            WHERE LOWER(TRIM(a.control_frequency)) = 'recurring'
              AND a.next_control_operation_date >= :startDateTime
              AND a.next_control_operation_date < :endDateTime
            """, nativeQuery = true)
    List<ControlAssignment> findRecurringByNextControlOperationDateRange(
            @Param("startDateTime") java.time.LocalDateTime startDateTime,
            @Param("endDateTime") java.time.LocalDateTime endDateTime);

    @Query(value = """
            SELECT a.*
            FROM controls a
            WHERE LOWER(TRIM(a.control_frequency)) = 'annual'
              AND a.next_control_operation_date >= :startDateTime
              AND a.next_control_operation_date < :endDateTime
            """, nativeQuery = true)
    List<ControlAssignment> findAnnualByNextControlOperationDateRange(
            @Param("startDateTime") java.time.LocalDateTime startDateTime,
            @Param("endDateTime") java.time.LocalDateTime endDateTime);

    @Query(value = """
            SELECT a.*
            FROM controls a
            WHERE LOWER(TRIM(a.control_frequency)) IN ('semi annual', 'semi-annual', 'semiannual')
              AND a.next_control_operation_date >= :startDateTime
              AND a.next_control_operation_date < :endDateTime
            """, nativeQuery = true)
    List<ControlAssignment> findSemiAnnualByNextControlOperationDateRange(
            @Param("startDateTime") java.time.LocalDateTime startDateTime,
            @Param("endDateTime") java.time.LocalDateTime endDateTime);

    @Query("""
            SELECT COUNT(ca) > 0
            FROM ControlAssignment ca
            JOIN Control c ON c.id = ca.controlId
            WHERE c.controlId = :controlId
              AND ca.controlOperationDate = :operationDate
            """)
    boolean existsByControlIdAndOperationDate(@Param("controlId") String controlId,
                                              @Param("operationDate") LocalDate operationDate);

    @Query(value = """
            SELECT COUNT(*) > 0
            FROM controls c
            WHERE c.control_operation_date = :operationDate
              AND (c.control_id = :baseIdExact
                   OR c.control_id LIKE CONCAT(:baseIdLike, '\\_%') ESCAPE '\\')
            """, nativeQuery = true)
    boolean existsByBaseControlIdAndOperationDate(@Param("baseIdExact") String baseIdExact,
                                                  @Param("baseIdLike") String baseIdLike,
                                                  @Param("operationDate") LocalDate operationDate);

    @Query("SELECT ca.controlId FROM ControlAssignment ca WHERE ca.controlOperator LIKE %:email%")
    List<Long> findControlIdsByControlOperator(@Param("email") String email);

    @Query("SELECT ca.controlId FROM ControlAssignment ca WHERE ca.facilitator LIKE %:email%")
    List<Long> findControlIdsByFacilitator(@Param("email") String email);

    @Query("SELECT ca.controlId FROM ControlAssignment ca WHERE ca.processOwner LIKE %:email%")
    List<Long> findControlIdsByProcessOwner(@Param("email") String email);

    @Query("SELECT ca.controlId FROM ControlAssignment ca WHERE ca.soqmLead LIKE %:email%")
    List<Long> findControlIdsBySoqmLead(@Param("email") String email);

    @Query("SELECT ca.controlId FROM ControlAssignment ca WHERE ca.controlSharedWith LIKE %:email%")
    List<Long> findControlIdsByControlSharedWith(@Param("email") String email);

    @Query(value = """
            SELECT c.id
            FROM controls c
            WHERE c.control_operation_deadline IS NOT NULL
              AND c.control_operation_deadline < :today
              AND COALESCE(UPPER(TRIM(c.performance_status)), 'DRAFT') NOT IN ('DRAFT', 'COMPLETED')
            """, nativeQuery = true)
    List<Long> findOverdueControlIds(@Param("today") LocalDate today);
}
