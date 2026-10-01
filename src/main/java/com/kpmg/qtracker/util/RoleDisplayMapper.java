package com.kpmg.qtracker.util;

import java.util.Locale;
import java.util.Map;

/**
 * Human-readable labels for user roles (e.g. "SOQM_TEAM" -> "SoQM Team").
 */
public final class RoleDisplayMapper {

    private static final Map<String, String> LABELS = Map.of(
            "FACILITATOR", "Facilitator",
            "CONTROL_OPERATOR", "Control Operator",
            "SOQM_TEAM", "SoQM Team",
            "PROCESS_OWNER", "Process Owner",
            "ADMIN", "Administrator",
            "KDN", "KDN"
    );

    private RoleDisplayMapper() {
    }

    public static String display(String role) {
        if (role == null || role.isBlank()) {
            return "";
        }
        String normalized = role.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        return LABELS.getOrDefault(normalized, role.trim());
    }
}
