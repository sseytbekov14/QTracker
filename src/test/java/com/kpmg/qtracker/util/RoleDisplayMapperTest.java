package com.kpmg.qtracker.util;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The words people see for a user's access: role, one-line summary, hints. */
class RoleDisplayMapperTest {

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "SOQM        | ALL | SoQM Team",
            "PARTICIPANT | OWN | User · My controls · Edit",
            "PARTICIPANT | ALL | User · All controls · Edit",
            "READ_ONLY   | OWN | User · My controls · Read Only",
            "READ_ONLY   | ALL | User · All controls · Read Only",
            "READ_ONLY   | KDN | KDN",
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
        assertThat(RoleDisplayMapper.role(user)).isEqualTo("SoQM Team");
        user.setAccessLevel(AccessLevel.READ_ONLY);
        user.setAccessScope(AccessScope.KDN);
        assertThat(RoleDisplayMapper.role(user)).isEqualTo("KDN");
    }

    @Test
    void everyCombinationHasAHint_inTheNewWords() {
        Map<String, String> hints = RoleDisplayMapper.hints();
        assertThat(hints).containsOnlyKeys("SOQM_TEAM", "USER/MY/EDIT", "USER/MY/READ_ONLY", "USER/ALL/EDIT",
                "USER/ALL/READ_ONLY", "KDN");
        assertThat(hints.get("USER/ALL/EDIT")).contains("every control", "only the controls they are assigned to");
        assertThat(hints.get("USER/MY/READ_ONLY")).contains("assigned to, shared with or created", "Views and downloads only");
        assertThat(hints.get("KDN")).contains("KDN controls");
        hints.values().forEach(hint -> assertThat(hint)
                .doesNotContain("Participant", "participant", "Level", "Scope", "Admin access", "Administrator"));
    }

    @Test
    void soqmTeamAndKdn_sayWhatTheyCanDo_andWhyTheirValuesAreFixed() {
        assertThat(RoleDisplayMapper.canDo(UserRole.SOQM_TEAM)).hasSizeBetween(3, 4)
                .anyMatch(line -> line.contains("Admin Panel"));
        assertThat(RoleDisplayMapper.canDo(UserRole.KDN)).hasSizeBetween(3, 4);
        assertThat(RoleDisplayMapper.canDo(UserRole.USER)).isEmpty();
        assertThat(RoleDisplayMapper.fixedValues(UserRole.KDN))
                .extracting(RoleDisplayMapper.FixedValue::reason)
                .contains("KDN users have read-only access.");
        assertThat(RoleDisplayMapper.fixedValues(UserRole.USER)).isEmpty();
    }
}
