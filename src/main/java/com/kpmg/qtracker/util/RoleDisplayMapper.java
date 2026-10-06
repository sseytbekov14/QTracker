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
 * ("SoQM Team", "User · My controls · Edit", "KDN"), what each combination gives, and what SoQM Team and
 * KDN can do. The rules themselves are {@link AccessPolicy}; Facilitator, Control Operator and Process Owner
 * are the Control roles of a control, not user roles.
 */
public final class RoleDisplayMapper {

    public static final String SEPARATOR = " · ";

    /** What each fixed value is and why it cannot be changed, for SoQM Team and KDN. */
    public record FixedValue(String label, String value, String reason) {
    }

    private RoleDisplayMapper() {
    }

    /** "SoQM Team", "User · My controls · Edit", "KDN"; empty for no user. */
    public static String access(User user) {
        return user == null ? "" : summary(AccessPolicy.Profile.of(user));
    }

    public static String access(AccessLevel level, AccessScope scope) {
        return summary(AccessPolicy.Profile.of(level, scope));
    }

    public static String summary(AccessPolicy.Profile profile) {
        if (profile.role() != UserRole.USER) {
            return profile.role().getDisplayName();
        }
        return profile.role().getDisplayName() + SEPARATOR + profile.visibility().getDisplayName()
                + SEPARATOR + profile.access().getDisplayName();
    }

    /** The role alone ("SoQM Team", "User", "KDN"), as the sidebar shows it. */
    public static String role(User user) {
        return user == null ? "" : AccessPolicy.Profile.of(user).role().getDisplayName();
    }

    /** What the combination gives, in one or two sentences. */
    public static String hint(AccessPolicy.Profile profile) {
        return switch (profile.role()) {
            case SOQM_TEAM -> "Sees every control, drafts included; edits any control, assigns people and acts for"
                    + " any Control role; manages users in the Admin Panel.";
            case KDN -> "Sees only the KDN controls they are assigned to, shared with or created; views and"
                    + " downloads only.";
            case USER -> sees(profile.visibility()) + " " + does(profile.access());
        };
    }

    private static String sees(Visibility visibility) {
        return visibility == Visibility.ALL
                ? "Sees every control, drafts included."
                : "Sees the controls they are assigned to, shared with or created.";
    }

    private static String does(AccessRight access) {
        return access == AccessRight.EDIT
                ? "Performs the steps and edits only the controls they are assigned to."
                : "Views and downloads only: no edits, no workflow steps.";
    }

    /** "What this role can do" for SoQM Team and KDN (a User's is {@link #hint}). */
    public static List<String> canDo(UserRole role) {
        return switch (role) {
            case SOQM_TEAM -> List.of(
                    "Sees every control, drafts included.",
                    "Creates and edits controls and assigns people.",
                    "Moves any control on or back, also for a Control role (Facilitator, Control Operator, Process Owner).",
                    "Manages users and sees the audit trail in the Admin Panel.");
            case KDN -> List.of(
                    "Sees only KDN controls: the ones they are assigned to, shared with or created.",
                    "Views the control, its history and documents and downloads attachments.",
                    "Never edits and performs no workflow step: SoQM Team does that for them.",
                    "Has no Admin Panel.");
            case USER -> List.of();
        };
    }

    /** The Visibility and Access a role fixes, with the reason; empty for User, who chooses both. */
    public static List<FixedValue> fixedValues(UserRole role) {
        return switch (role) {
            case SOQM_TEAM -> List.of(
                    new FixedValue("Visibility", Visibility.ALL.getDisplayName(), "SoQM Team always sees all controls."),
                    new FixedValue("Access", "Full", "SoQM Team edits every control."));
            case KDN -> List.of(
                    new FixedValue("Visibility", "KDN controls", "KDN users see only KDN controls they are on."),
                    new FixedValue("Access", AccessRight.READ_ONLY.getDisplayName(), "KDN users have read-only access."));
            case USER -> List.of();
        };
    }

    /** Every combination's hint by key "ROLE" or "USER/VISIBILITY/ACCESS", for the Admin Panel script. */
    public static Map<String, String> hints() {
        Map<String, String> hints = new LinkedHashMap<>();
        hints.put(UserRole.SOQM_TEAM.name(), hint(AccessPolicy.Profile.soqmTeam()));
        for (Visibility visibility : Visibility.values()) {
            for (AccessRight access : AccessRight.values()) {
                hints.put(UserRole.USER.name() + "/" + visibility.name() + "/" + access.name(),
                        hint(AccessPolicy.Profile.user(visibility, access)));
            }
        }
        hints.put(UserRole.KDN.name(), hint(AccessPolicy.Profile.kdn()));
        return hints;
    }
}
