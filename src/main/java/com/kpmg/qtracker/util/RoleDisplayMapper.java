package com.kpmg.qtracker.util;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;

/**
 * Human-readable label of a user's access: level, the scope when it is not implied, and the admin flag
 * (e.g. "SoQM", "Participant · KDN", "Read only · All controls · Admin").
 */
public final class RoleDisplayMapper {

    private static final String SEPARATOR = " · ";

    private RoleDisplayMapper() {
    }

    public static String access(User user) {
        if (user == null) {
            return "";
        }
        return access(user.getAccessLevel(), user.getAccessScope(), Boolean.TRUE.equals(user.getAdminAccess()));
    }

    public static String access(AccessLevel level, AccessScope scope, boolean admin) {
        AccessLevel shownLevel = level != null ? level : AccessLevel.READ_ONLY;
        StringBuilder label = new StringBuilder(shownLevel.getDisplayName());
        // SoQM always sees all controls, and Own is the usual scope of everyone else
        if (shownLevel != AccessLevel.SOQM && scope == AccessScope.ALL) {
            label.append(SEPARATOR).append("All controls");
        } else if (shownLevel != AccessLevel.SOQM && scope == AccessScope.KDN) {
            label.append(SEPARATOR).append("KDN");
        }
        if (admin) {
            label.append(SEPARATOR).append("Admin");
        }
        return label.toString();
    }
}
