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


    // ---- The rich-text fields (2026-11-23): every value cleaned as it is set (RichText.clean), whoever sets it ----

    public void setProcessName(String processName) {
        this.processName = com.kpmg.qtracker.service.RichText.clean(processName);
    }

    public void setReferencesToControl(String referencesToControl) {
        this.referencesToControl = com.kpmg.qtracker.service.RichText.clean(referencesToControl);
    }

    public void setDepartment(String department) {
        this.department = com.kpmg.qtracker.service.RichText.clean(department);
    }

    public void setProcessActivities(String processActivities) {
        this.processActivities = com.kpmg.qtracker.service.RichText.clean(processActivities);
    }

    public void setControlStepsPerformed(String controlStepsPerformed) {
        this.controlStepsPerformed = com.kpmg.qtracker.service.RichText.clean(controlStepsPerformed);
    }

    public void setControlOperatorReview(String controlOperatorReview) {
        this.controlOperatorReview = com.kpmg.qtracker.service.RichText.clean(controlOperatorReview);
    }

    public void setSoqmHeadComments(String soqmHeadComments) {
        this.soqmHeadComments = com.kpmg.qtracker.service.RichText.clean(soqmHeadComments);
    }

    public void setProcessOwnerComments(String processOwnerComments) {
        this.processOwnerComments = com.kpmg.qtracker.service.RichText.clean(processOwnerComments);
    }
}
