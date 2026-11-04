package com.kpmg.qtracker.util;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessRight;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.enums.UserRole;
import com.kpmg.qtracker.enums.Visibility;
import com.kpmg.qtracker.service.AccessPolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The words people see for a user's access, in one place: the role, the summary on one line
 * ("SoQM Team", "User · My controls · Edit", "KDN · All KDN controls · Read Only"), the Admin Panel's Access lines
 * without the role ("Full access", "My controls · Edit"), and the dialog's Permissions texts. The rules themselves
 * are {@link AccessPolicy}; Facilitator, Control Operator and Process Owner are the Control roles of a control, not
 * user roles.
 */
public final class RoleDisplayMapper {

    public static final String SEPARATOR = " · ";

    /** The Visibility the role KDN fixes: every KDN control ({@link AccessPolicy#isKdnControl}). */
    public static final String KDN_VISIBILITY = AccessScope.KDN.getDisplayName();

    /** The access of SoQM Admin Team in the Admin Panel's Access: every control, every change. */
    public static final String FULL_ACCESS = "Full access";

    /** {@link #FULL_ACCESS} in the Access filter. */
    private static final String FULL_ACCESS_KEY = "FULL";

    /** What each fixed value is and why it cannot be changed, for SoQM Team and KDN. */
    public record FixedValue(String label, String value, String reason) {
    }

    private RoleDisplayMapper() {
    }

    /** "SoQM Team", "User · My controls · Edit", "KDN · All KDN controls · Read Only"; empty for no user. */
    public static String access(User user) {
        return user == null ? "" : summary(AccessPolicy.Profile.of(user));
    }

    public static String access(AccessLevel level, AccessScope scope) {
        return summary(AccessPolicy.Profile.of(level, scope));
    }

    public static String summary(AccessPolicy.Profile profile) {
        if (profile.role() == UserRole.KDN) {
            return profile.role().getDisplayName() + SEPARATOR + KDN_VISIBILITY
                    + SEPARATOR + AccessRight.READ_ONLY.getDisplayName();
        }
        if (profile.role() != UserRole.USER) {
            return profile.role().getDisplayName();
        }
        return profile.role().getDisplayName() + SEPARATOR + profile.visibility().getDisplayName()
                + SEPARATOR + profile.access().getDisplayName();
    }

    /**
     * The Admin Panel's Access: one line per access, "area · level", without the role (the Role column and field
     * show it): "Full access", "My controls · Edit", "All KDN controls · Read Only". Each user has one access today.
     */
    public static List<String> accessLines(AccessPolicy.Profile profile) {
        return switch (profile.role()) {
            case SOQM_TEAM -> List.of(FULL_ACCESS);
            case KDN -> List.of(KDN_VISIBILITY + SEPARATOR + AccessRight.READ_ONLY.getDisplayName());
            case USER -> List.of(profile.visibility().getDisplayName() + SEPARATOR + profile.access().getDisplayName());
        };
    }

    /**
     * The Admin Panel's Visibility filter, by the area of the Access lines: every user has one, SoQM Admin Team
     * counts as All controls (they see every control), KDN as All KDN controls.
     */
    public static Map<String, String> visibilityFilter() {
        Map<String, String> options = new LinkedHashMap<>();
        for (AccessScope scope : AccessScope.values()) {
            options.put(scope.name(), scope.getDisplayName());
        }
        return options;
    }

    /** The filter key of a user's area ({@link #visibilityFilter}). */
    public static String visibilityKey(AccessPolicy.Profile profile) {
        return profile.scope().name();
    }

