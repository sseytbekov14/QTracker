package com.kpmg.qtracker.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ControlDocumentsDTO {
    private Long controlId;
    private String soqmDevelopmentMaterials;

    /** Why SoQM Team changes a completed control in place (CompletedEdit); sent, never returned. */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String editReason;
}
