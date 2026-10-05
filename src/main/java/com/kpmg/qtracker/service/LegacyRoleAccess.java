package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;

import java.util.Locale;
import java.util.Set;

/**
 * The access level and scope for an old role: the users.role / secondary_role values of QTracker and the
 * Role values of the Power Apps user list QT_Users, which the user import maps with it:
 * <ul>
 *   <li>SoQM Team, SoQM Head (and any other SOQM role) - SOQM / ALL;</li>
 *   <li>Master - PARTICIPANT / ALL;</li>
 *   <li>Facilitator, Control Operator, Process Owner - PARTICIPANT / OWN;</li>
 *   <li>KDN, in either role column - PARTICIPANT / KDN;</li>
 *   <li>ADMIN (the seed accounts' role string) - PARTICIPANT / ALL;</li>
 *   <li>Read Only, a blank or unknown role - READ_ONLY / OWN.</li>
 * </ul>
 * The same rules as the V6 migration backfill; the first that matches wins. The admin_access flag is not
 * a role and is never derived from one: it is given by hand in the Admin Panel.
 */
public final class LegacyRoleAccess {

    private static final Set<String> PARTICIPANT_ROLES = Set.of("FACILITATOR", "CONTROL_OPERATOR", "PROCESS_OWNER");
    private static final Set<String> PARTICIPANT_ALL_ROLES = Set.of("ADMIN", "MASTER");

    private LegacyRoleAccess() {
    }

    public record Access(AccessLevel level, AccessScope scope) {
    }

    public static Access of(String role, String secondaryRole) {
        String primary = key(role);
        if ("KDN".equals(primary) || "KDN".equals(key(secondaryRole))) {
            return new Access(AccessLevel.PARTICIPANT, AccessScope.KDN);
        }
        if (primary.startsWith("SOQM")) {
            return new Access(AccessLevel.SOQM, AccessScope.ALL);
        }
        if (PARTICIPANT_ALL_ROLES.contains(primary)) {
            return new Access(AccessLevel.PARTICIPANT, AccessScope.ALL);
        }
        if (PARTICIPANT_ROLES.contains(primary)) {
            return new Access(AccessLevel.PARTICIPANT, AccessScope.OWN);
        }
        return new Access(AccessLevel.READ_ONLY, AccessScope.OWN);
    }

    private static String key(String role) {
        return role == null ? "" : role.trim().replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
