package com.kpmg.qtracker.dto;

import lombok.Data;

@Data
public class ControlDetailsDTO {
    private Long controlId;
    private String processName;
    private String homogeneity;
    private String referencesToControl;
    private String department;
    private String processActivities;
    private String otherRelatedControls;
    private String itApplications;
    private String controlStepsPerformed;
    private String soqmHeadComments;
    private String processOwnerComments;
}
