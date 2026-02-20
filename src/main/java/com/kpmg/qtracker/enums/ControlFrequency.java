package com.kpmg.qtracker.enums;

import java.util.Locale;

public enum ControlFrequency {
    MONTHLY,
    QUARTERLY,
    RECURRING,
    AD_HOC,
    SEMI_ANNUAL,
    ANNUAL;

    public static ControlFrequency fromValue(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("frequency must not be null");
        }

        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.contains("month")) {
            return MONTHLY;
        }
        if (value.contains("quarter")) {
            return QUARTERLY;
        }
        if (value.contains("recurr")) {
            return RECURRING;
        }
        if (value.contains("ad") && value.contains("hoc")) {
            return AD_HOC;
        }
        if (value.contains("semi")) {
            return SEMI_ANNUAL;
        }
        if (value.contains("annual")) {
            return ANNUAL;
        }
        throw new IllegalArgumentException("Unsupported frequency: " + raw);
    }
}
