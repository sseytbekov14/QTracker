package com.kpmg.qtracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ErrorResponse {
    private String code;
    private String message;
    private String correlationId;
}
