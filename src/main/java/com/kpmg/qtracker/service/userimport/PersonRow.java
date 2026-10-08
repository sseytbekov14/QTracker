package com.kpmg.qtracker.service.userimport;

import com.kpmg.qtracker.enums.AccessRight;
import com.kpmg.qtracker.enums.Visibility;
import com.kpmg.qtracker.service.AccessPolicy;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One line of the people file in QTracker terms: the display name (column User), the role with Visibility
 * and Access, and whether the account is active. Service accounts (svc, IT tester) are recognised and kept
 * apart; values the import does not know are an error with the reason, never a guess.
 */
public record PersonRow(int line, Kind kind, String displayName, AccessPolicy.Profile profile, boolean enabled,
                        String error) {

    public enum Kind {
        PERSON, SERVICE_ACCOUNT, ERROR
    }

    public static final String SOQM_TEAM = "SoQM Team";
    public static final String USER = "User";
    public static final String KDN = "KDN";
    public static final String KDN_ACCESS = "All KDN Controls, Read Only";
    public static final String ACTIVE = "Active";

    private static final Pattern SERVICE = Pattern.compile("(?i)(^|[^a-z])svc([^a-z]|$)|\\bit[\\s_-]*tester\\b");
    private static final int SHOWN_VALUE = 40;

    public static PersonRow of(UsersCsv.Record record) {
        String displayName = record.get(UsersCsv.USER);
        if (SERVICE.matcher(record.get(UsersCsv.NAME)).find() || SERVICE.matcher(displayName).find()) {
            return new PersonRow(record.line(), Kind.SERVICE_ACCOUNT, displayName, null, false,
                    "service account (svc / IT tester), not imported");
        }
        if (record.error() != null) {
            return error(record, displayName, record.error());
        }
        if (displayName.isEmpty()) {
            return error(record, displayName, "empty User");
        }
        Profile profile = profile(record.get(UsersCsv.ROLE), record.get(UsersCsv.ACCESS));
        if (profile.error() != null) {
            return error(record, displayName, profile.error());
        }
        boolean enabled = same(record.get(UsersCsv.STATUS), ACTIVE);
        return new PersonRow(record.line(), Kind.PERSON, displayName, profile.profile(), enabled, null);
    }

    /** A profile, or why the Role and Access of a line give none. */
    public record Profile(AccessPolicy.Profile profile, String error) {

        static Profile of(AccessPolicy.Profile profile) {
            return new Profile(profile, null);
        }

        static Profile refused(String error) {
            return new Profile(null, error);
        }
    }

    /**
     * Role SoQM Team (Access empty or "SoQM Team"), KDN (Access empty or "All KDN Controls, Read Only") or User
     * with Access "<My Controls|All Controls>, <Edit|Read Only>"; case and extra spaces do not count.
     */
    public static Profile profile(String roleValue, String accessValue) {
        String role = roleValue == null ? "" : roleValue;
        String access = accessValue == null ? "" : accessValue;
        if (same(role, SOQM_TEAM)) {
            return access.isBlank() || same(access, SOQM_TEAM) ? Profile.of(AccessPolicy.Profile.soqmTeam())
                    : Profile.refused("Access " + shown(access) + " does not fit the role SoQM Team");
        }
        if (same(role, KDN)) {
            return access.isBlank() || same(access, KDN_ACCESS) ? Profile.of(AccessPolicy.Profile.kdn())
                    : Profile.refused("Access " + shown(access) + " does not fit the role KDN (expected \""
                    + KDN_ACCESS + "\")");
        }
        if (same(role, USER)) {
            String[] parts = access.split(",", -1);
            if (parts.length != 2) {
                return Profile.refused("Access " + shown(access)
                        + " for the role User is not \"<My Controls|All Controls>, <Edit|Read Only>\"");
            }
            Optional<Visibility> visibility = visibility(parts[0]);
            Optional<AccessRight> right = right(parts[1]);
            if (visibility.isEmpty()) {
                return Profile.refused("unknown visibility " + shown(parts[0]) + " (My Controls or All Controls)");
            }
            if (right.isEmpty()) {
                return Profile.refused("unknown access " + shown(parts[1]) + " (Edit or Read Only)");
            }
            return Profile.of(AccessPolicy.Profile.user(visibility.get(), right.get()));
        }
        return Profile.refused(role.isBlank() ? "empty Role"
                : "unknown Role " + shown(role) + " (SoQM Team, User or KDN)");
    }

    private static Optional<Visibility> visibility(String value) {
        if (same(value, "My Controls")) {
            return Optional.of(Visibility.MY);
        }
        if (same(value, "All Controls")) {
            return Optional.of(Visibility.ALL);
        }
        return Optional.empty();
    }

    private static Optional<AccessRight> right(String value) {
        for (AccessRight right : AccessRight.values()) {
            if (same(value, right.getDisplayName())) {
                return Optional.of(right);
            }
        }
        return Optional.empty();
    }

    static boolean same(String value, String expected) {
        return key(value).equals(key(expected));
    }

    private static String key(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String shown(String value) {
        String stripped = value.strip();
        return "\"" + (stripped.length() > SHOWN_VALUE ? stripped.substring(0, SHOWN_VALUE) + "…" : stripped) + "\"";
    }

    private static PersonRow error(UsersCsv.Record record, String displayName, String error) {
        return new PersonRow(record.line(), Kind.ERROR, displayName, null, false, error);
    }
}
