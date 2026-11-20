package com.kpmg.qtracker.dto;

import com.kpmg.qtracker.service.ControlStepsFields;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ControlDetailsDTO {
    private Long controlId;
    @Size(max = com.kpmg.qtracker.service.ControlTextLimits.MAX, message = "Process Name must be at most " + com.kpmg.qtracker.service.ControlTextLimits.MAX + " characters")
    private String processName;
    @Size(max = 255, message = "Homogeneity must be at most 255 characters")
    private String homogeneity;
    @Size(max = com.kpmg.qtracker.service.ControlTextLimits.MAX, message = "References to Control must be at most " + com.kpmg.qtracker.service.ControlTextLimits.MAX + " characters")
    private String referencesToControl;
    @Size(max = com.kpmg.qtracker.service.ControlTextLimits.MAX, message = "Department must be at most " + com.kpmg.qtracker.service.ControlTextLimits.MAX + " characters")
    private String department;
    @Size(max = com.kpmg.qtracker.service.ControlTextLimits.MAX, message = "Process Activities must be at most " + com.kpmg.qtracker.service.ControlTextLimits.MAX + " characters")
    private String processActivities;
    @Size(max = 255, message = "IT Applications must be at most 255 characters")
    private String itApplications;
    @Size(max = com.kpmg.qtracker.service.ControlTextLimits.MAX, message = ControlStepsFields.STEPS_LABEL + "must be at most " + com.kpmg.qtracker.service.ControlTextLimits.MAX + " characters")
    private String controlStepsPerformed;
    @Size(max = com.kpmg.qtracker.service.ControlTextLimits.MAX, message = ControlStepsFields.OPERATOR_PROGRAM_LABEL + "must be at most " + com.kpmg.qtracker.service.ControlTextLimits.MAX + " characters")
    private String controlOperatorReview;
    @Size(max = com.kpmg.qtracker.service.ControlTextLimits.MAX, message = "SoQM Admin Team Comments must be at most " + com.kpmg.qtracker.service.ControlTextLimits.MAX + " characters")
    private String soqmHeadComments;
    @Size(max = com.kpmg.qtracker.service.ControlTextLimits.MAX, message = "Process Owner Comments must be at most " + com.kpmg.qtracker.service.ControlTextLimits.MAX + " characters")
    private String processOwnerComments;

    /**
     * Other Related Controls (RelatedControls): Control IDs of the list as ValueList writes them ("A; B"); "" clears
     * them, null keeps them as stored.
     */
    private String otherRelatedControls;

    /** Why SoQM Team changes a completed control in place (CompletedEdit); sent, never returned. */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String editReason;
}
