package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.RelatedControlRef;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface RelatedControlRefRepository extends JpaRepository<RelatedControlRef, Long> {

    /** The active Control IDs whose text holds the pattern (lower case, LIKE with "\" as escape), by Control ID. */
    @Query("SELECT r.controlId FROM RelatedControlRef r WHERE r.active = true AND LOWER(r.controlId) LIKE :pattern "
            + "ESCAPE '\\' ORDER BY LOWER(r.controlId), r.controlId")
    List<String> searchActive(@Param("pattern") String pattern, Pageable page);

    List<RelatedControlRef> findByActiveTrueAndControlKeyIn(Collection<String> keys);

    boolean existsByActiveTrue();
}
