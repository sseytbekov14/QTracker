package com.kpmg.qtracker.enums;

import java.util.Locale;
import java.util.Optional;

/**
 * What a user may do (users.access_level). Which controls they see is {@link AccessScope}. People see it as
 * the role SoQM Team or, for a User, the Access Edit or Read Only ({@code AccessPolicy.Profile}); the display
 * names follow those words.
 */
public enum AccessLevel {
    /** SoQM Head or Delegate: creates and edits controls, assigns people, performs the SoQM steps. Always scope ALL. */
    SOQM("SoQM Team"),
    /** Performs the Facilitator, Control Operator and Process Owner steps of the controls they are assigned to. */
    PARTICIPANT("Edit"),
    /** Views, reads the history and downloads attachments; never writes. */
    READ_ONLY("Read Only");

    private final String displayName;

    AccessLevel(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** The level for a code such as "SOQM" or "read-only"; empty for a blank or unknown value. */
    public static Optional<AccessLevel> tryFrom(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        for (AccessLevel level : values()) {
            if (level.name().equals(key)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }
}
