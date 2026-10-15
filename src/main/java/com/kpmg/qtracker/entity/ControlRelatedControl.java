package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One link of Other Related Controls (Details tab): the control relatedControlId is linked from controlId, at its
 * place in the list. Both are controls.id, so a Rename ID keeps the link (V15).
 */
@Entity
@Table(name = "control_related_controls",
        uniqueConstraints = @UniqueConstraint(name = "uq_control_related_controls",
                columnNames = {"control_id", "related_control_id"}))
@Data
@NoArgsConstructor
public class ControlRelatedControl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_id", nullable = false)
    private Long controlId;

    @Column(name = "related_control_id", nullable = false)
    private Long relatedControlId;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    public ControlRelatedControl(Long controlId, Long relatedControlId, int sortOrder) {
        this.controlId = controlId;
        this.relatedControlId = relatedControlId;
        this.sortOrder = sortOrder;
    }
}
