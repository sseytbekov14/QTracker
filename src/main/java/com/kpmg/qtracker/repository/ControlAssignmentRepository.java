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
     * with "/FY../location/period" (maybe with "(n)"). The rule of "this cycle is already there" for Create Control
     * (ControlCycleService).
     */
    String CYCLE_OF_BASE_ON_DAY = """
            FROM controls c
            WHERE c.control_operation_date = :operationDate
              AND (c.control_id = :baseIdExact
                   OR c.control_id LIKE CONCAT(:baseIdLike, '\\_%') ESCAPE '\\'
                   OR c.control_id LIKE CONCAT(:baseIdLike, '/%') ESCAPE '\\')
            """;

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
