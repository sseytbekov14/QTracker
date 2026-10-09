package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.ErrorResponse;
import com.kpmg.qtracker.exception.ControlReadDeniedException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Value("${file.upload.max-file-size-mb:10}")
    private long maxFileSizeMb;

    @Value("${spring.servlet.multipart.max-request-size:100MB}")
    private DataSize maxRequestSize;

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        ErrorResponse response = new ErrorResponse(
                "ACCESS_DENIED",
                "Access denied",
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(ControlReadDeniedException.class)
    public ResponseEntity<ErrorResponse> handleControlReadDenied(ControlReadDeniedException ex) {
        String code = switch (ex.getStatus()) {
            case NOT_FOUND -> "NOT_FOUND";
            case UNAUTHORIZED -> "UNAUTHORIZED";
            default -> "ACCESS_DENIED";
        };
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorResponse(code, ex.getMessage(), getCorrelationId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(),
                    fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "is invalid");
        }

        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        String message = details.isBlank() ? "Validation failed" : "Validation failed: " + details;

        ErrorResponse response = new ErrorResponse(
                "VALIDATION_ERROR",
                message,
                getCorrelationId(),
                fieldErrors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles Jakarta Bean Validation constraint violations triggered outside of @RequestBody
     * (e.g., @RequestParam with validation annotations).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation -> {
            String fieldName = violation.getPropertyPath().toString();
            // Extract last segment of property path (e.g. "returnToFacilitator.comments" -> "comments")
            int dotIndex = fieldName.lastIndexOf('.');
            if (dotIndex >= 0) {
                fieldName = fieldName.substring(dotIndex + 1);
            }
            fieldErrors.put(fieldName, violation.getMessage());
        });

        String message = "Validation failed: " + fieldErrors.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining("; "));

        ErrorResponse response = new ErrorResponse(
                "VALIDATION_ERROR",
                message,
                getCorrelationId(),
                fieldErrors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Last resort handler for database integrity violations (e.g. value too long for column).
     * Parses PostgreSQL error messages to provide user-friendly feedback instead of raw stack traces.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        logger.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());

        String message = "Data validation error. Please check the length of all text fields and try again.";

        // Try to extract useful info from PostgreSQL error message
        // Example: "ERROR: value too long for type character varying(2000)"
        String rootMessage = ex.getMostSpecificCause().getMessage();
        if (rootMessage != null) {
            Pattern pattern = Pattern.compile("value too long for type character varying\\((\\d+)\\)");
            Matcher matcher = pattern.matcher(rootMessage);
            if (matcher.find()) {
                String limit = matcher.group(1);
                message = "Text is too long. Maximum allowed length is " + limit + " characters. "
                        + "Please shorten the text and try again.";
            }
        }

        ErrorResponse response = new ErrorResponse(
                "DATA_INTEGRITY_ERROR",
                message,
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        String message = "File is too large. Maximum file size is " + maxFileSizeMb
                + " MB and maximum total upload size is " + maxRequestSize.toMegabytes() + " MB.";
        ErrorResponse response = new ErrorResponse("FILE_TOO_LARGE", message, getCorrelationId());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
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
