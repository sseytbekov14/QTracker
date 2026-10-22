package com.kpmg.qtracker.support;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;

import java.util.Locale;
import java.util.Set;

/** Test users shaped like the database after V6. */
public final class TestUsers {

    private static final Set<String> PARTICIPANT_ROLES = Set.of("FACILITATOR", "CONTROL_OPERATOR", "PROCESS_OWNER");

    private TestUsers() {
    }

    /**
     * Gives the user the access level and scope V6 (with KDN read-only as V8 makes it) derived from an old role word:
     * SOQM* - SOQM / ALL, KDN - READ_ONLY / KDN, ADMIN - PARTICIPANT / ALL, Facilitator, Control Operator, Process
     * Owner - PARTICIPANT / OWN, anything else - READ_ONLY / OWN. The word itself is not stored (users.role is gone).
     */
    public static User withRole(User user, String role) {
        String key = role == null ? "" : role.trim().replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        if ("KDN".equals(key)) {
            return withAccess(user, AccessLevel.READ_ONLY, AccessScope.KDN);
        }
        if (key.startsWith("SOQM")) {
            return withAccess(user, AccessLevel.SOQM, AccessScope.ALL);
        }
        if ("ADMIN".equals(key)) {
            return withAccess(user, AccessLevel.PARTICIPANT, AccessScope.ALL);
        }
        if (PARTICIPANT_ROLES.contains(key)) {
            return withAccess(user, AccessLevel.PARTICIPANT, AccessScope.OWN);
        }
        return withAccess(user, AccessLevel.READ_ONLY, AccessScope.OWN);
    }

    private static User withAccess(User user, AccessLevel level, AccessScope scope) {
        user.setAccessLevel(level);
        user.setAccessScope(scope);
        return user;
    }

    /** An enabled user with an old role and its V6 access. */
    public static User user(String mail, String role) {
        User user = new User();
        user.setMail(mail);
        user.setDisplayName(mail);
        user.setEnabled(true);
        return withRole(user, role);
    }

    /** An enabled user with the given access and no old role. */
    public static User user(String mail, AccessLevel level, AccessScope scope, boolean admin) {
        User user = new User();
        user.setMail(mail);
        user.setDisplayName(mail);
        user.setEnabled(true);
        user.setAccessLevel(level);
        user.setAccessScope(scope);
        user.setAdminAccess(admin);
        return user;
    }
}
