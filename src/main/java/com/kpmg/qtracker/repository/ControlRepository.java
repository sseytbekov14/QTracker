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

    /** The controls a user created (the user's deletion keeps them, with the creator as text). */
    List<Control> findByCreatedById(Long userId);

    List<Control> findByCreatedByMail(String userEmail);
    List<Control> findByComponent(String component);

    Optional<Control> findByControlId(String controlId);

    boolean existsByControlId(String controlId);

    /** The controls made from this one with Create Control (its next cycles), oldest first. */
    List<Control> findByCreatedFromControlIdOrderByIdAsc(Long createdFromControlId);

    /** The control with this Control ID in any case (Create Control keeps IDs unique ignoring case). */
    @Query("SELECT c FROM Control c WHERE LOWER(c.controlId) = LOWER(TRIM(:controlId)) ORDER BY c.id")
    List<Control> findByControlIdIgnoringCase(@Param("controlId") String controlId);

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
    List<Control> findAllByOrderByIdDesc();
    List<Control> findByControlStatusIgnoreCase(String controlStatus);
    List<Control> findByPerformanceStatusIgnoreCase(String performanceStatus);

    /** The controls of the planned notifications: in one of the statuses (upper case), not returned from Completed. */
    @Query("SELECT c.id FROM Control c WHERE UPPER(TRIM(c.performanceStatus)) IN :statuses AND c.reopenedAt IS NULL"
            + " ORDER BY c.id")
    List<Long> findIdsForPlannedNotifications(@Param("statuses") java.util.Collection<String> statuses);
}
