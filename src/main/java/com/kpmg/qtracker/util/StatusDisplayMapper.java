package com.kpmg.qtracker.util;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class StatusDisplayMapper {

    private static final Map<String, String> DISPLAY_MAP = Map.of(
            "DRAFT", "Draft",
            "IN_PROGRESS", "In Progress",
            "REVIEW", "Review",
            "SOQM_HEAD_REVIEW", "SoQM Head Review",
            "PROCESS_OWNER_REVIEW", "Process Owner Review",
            "COMPLETED", "Completed"
    );

    public String display(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = normalize(status);
        String mapped = DISPLAY_MAP.get(normalized);
        return mapped != null ? mapped : status;
    }

    private String normalize(String status) {
        return status.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }
}
