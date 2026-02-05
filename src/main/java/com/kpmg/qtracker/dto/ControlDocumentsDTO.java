package com.kpmg.qtracker.dto;

import lombok.Data;

@Data
public class ControlDocumentsDTO {
    private Long controlId;
    private String link;
    private String attachment;
    private String soqmDevelopmentMaterials;
}