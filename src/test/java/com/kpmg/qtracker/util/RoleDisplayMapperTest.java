package com.kpmg.qtracker.util;

import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RoleDisplayMapperTest {

    @ParameterizedTest(name = "{0} {1} admin={2}")
    @CsvSource(delimiter = '|', value = {
            "SOQM        | ALL | false | SoQM",
            "SOQM        | ALL | true  | SoQM · Admin",
            "PARTICIPANT | OWN | false | Participant",
            "PARTICIPANT | ALL | false | Participant · All controls",
            "PARTICIPANT | KDN | true  | Participant · KDN · Admin",
            "READ_ONLY   | OWN | false | Read only",
            "READ_ONLY   | ALL | true  | Read only · All controls · Admin",
    })
    void accessLabel(AccessLevel level, AccessScope scope, boolean admin, String label) {
        assertThat(RoleDisplayMapper.access(level, scope, admin)).isEqualTo(label);
    }

    @Test
    void noUser_hasNoLabel_andAMissingLevelIsTheLeastAccess() {
        assertThat(RoleDisplayMapper.access(null)).isEmpty();
        assertThat(RoleDisplayMapper.access(null, null, false)).isEqualTo("Read only");
    }
}
