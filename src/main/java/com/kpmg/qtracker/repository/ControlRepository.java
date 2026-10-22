package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.Control;
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

    List<Control> findByCreatedByMailOrderByCreatedAtDesc(String userEmail);
    List<Control> findAllByOrderByIdDesc();

    /**
     * The controls with "KDN" anywhere in the Control ID, any case, newest first: every KDN control and some others
     * (AccessPolicy.isKdnControl, the one rule, then keeps the KDN ones). The KDN users' lists read these instead of
     * the whole table.
     */
    @Query("SELECT c FROM Control c WHERE UPPER(c.controlId) LIKE '%KDN%' ORDER BY c.id DESC")
    List<Control> findKdnCandidatesOrderByIdDesc();

    /** The controls of the planned notifications: in one of the statuses (upper case), not returned from Completed. */
    @Query("SELECT c.id FROM Control c WHERE UPPER(TRIM(c.performanceStatus)) IN :statuses AND c.reopenedAt IS NULL"
            + " ORDER BY c.id")
    List<Long> findIdsForPlannedNotifications(@Param("statuses") java.util.Collection<String> statuses);
}
