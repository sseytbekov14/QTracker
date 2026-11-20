package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "controls")
@Data
public class ControlDetails {
    @Id
    @Column(name = "id")
    private Long controlId;

    // Free text up to ControlTextLimits.MAX (V31: TEXT)
    @Column(columnDefinition = "TEXT")
    private String processName;
    private String homogeneity;
    @Column(columnDefinition = "TEXT")
    private String referencesToControl;
    @Column(columnDefinition = "TEXT")
    private String department;

    @Column(columnDefinition = "TEXT")
    private String processActivities;

    /** IT Applications as ValueList writes them ("SAP; KICS"); TEXT since V18. */
    @Column(name = "it_applications", columnDefinition = "TEXT")
    private String itApplications;

    /** Other Related Controls: Control IDs as ValueList writes them (RelatedControls, V18). */
    @Column(name = "other_related_controls", columnDefinition = "TEXT")
    private String otherRelatedControls;

    @Column(columnDefinition = "TEXT")
    private String controlStepsPerformed;

    /** Control Operator's Program (ControlStepsFields.OPERATOR_PROGRAM_LABEL); the column keeps its first name. */
    @Column(name = "control_operator_review", columnDefinition = "TEXT")
    private String controlOperatorReview;

    @Column(name = "soqm_head_comments", columnDefinition = "TEXT")
    private String soqmHeadComments;

    @Column(name = "process_owner_comments", columnDefinition = "TEXT")
    private String processOwnerComments;

}
