package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.ErrorResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        ErrorResponse response = new ErrorResponse(
                "ACCESS_DENIED",
                "Access denied",
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        String message = details.isBlank() ? "Validation failed" : "Validation failed: " + details;
        ErrorResponse response = new ErrorResponse(
                "VALIDATION_ERROR",
                message,
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    private String formatFieldError(FieldError fieldError) {
        String field = fieldError.getField();
        String error = fieldError.getDefaultMessage();
        return field + " " + (error == null ? "is invalid" : error);
    }

    private String getCorrelationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null ? "N/A" : correlationId;
    }
}
