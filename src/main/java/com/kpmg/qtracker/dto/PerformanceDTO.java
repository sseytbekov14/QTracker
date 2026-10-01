package com.kpmg.qtracker.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.LocalDate;

@Data
public class PerformanceDTO {
    private Long controlId;
    private String controlOperator;
    private String facilitator;
    private String controlFrequency;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate controlOperationDate;
    private String soqmYear;
    private String assignedTo;
    private String performanceStatus = "DRAFT";
}
