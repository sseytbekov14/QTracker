package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ControlRepository extends JpaRepository<Control, Long> {
    List<Control> findByCreatedByMail(String userEmail);
    List<Control> findByComponent(String component);

    Optional<Control> findByControlId(String controlId);

    boolean existsByControlId(String controlId);

    @Query("SELECT c.controlId FROM Control c WHERE c.controlId LIKE :prefix ORDER BY c.controlId DESC")
    List<String> findControlIdsByPrefix(@Param("prefix") String prefix);

    default boolean isControlIdUnique(String controlId) {
        return findByControlId(controlId).isEmpty();
    }

    @Query("SELECT MAX(c.id) FROM Control c")
    Optional<Long> findMaxId();

    List<Control> findByCreatedBy(User user);

    @Query("SELECT COUNT(c) FROM Control c WHERE c.component = :component")
    Long countByComponent(@Param("component") String component);

    @Query("SELECT COUNT(c) FROM Control c")
    Long countAllControls();

    List<Control> findByCreatedByMailOrderByCreatedAtDesc(String userEmail);
    List<Control> findByComponentOrderByCreatedAtDesc(String component);
    List<Control> findAllByOrderByIdDesc();
    List<Control> findByControlStatusIgnoreCase(String controlStatus);
    List<Control> findByPerformanceStatusIgnoreCase(String performanceStatus);

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE c.performance_status NOT IN ('COMPLETED')
            """, nativeQuery = true)
    List<ReminderControlProjection> findAllForReminders();

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE LOWER(TRIM(c.control_frequency)) = 'monthly'
              AND c.control_operation_date = :operationDate
              AND COALESCE(UPPER(TRIM(c.performance_status)), '') IN ('IN_PROGRESS', 'REVIEW')
            """, nativeQuery = true)
    List<ReminderControlProjection> findMonthlyDay0Candidates(@Param("operationDate") java.time.LocalDate operationDate);

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE LOWER(c.control_frequency) = 'monthly'
              AND c.control_operation_date IS NOT NULL
            """, nativeQuery = true)
    List<ReminderControlProjection> findMonthlyDay3Day6Candidates();

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE LOWER(c.control_frequency) = 'monthly'
              AND c.control_operation_deadline IS NOT NULL
            """, nativeQuery = true)
    List<ReminderControlProjection> findMonthlyOverdueCandidates();

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE LOWER(TRIM(c.control_frequency)) = 'quarterly'
              AND c.control_operation_date = :operationDate
              AND COALESCE(UPPER(TRIM(c.performance_status)), '') IN ('IN_PROGRESS', 'REVIEW')
            """, nativeQuery = true)
    List<ReminderControlProjection> findQuarterlyDay0Candidates(@Param("operationDate") java.time.LocalDate operationDate);

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE LOWER(c.control_frequency) = 'quarterly'
              AND c.control_operation_date IS NOT NULL
            """, nativeQuery = true)
    List<ReminderControlProjection> findQuarterlyDay5Day12Candidates();

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE LOWER(c.control_frequency) = 'quarterly'
              AND c.control_operation_deadline IS NOT NULL
            """, nativeQuery = true)
    List<ReminderControlProjection> findQuarterlyOverdueCandidates();

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE LOWER(TRIM(c.control_frequency)) = 'recurring'
              AND c.control_operation_date = :operationDate
              AND COALESCE(UPPER(TRIM(c.performance_status)), '') IN ('IN_PROGRESS', 'REVIEW')
            """, nativeQuery = true)
    List<ReminderControlProjection> findRecurringDay0Candidates(@Param("operationDate") java.time.LocalDate operationDate);

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE LOWER(c.control_frequency) = 'recurring'
              AND c.control_operation_date IS NOT NULL
            """, nativeQuery = true)
    List<ReminderControlProjection> findRecurringDay5Day12Candidates();

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE LOWER(c.control_frequency) = 'recurring'
              AND c.control_operation_deadline IS NOT NULL
            """, nativeQuery = true)
    List<ReminderControlProjection> findRecurringOverdueCandidates();

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE LOWER(TRIM(c.control_frequency)) IN ('ad-hoc', 'ad hoc')
              AND c.control_operation_date = :operationDate
              AND COALESCE(UPPER(TRIM(c.performance_status)), '') IN ('IN_PROGRESS', 'REVIEW')
            """, nativeQuery = true)
    List<ReminderControlProjection> findAdhocDay0Candidates(@Param("operationDate") java.time.LocalDate operationDate);

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE LOWER(c.control_frequency) = 'ad-hoc'
              AND c.control_operation_date IS NOT NULL
            """, nativeQuery = true)
    List<ReminderControlProjection> findAdhocDay5Day12Candidates();

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE LOWER(c.control_frequency) = 'ad-hoc'
              AND c.control_operation_deadline IS NOT NULL
            """, nativeQuery = true)
    List<ReminderControlProjection> findAdhocOverdueCandidates();

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE LOWER(TRIM(c.control_frequency)) IN (
                    'annual',
                    'annually',
                    'semi annual',
                    'semi-annual',
                    'semiannually',
                    'semi-annually',
                    'semiannual'
                  )
              AND c.control_operation_date = :operationDate
              AND COALESCE(UPPER(TRIM(c.performance_status)), '') IN ('IN_PROGRESS', 'REVIEW')
            """, nativeQuery = true)
    List<ReminderControlProjection> findAnnualSemiDay0Candidates(@Param("operationDate") java.time.LocalDate operationDate);

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE LOWER(c.control_frequency) IN ('annual', 'semi annual')
              AND c.control_operation_date IS NOT NULL
            """, nativeQuery = true)
    List<ReminderControlProjection> findAnnualSemiDay5Day25Candidates();

    @Query(value = """
            SELECT c.id                         AS "controlId",
                   c.control_id                 AS "controlName",
                   c.control_description        AS "controlDescription",
                   c.control_frequency          AS "frequency",
                   c.performance_status             AS "status",
                   c.control_operation_date     AS "operationDate",
                   c.control_operation_deadline AS "deadlineDate",
                   c.facilitator                AS "facilitator",
                   c.control_operator           AS "controlOperator",
                   c.soqm_lead                  AS "soqmLead",
                   c.process_owner              AS "processOwner"
            FROM controls c
            WHERE LOWER(c.control_frequency) IN ('annual', 'semi annual')
              AND c.control_operation_deadline IS NOT NULL
            """, nativeQuery = true)
    List<ReminderControlProjection> findAnnualSemiOverdueCandidates();
}

