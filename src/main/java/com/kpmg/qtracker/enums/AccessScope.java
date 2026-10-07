package com.kpmg.qtracker.enums;

import java.util.Locale;
import java.util.Optional;

/** Which controls a user sees (users.access_scope). What they may do there is {@link AccessLevel}. */
public enum AccessScope {
    /** The controls they are assigned to, that are shared with them or that they created. */
    OWN("My controls"),
    /** Every control, drafts included. */
    ALL("All controls"),
    /** Every KDN control (Control ID starting with KDN) and no other ({@link com.kpmg.qtracker.service.AccessPolicy#isKdnControl}). */
    KDN("KDN controls");

    private final String displayName;

    AccessScope(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** The scope for a code such as "ALL" or "own"; empty for a blank or unknown value. */
    public static Optional<AccessScope> tryFrom(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        for (AccessScope scope : values()) {
            if (scope.name().equals(key)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }
}
