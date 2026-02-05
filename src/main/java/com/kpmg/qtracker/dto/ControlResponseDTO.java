package com.kpmg.qtracker.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;

@Data
public class ControlResponseDTO {
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
    private String controlOperatorsProgram;
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
}
