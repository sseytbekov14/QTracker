package com.kpmg.qtracker.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Data
public class ControlResponseDTO {
    private static final Set<String> WORKFLOW_STATUSES = Set.of(
            "DRAFT",
            "IN_PROGRESS",
            "REVIEW",
            "SOQM_HEAD_REVIEW",
            "PROCESS_OWNER_REVIEW",
            "COMPLETED"
    );
    private Long id;
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
    private String controlDescription;
    private String prp;
    private String soqmHeadComments;
    private String processOwnerComments;
    private String createdBy;
        private String createdByEmail;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate deadline;
    private String performanceStatus;
    private String performanceStatusDisplay;
    private boolean performanceInitiated;
    private boolean goToPerformanceCycle;
    private boolean overdue;
    private boolean sharedViewOnly;
    // Workflow fields
    private String workflowStatus;
    private String workflowStatusDisplay;
    private List<WorkflowHistoryDTO> workflowHistory;
    private boolean canInitiateWorkflow;
    private boolean canSubmitForReview;
    private boolean canSubmitForSoQM;
    private boolean canSendToProcessOwner;
    private boolean canCompleteWorkflow;
    private boolean canReturnWorkflow;

    private List<String> facilitators;
    private List<String> controlOperators;
    private List<String> processOwners;
    private List<String> soqmLeads;

    private List<UserDTO> facilitatorUsers;
    private List<UserDTO> controlOperatorUsers;
    private List<UserDTO> processOwnerUsers;
    private List<UserDTO> soqmLeadUsers;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate controlOperationDate;

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
