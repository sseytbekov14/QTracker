package com.kpmg.qtracker.dto;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

@Data
public class ControlDTO {
    private Long id;
    @Size(max = 255, message = "Control ID must be at most 255 characters")
    private String controlId;
    @Size(max = 255, message = "Control Frequency must be at most 255 characters")
    private String controlFrequency;
    @Size(max = 255, message = "Control Category must be at most 255 characters")
    private String controlCategory;
    @Size(max = 255, message = "Control Type must be at most 255 characters")
    private String controlType;
    @Size(max = 255, message = "Component must be at most 255 characters")
    private String component;
    @Size(max = 255, message = "Operated By must be at most 255 characters")
    private String operatedBy;
    @Size(max = 255, message = "References to Control must be at most 255 characters")
    private String referencesToControl;
    @Size(max = 255, message = "Priority must be at most 255 characters")
    private String priority;
    @Size(max = 255, message = "Non-Audit Services Applicability must be at most 255 characters")
    private String nonAuditServicesApplicability;
    @Size(max = 255, message = "Homogeneity must be at most 255 characters")
    private String homogeneity;
    @Size(max = 255, message = "Control Status must be at most 255 characters")
    private String controlStatus;
    @Size(max = 1000, message = "Control Description must be at most 1000 characters")
    private String controlDescription;
    @Size(max = 1000, message = "PRP must be at most 1000 characters")
    private String prp;
    @Size(max = 2000, message = "SoQM Head Comments must be at most 2000 characters")
    private String soqmHeadComments;
    @Size(max = 2000, message = "Process Owner Comments must be at most 2000 characters")
    private String processOwnerComments;
    private String createdByEmail;
    
    // Assignment fields - can be set when creating a control
    private List<String> facilitator;
    private List<String> controlOperator;
    private List<String> soqmLead;
    private List<String> processOwner;
}
