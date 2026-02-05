package com.kpmg.qtracker.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;

@Data
public class ControlAssignmentDTO {
    private Long controlId;
    private List<String> facilitator;
    private List<String> controlOperator;
    private List<String> soqmLead;
    private List<String> processOwner;
    private List<String> controlSharedWith;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate controlOperationDate;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate controlOperationDeadline;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate nextControlOperationDate;
}
