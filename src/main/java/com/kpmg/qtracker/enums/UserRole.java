package com.kpmg.qtracker.enums;

import java.util.Locale;
import java.util.Optional;

/**
 * The role a person has in QTracker, as the Admin Panel shows and sets it. It is stored as an access level
 * and scope ({@link AccessLevel}, {@link AccessScope}); the mapping is {@code AccessPolicy.Profile}.
 */
public enum UserRole {
    /** SoQM Head, Team and Delegate: sees every control, acts for any role, manages users (SOQM / ALL). */
    SOQM_TEAM("SoQM Team"),
    /** Everyone else in the firm: {@link Visibility} and {@link AccessRight} say what they see and do. */
    USER("User"),
    /** Staff of other countries: views every KDN control and nothing else (READ_ONLY / KDN). */
    KDN("KDN");

    private final String displayName;

    UserRole(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** The role for a code such as "SOQM_TEAM" or "user"; empty for a blank or unknown value. */
    public static Optional<UserRole> tryFrom(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        for (UserRole role : values()) {
            if (role.name().equals(key)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
