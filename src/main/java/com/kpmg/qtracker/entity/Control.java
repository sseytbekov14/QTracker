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

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deadline")
    private LocalDate deadline;

    @Column(name = "attachment_details_path", length = 500)
    private String attachmentDetailsPath;

    @Column(name = "attachment_documents_path", length = 500)
    private String attachmentDocumentsPath;

    @Column(name = "soqm_year")
    private String soqmYear;

    @Column(name = "return_to_facilitator_comment", length = 2000)
    private String returnToFacilitatorComment;

    @Column(name = "return_to_operator_comment", length = 2000)
    private String returnToOperatorComment;

    @Column(name = "return_to_soqm_team_comment", length = 2000)
    private String returnToSoqmTeamComment;

    @PrePersist
    @PreUpdate
    private void ensurePerformanceStatus() {
        if (performanceStatus == null || performanceStatus.isBlank()) {
            performanceStatus = "DRAFT";
        }
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
