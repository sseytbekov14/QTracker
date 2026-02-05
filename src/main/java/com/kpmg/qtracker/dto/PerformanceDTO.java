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
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate actualOperationDate;
    private String assignedTo;
    private String performanceStatus = "Not Started";

    public boolean isAllFieldsCompleted() {
        return controlOperator != null && !controlOperator.trim().isEmpty() &&
                facilitator != null && !facilitator.trim().isEmpty() &&
                soqmYear != null && !soqmYear.trim().isEmpty() &&
                actualOperationDate != null;
    }
}
