package com.kpmg.qtracker.enums;

import java.util.Locale;
import java.util.Optional;

/**
 * What a User may do on the controls they see (stored as {@link AccessLevel} PARTICIPANT or READ_ONLY).
 * Edit always means: performs the steps and edits only the controls they are assigned to.
 */
public enum AccessRight {
    EDIT("Edit"),
    READ_ONLY("Read Only");

    private final String displayName;

    AccessRight(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** The access for a code such as "EDIT" or "read-only"; empty for a blank or unknown value. */
    public static Optional<AccessRight> tryFrom(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        for (AccessRight right : values()) {
            if (right.name().equals(key)) {
                return Optional.of(right);
            }
        }
        return Optional.empty();
    }
}
