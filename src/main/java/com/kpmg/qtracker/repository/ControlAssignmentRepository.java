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

    /**
     * A cycle of a base control on a day: its ID is the base itself, the base with a "_" suffix (older IDs) or the base
     * with "/FY../location/period" (ControlIdGeneratorService, maybe with "(n)"). The one rule of "this cycle is already
     * there" for the daily auto-creation and Create Control.
     */
    String CYCLE_OF_BASE_ON_DAY = """
            FROM controls c
            WHERE c.control_operation_date = :operationDate
              AND (c.control_id = :baseIdExact
                   OR c.control_id LIKE CONCAT(:baseIdLike, '\\_%') ESCAPE '\\'
                   OR c.control_id LIKE CONCAT(:baseIdLike, '/%') ESCAPE '\\')
            """;

    /** A cycle of this base control on this day already exists ({@link #CYCLE_OF_BASE_ON_DAY}). */
    @Query(value = "SELECT COUNT(*) > 0 " + CYCLE_OF_BASE_ON_DAY, nativeQuery = true)
    boolean existsByBaseControlIdAndOperationDate(@Param("baseIdExact") String baseIdExact,
                                                  @Param("baseIdLike") String baseIdLike,
                                                  @Param("operationDate") LocalDate operationDate);

    /** The ids of the cycles of this base control on this day ({@link #CYCLE_OF_BASE_ON_DAY}), oldest first. */
    @Query(value = "SELECT c.id " + CYCLE_OF_BASE_ON_DAY + " ORDER BY c.id", nativeQuery = true)
    List<Long> findIdsByBaseControlIdAndOperationDate(@Param("baseIdExact") String baseIdExact,
                                                      @Param("baseIdLike") String baseIdLike,
                                                      @Param("operationDate") LocalDate operationDate);

    @Query("SELECT ca.controlId FROM ControlAssignment ca "
            + "WHERE LOWER(ca.controlOperator) LIKE LOWER(CONCAT('%', TRIM(:email), '%'))")
    List<Long> findControlIdsByControlOperator(@Param("email") String email);

    @Query("SELECT ca.controlId FROM ControlAssignment ca "
            + "WHERE LOWER(ca.facilitator) LIKE LOWER(CONCAT('%', TRIM(:email), '%'))")
    List<Long> findControlIdsByFacilitator(@Param("email") String email);

    @Query("SELECT ca.controlId FROM ControlAssignment ca "
            + "WHERE LOWER(ca.processOwner) LIKE LOWER(CONCAT('%', TRIM(:email), '%'))")
    List<Long> findControlIdsByProcessOwner(@Param("email") String email);

    @Query("SELECT ca.controlId FROM ControlAssignment ca "
            + "WHERE LOWER(ca.soqmLead) LIKE LOWER(CONCAT('%', TRIM(:email), '%'))")
    List<Long> findControlIdsBySoqmLead(@Param("email") String email);

    @Query("SELECT ca.controlId FROM ControlAssignment ca "
            + "WHERE LOWER(ca.controlSharedWith) LIKE LOWER(CONCAT('%', TRIM(:email), '%'))")
    List<Long> findControlIdsByControlSharedWith(@Param("email") String email);
}
