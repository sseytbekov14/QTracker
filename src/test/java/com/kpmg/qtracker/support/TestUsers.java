package com.kpmg.qtracker.support;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.service.LegacyRoleAccess;

/** Test users shaped like the database after V6. */
public final class TestUsers {

    private TestUsers() {
    }

    /** Gives the user an old role and the access level and scope V6 derives from it. */
    public static User withRole(User user, String role) {
        user.setRole(role);
        LegacyRoleAccess.Access access = LegacyRoleAccess.of(role, user.getSecondaryRole());
        user.setAccessLevel(access.level());
        user.setAccessScope(access.scope());
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