    /** The Admin Panel's Access filter, by the level of the Access lines: Full access, Edit, Read Only (KDN too). */
    public static Map<String, String> accessFilter() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put(FULL_ACCESS_KEY, FULL_ACCESS);
        for (AccessRight access : AccessRight.values()) {
            options.put(access.name(), access.getDisplayName());
        }
        return options;
    }

    /** The filter key of a user's level ({@link #accessFilter}). */
    public static String accessKey(AccessPolicy.Profile profile) {
        return switch (profile.role()) {
            case SOQM_TEAM -> FULL_ACCESS_KEY;
            case KDN -> AccessRight.READ_ONLY.name();
            case USER -> profile.access().name();
        };
    }

    /** The role alone ("SoQM Team", "User", "KDN"), as the sidebar shows it. */
    public static String role(User user) {
        return user == null ? "" : AccessPolicy.Profile.of(user).role().getDisplayName();
    }

    /** The hint at a Control ID field (new control, Rename ID). */
    public static final String KDN_ID_HINT = "IDs starting with KDN are visible to KDN users";

    /** The hint at Control Shared With: what a place there gives ({@link AccessPolicy.SharedAccess#VIEWS}). */
    public static final String SHARED_WITH_HINT = "Can view this control and download files, no editing";

    /** Control Shared With when it holds nobody. */
    public static final String SHARED_WITH_EMPTY = "Not shared with anyone";

    /** The mark at a person in Control Shared With, or null when the hint at the field says it all. */
    public static String sharedNote(AccessPolicy.SharedAccess access) {
        if (access == null) {
            return null;
        }
        return switch (access) {
            case VIEWS -> null;
            case AFTER_INITIATION -> "Will see it once the control is initiated";
            case NOT_SEEN -> "Will not see this control";
            case DISABLED -> "Disabled";
            case NOT_A_USER -> "Not in the system";
        };
    }

    /**
     * "Permissions" in the Admin Panel's user dialog, one short text per role and, for a User, per Access (what they
     * see is the Visibility next to it). The words follow {@link AccessPolicy}; RoleDisplayMapperTest checks them
     * against it.
     */
    public static final String PERMISSIONS_SOQM_TEAM = "Manages all controls and users. Completes or returns steps"
            + " on behalf of any role. Exports data and accesses the Admin Panel.";
    public static final String PERMISSIONS_USER_EDIT = "Completes steps and edits controls assigned to them.";
    public static final String PERMISSIONS_USER_READ_ONLY = "Views controls. No editing.";
    public static final String PERMISSIONS_KDN = "Views KDN controls and downloads files. No editing.";

    /** The "Permissions" text of a role and Access ({@link #PERMISSIONS_SOQM_TEAM} and the others). */
    public static String permissions(AccessPolicy.Profile profile) {
        return switch (profile.role()) {
            case SOQM_TEAM -> PERMISSIONS_SOQM_TEAM;
            case KDN -> PERMISSIONS_KDN;
            case USER -> profile.access() == AccessRight.EDIT ? PERMISSIONS_USER_EDIT : PERMISSIONS_USER_READ_ONLY;
        };
    }

    /** The Visibility and Access a role fixes, with the reason; empty for User, who chooses both. */
    public static List<FixedValue> fixedValues(UserRole role) {
        return switch (role) {
            case SOQM_TEAM -> List.of(
                    new FixedValue("Visibility", Visibility.ALL.getDisplayName(), "SoQM Admin Team always sees all controls."),
                    new FixedValue("Access", FULL_ACCESS, "SoQM Admin Team edits every control."));
            case KDN -> List.of(
                    new FixedValue("Visibility", KDN_VISIBILITY, "Fixed for KDN: every KDN control, no other."),
                    new FixedValue("Access", AccessRight.READ_ONLY.getDisplayName(), "Fixed for KDN: view and download only."));
            case USER -> List.of();
        };
    }

    /** The Access lines of SoQM Team and KDN by role, for the Admin Panel script (a User's are built from the choices). */
    public static Map<String, List<String>> roleAccessLines() {
        Map<String, List<String>> lines = new LinkedHashMap<>();
        lines.put(UserRole.SOQM_TEAM.name(), accessLines(AccessPolicy.Profile.soqmTeam()));
        lines.put(UserRole.KDN.name(), accessLines(AccessPolicy.Profile.kdn()));
        return lines;
    }

    /** Every "Permissions" text by key "ROLE" or "USER/ACCESS", for the Admin Panel script. */
    public static Map<String, String> permissionsByKey() {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put(UserRole.SOQM_TEAM.name(), permissions(AccessPolicy.Profile.soqmTeam()));
        for (AccessRight access : AccessRight.values()) {
            texts.put(UserRole.USER.name() + "/" + access.name(), permissions(AccessPolicy.Profile.user(Visibility.MY, access)));
        }
        texts.put(UserRole.KDN.name(), permissions(AccessPolicy.Profile.kdn()));
        return texts;
    }
}
