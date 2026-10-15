package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.ControlRelatedControl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ControlRelatedControlRepository extends JpaRepository<ControlRelatedControl, Long> {

    List<ControlRelatedControl> findByControlIdOrderBySortOrderAsc(Long controlId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ControlRelatedControl r WHERE r.controlId = :controlId")
    void deleteByControlId(@Param("controlId") Long controlId);

    /** What Other Related Controls shows of a control, without loading the whole row. */
    interface ControlSummary {
        Long getId();

        String getControlId();

        String getPerformanceStatus();

        String getControlStatus();

        String getControlDescription();
    }

    String SUMMARY = "SELECT c.id AS id, c.controlId AS controlId, c.performanceStatus AS performanceStatus, "
            + "c.controlStatus AS controlStatus, c.controlDescription AS controlDescription FROM Control c";

    @Query(SUMMARY)
    List<ControlSummary> findAllSummaries();

    @Query(SUMMARY + " WHERE c.id IN :ids")
    List<ControlSummary> findSummariesByIds(@Param("ids") Collection<Long> ids);
}
