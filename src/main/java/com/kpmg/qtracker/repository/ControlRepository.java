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
}