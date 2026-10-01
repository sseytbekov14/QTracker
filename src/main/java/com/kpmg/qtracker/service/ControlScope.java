package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.User;

import java.util.Locale;

/**
 * Whose controls a user's lists and dashboard numbers cover. Admins (admin access or the ADMIN role)
 * and every SoQM role see all controls, drafts included; everyone else sees the controls they work on
 * or that are shared with them ({@link IControlService#findVisibleControlsForUser}), without drafts.
 */
public final class ControlScope {

    private ControlScope() {
    }

    public static boolean seesAllControls(User user) {
        return user != null && seesAllControls(user.getRole(), Boolean.TRUE.equals(user.getAdminAccess()));
    }

    public static boolean seesAllControls(String role, boolean adminAccess) {
        if (adminAccess) {
            return true;
        }
        if (role == null) {
            return false;
        }
        String normalized = role.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return "ADMIN".equals(normalized) || normalized.startsWith("SOQM");
    }
}
