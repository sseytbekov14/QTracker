package com.kpmg.qtracker.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ControlDetailsDTO {
    private Long controlId;
    @Size(max = 255, message = "Process Name must be at most 255 characters")
    private String processName;
    @Size(max = 255, message = "Homogeneity must be at most 255 characters")
    private String homogeneity;
    @Size(max = 255, message = "References to Control must be at most 255 characters")
    private String referencesToControl;
    @Size(max = 255, message = "Department must be at most 255 characters")
    private String department;
    @Size(max = 2000, message = "Process Activities must be at most 2000 characters")
    private String processActivities;
    @Size(max = 255, message = "Other Related Controls must be at most 255 characters")
    private String otherRelatedControls;
    @Size(max = 255, message = "IT Applications must be at most 255 characters")
    private String itApplications;
    @Size(max = 2000, message = "Control Steps Performed must be at most 2000 characters")
    private String controlStepsPerformed;
    @Size(max = 2000, message = "SoQM Head Comments must be at most 2000 characters")
    private String soqmHeadComments;
    @Size(max = 2000, message = "Process Owner Comments must be at most 2000 characters")
    private String processOwnerComments;
}
