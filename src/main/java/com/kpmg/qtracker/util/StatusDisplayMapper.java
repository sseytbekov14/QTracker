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
            "SOQM_HEAD_REVIEW", "SoQM Admin Team Review",
            "PROCESS_OWNER_REVIEW", "Process Owner Review",
            "COMPLETED", "Completed"
    );

    private static final java.util.regex.Pattern EARLIER_NAMES =
            java.util.regex.Pattern.compile("SoQM Head Review", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * A text saved under an earlier name of a status - an audit entry "Move to SoQM Head Review: Review -> SoQM Head
     * Review" written before 2026-11-10 - shown under today's name. The stored text itself is not changed.
     */
    public static String withCurrentNames(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return EARLIER_NAMES.matcher(text)
                .replaceAll(java.util.regex.Matcher.quoteReplacement(DISPLAY_MAP.get("SOQM_HEAD_REVIEW")));
    }

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
