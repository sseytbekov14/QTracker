package com.kpmg.qtracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ErrorResponse {
    private String code;
    private String message;
    private String correlationId;
    /** Per-field validation errors, e.g. {"controlDescription": "must be at most 1000 characters"} */
    private Map<String, String> fieldErrors;

    /** Backward-compatible constructor (no field errors). */
    public ErrorResponse(String code, String message, String correlationId) {
        this.code = code;
        this.message = message;
        this.correlationId = correlationId;
    }
}
