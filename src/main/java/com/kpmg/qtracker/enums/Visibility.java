package com.kpmg.qtracker.enums;

import java.util.Locale;
import java.util.Optional;

/** Which controls a User sees (stored as {@link AccessScope} OWN or ALL). */
public enum Visibility {
    /** The controls they are assigned to, shared with or created. */
    MY("My controls"),
    /** Every control, drafts included. */
    ALL("All controls");

    private final String displayName;

    Visibility(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** The visibility for a code such as "MY" or "all"; empty for a blank or unknown value. */
    public static Optional<Visibility> tryFrom(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        for (Visibility visibility : values()) {
            if (visibility.name().equals(key)) {
                return Optional.of(visibility);
            }
        }
        return Optional.empty();
    }
}
