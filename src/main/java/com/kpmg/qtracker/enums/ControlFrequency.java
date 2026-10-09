package com.kpmg.qtracker.enums;

import java.util.Locale;
import java.util.Optional;

public enum ControlFrequency {
    MONTHLY,
    QUARTERLY,
    RECURRING,
    AD_HOC,
    SEMI_ANNUAL,
    ANNUAL;

    /** The values the Control tab offers; any other stored spelling is read as below. */
    public static final String OFFERED = "Monthly, Quarterly, Ad-hoc, Recurring, Annual, Semi Annual";

    public static ControlFrequency fromValue(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("frequency must not be null");
        }
        return tryFromValue(raw)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported frequency: " + raw));
    }

    /**
     * As fromValue, empty for a blank or unknown value. view-control.js (normalizeControlFrequency) reads
     * the frequency the same way, in the same order, for its preview of the deadline and the next date.
     */
    public static Optional<ControlFrequency> tryFromValue(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Optional.empty();
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.contains("month")) {
            return Optional.of(MONTHLY);
        }
        if (value.contains("quarter")) {
            return Optional.of(QUARTERLY);
        }
        if (value.contains("recurr")) {
            return Optional.of(RECURRING);
        }
        if (value.contains("ad") && value.contains("hoc")) {
            return Optional.of(AD_HOC);
        }
        if (value.contains("semi")) {
            return Optional.of(SEMI_ANNUAL);
        }
        if (value.contains("annual")) {
            return Optional.of(ANNUAL);
        }
        return Optional.empty();
    }
}
