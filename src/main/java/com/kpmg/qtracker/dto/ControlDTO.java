package com.kpmg.qtracker.dto;
import lombok.Data;
import java.util.List;

@Data
public class ControlDTO {
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
    private String createdByEmail;
    
    // Assignment fields - can be set when creating a control
    private List<String> facilitator;
    private List<String> controlOperator;
    private List<String> soqmLead;
    private List<String> processOwner;
}