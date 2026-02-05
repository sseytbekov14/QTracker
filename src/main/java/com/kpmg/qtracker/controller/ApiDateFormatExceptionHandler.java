package com.kpmg.qtracker.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
@Slf4j
public class ApiDateFormatExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        String message = ex.getMostSpecificCause() != null
                ? ex.getMostSpecificCause().getMessage()
                : ex.getMessage();
        if (message != null && (message.contains("LocalDate") || message.contains("yyyy-MM-dd"))) {
            log.warn("Rejected non-ISO date payload: {}", message);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "Invalid date format. Expected yyyy-MM-dd"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("success", false, "message", "Invalid request payload"));
    }
}
