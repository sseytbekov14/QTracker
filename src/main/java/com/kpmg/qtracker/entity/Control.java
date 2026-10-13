package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

@Entity
@Table(name = "controls")
@Data
public class Control {
    private static final Set<String> WORKFLOW_STATUSES = Set.of(
            "DRAFT",
            "IN_PROGRESS",
            "REVIEW",
            "SOQM_HEAD_REVIEW",
            "PROCESS_OWNER_REVIEW",
            "COMPLETED"
    );
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_id", unique = true)
    private String controlId;

    private String controlFrequency;
    private String controlCategory;
    private String controlType;
    private String component;
    private String operatedBy;
    private String referencesToControl;
    private String priority;
    private String nonAuditServicesApplicability;
    private String homogeneity;
    private String controlStatus;
    @Column(name = "performance_status")
    private String performanceStatus;

    @Column(length = 1000)
    private String controlDescription;

    @Column(length = 1000)
    private String prp;
    
    
    @Column(name = "soqm_head_comments", length = 2000)
    private String soqmHeadComments;
    
    @Column(name = "process_owner_comments", length = 2000)
    private String processOwnerComments;
    
    @ManyToOne
    @JoinColumn(name = "created_by")
    private User createdBy;

    /**
     * Who created the control, as text (V12): kept when the creator is deleted from QTracker (createdBy is then
     * null), so the control still names them, "Name (deleted user)" ({@link #creatorName}).
     */
    @Column(name = "created_by_email")
    private String createdByEmail;

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deadline")
    private LocalDate deadline;

    /**
     * Read only here: the column belongs to ControlAssignment (the same row of table controls). A Control loaded
     * before an Assignment save and saved after it (its deadline) used to write the old date back over the new one.
     */
    @Column(insertable = false, updatable = false)
    private LocalDate controlOperationDate;

    @Column(name = "attachment_details_path", columnDefinition = "TEXT")
    private String attachmentDetailsPath;

    @Column(name = "attachment_documents_path", columnDefinition = "TEXT")
    private String attachmentDocumentsPath;

    @Column(name = "soqm_year")
    private String soqmYear;

    @Column(name = "return_to_facilitator_comment", length = 2000)
    private String returnToFacilitatorComment;

    @Column(name = "return_to_operator_comment", length = 2000)
    private String returnToOperatorComment;

    @Column(name = "return_to_soqm_team_comment", length = 2000)
    private String returnToSoqmTeamComment;

    /**
     * When SoQM last returned the control from Completed (business decision 4); null once it is completed
     * again. While set, no overdue notice goes out for it: its deadline is not changed.
     */
    @Column(name = "reopened_at")
    private LocalDateTime reopenedAt;

    @PrePersist
    @PreUpdate
    private void ensurePerformanceStatus() {
        if (performanceStatus == null || performanceStatus.isBlank()) {
            performanceStatus = "DRAFT";
        }
        // The creator as text, while the creator's row is there
        if (createdBy != null && (createdByEmail == null || createdByEmail.isBlank())) {
            createdByEmail = createdBy.getMail();
            createdByName = createdBy.getDisplayName();
        }
    }

    /** Suffix of a person who was deleted from QTracker, wherever their name or e-mail is still shown. */
    public static final String DELETED_USER_MARK = " (deleted user)";

    /** The creator's name: the user's, or the one kept as text with "(deleted user)"; null when not known. */
    public String creatorName() {
        if (createdBy != null) {
            return createdBy.getDisplayName();
        }
        String kept = createdByName != null && !createdByName.isBlank() ? createdByName : createdByEmail;
        return kept == null || kept.isBlank() ? null : kept.trim() + DELETED_USER_MARK;
    }

    /** The creator's e-mail: the user's, or the one kept as text; null when not known. */
    public String creatorEmail() {
        return createdBy != null ? createdBy.getMail() : createdByEmail;
    }

    public String getPerformanceStatus() {
        if (performanceStatus != null && !performanceStatus.isBlank()) {
            return performanceStatus;
        }
        if (controlStatus == null || controlStatus.isBlank()) {
            return performanceStatus;
        }
        String normalized = controlStatus.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return WORKFLOW_STATUSES.contains(normalized) ? normalized : performanceStatus;
    }
}
