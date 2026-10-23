package com.kpmg.qtracker.util;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessRight;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.enums.UserRole;
import com.kpmg.qtracker.enums.Visibility;
import com.kpmg.qtracker.service.AccessPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

/** The words people see for a user's access: role, one-line summary, Access lines, Permissions. */
class RoleDisplayMapperTest {

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "SOQM        | ALL | SoQM Admin Team",
            "PARTICIPANT | OWN | User · My controls · Edit",
            "PARTICIPANT | ALL | User · All controls · Edit",
            "READ_ONLY   | OWN | User · My controls · Read Only",
            "READ_ONLY   | ALL | User · All controls · Read Only",
            "READ_ONLY   | KDN | KDN · All KDN controls · Read Only",
    })
    void summary_isOneLine(AccessLevel level, AccessScope scope, String label) {
        assertThat(RoleDisplayMapper.access(level, scope)).isEqualTo(label);
    }

    @Test
    void noUser_hasNoLabel_andAMissingLevelIsTheLeastAccess() {
        assertThat(RoleDisplayMapper.access(null)).isEmpty();
        assertThat(RoleDisplayMapper.access(null, null)).isEqualTo("User · My controls · Read Only");
        assertThat(RoleDisplayMapper.role(null)).isEmpty();
    }

    @Test
    void role_isTheRoleAlone() {
        User user = new User();
        user.setAccessLevel(AccessLevel.PARTICIPANT);
        user.setAccessScope(AccessScope.ALL);
        assertThat(RoleDisplayMapper.role(user)).isEqualTo("User");
        user.setAccessLevel(AccessLevel.SOQM);
        assertThat(RoleDisplayMapper.role(user)).isEqualTo("SoQM Admin Team");
        user.setAccessLevel(AccessLevel.READ_ONLY);
        user.setAccessScope(AccessScope.KDN);
        assertThat(RoleDisplayMapper.role(user)).isEqualTo("KDN");
    }

    @Test
    void permissions_areOneShortTextPerRoleAndAccess() {
        assertThat(RoleDisplayMapper.permissionsByKey()).containsExactly(
                entry("SOQM_TEAM", "Manages all controls and users. Completes or returns steps on behalf of any role."
                        + " Exports data and accesses the Admin Panel."),
                entry("USER/EDIT", "Completes steps and edits controls assigned to them."),
                entry("USER/READ_ONLY", "Views controls. No editing."),
                entry("KDN", "Views KDN controls and downloads files. No editing."));
        // What a User sees is the Visibility next to it: both give the same text
        for (AccessRight access : AccessRight.values()) {
            assertThat(RoleDisplayMapper.permissions(AccessPolicy.Profile.user(Visibility.ALL, access)))
                    .isEqualTo(RoleDisplayMapper.permissions(AccessPolicy.Profile.user(Visibility.MY, access)));
        }
    }

    /** Each text claims only what AccessPolicy gives the profile, and says "No editing" exactly when it writes nothing. */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "SOQM        | ALL",
            "PARTICIPANT | OWN",
            "PARTICIPANT | ALL",
            "READ_ONLY   | OWN",
            "READ_ONLY   | ALL",
            "READ_ONLY   | KDN",
    })
    void permissions_doNotContradictAccessPolicy(AccessLevel level, AccessScope scope) {
        AccessPolicy.Subject subject = new AccessPolicy.Subject(level, scope, true);
        String text = RoleDisplayMapper.permissions(AccessPolicy.Profile.of(subject));
        assertThat(text.contains("Admin Panel")).isEqualTo(AccessPolicy.canOpenAdminPanel(subject));
        assertThat(text.contains("and users")).isEqualTo(AccessPolicy.canManageUsers(subject));
        assertThat(text.contains("Exports")).isEqualTo(AccessPolicy.canExportControlLists(subject));
        assertThat(text.contains("on behalf of any role")).isEqualTo(AccessPolicy.isSoqm(subject));
        assertThat(text.contains("No editing")).isEqualTo(!AccessPolicy.mayWrite(subject));
        assertThat(text.contains("Completes")).isEqualTo(AccessPolicy.mayWrite(subject));
        assertThat(text.contains("KDN controls")).isEqualTo(AccessPolicy.isKdnUser(subject));
    }

    @Test
    void soqmTeamAndKdn_sayWhyTheirValuesAreFixed() {
        assertThat(RoleDisplayMapper.fixedValues(UserRole.SOQM_TEAM))
                .extracting(RoleDisplayMapper.FixedValue::label, RoleDisplayMapper.FixedValue::value)
                .containsExactly(tuple("Visibility", "All controls"), tuple("Access", "Full access"));
        assertThat(RoleDisplayMapper.fixedValues(UserRole.KDN))
                .extracting(RoleDisplayMapper.FixedValue::label, RoleDisplayMapper.FixedValue::value)
                .containsExactly(tuple("Visibility", "All KDN controls"), tuple("Access", "Read Only"));
        assertThat(RoleDisplayMapper.fixedValues(UserRole.KDN))
                .extracting(RoleDisplayMapper.FixedValue::reason)
                .noneMatch(reason -> reason.contains("read-only access") || reason.contains("assigned"));
        assertThat(RoleDisplayMapper.fixedValues(UserRole.USER)).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "SOQM        | ALL | Full access",
            "PARTICIPANT | OWN | My controls · Edit",
            "PARTICIPANT | ALL | All controls · Edit",
            "READ_ONLY   | OWN | My controls · Read Only",
            "READ_ONLY   | ALL | All controls · Read Only",
            "READ_ONLY   | KDN | All KDN controls · Read Only",
    })
    void accessLines_areAreaAndLevel_withoutTheRole(AccessLevel level, AccessScope scope, String line) {
        assertThat(RoleDisplayMapper.accessLines(AccessPolicy.Profile.of(level, scope))).containsExactly(line);
    }

    @Test
    void roleAccessLines_forTheDialogScript() {
        assertThat(RoleDisplayMapper.roleAccessLines()).containsExactly(
                entry("SOQM_TEAM", java.util.List.of("Full access")),
                entry("KDN", java.util.List.of("All KDN controls · Read Only")));
    }

    @Test
    void sharedWith_hintEmptyStateAndMarks() {
        assertThat(RoleDisplayMapper.SHARED_WITH_HINT).isEqualTo("Can view this control and download files, no editing");
        assertThat(RoleDisplayMapper.SHARED_WITH_EMPTY).isEqualTo("Not shared with anyone");
        assertThat(RoleDisplayMapper.sharedNote(AccessPolicy.SharedAccess.VIEWS)).isNull();
        assertThat(RoleDisplayMapper.sharedNote(null)).isNull();
        assertThat(RoleDisplayMapper.sharedNote(AccessPolicy.SharedAccess.AFTER_INITIATION))
                .isEqualTo("Will see it once the control is initiated");
        assertThat(RoleDisplayMapper.sharedNote(AccessPolicy.SharedAccess.NOT_SEEN)).isEqualTo("Will not see this control");
        assertThat(RoleDisplayMapper.sharedNote(AccessPolicy.SharedAccess.DISABLED)).isEqualTo("Disabled");
        assertThat(RoleDisplayMapper.sharedNote(AccessPolicy.SharedAccess.NOT_A_USER)).isEqualTo("Not in the system");
    }
}
