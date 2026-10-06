package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The access the user import gives the Role values of the Power Apps user list, and the old QTracker roles. */
class LegacyRoleAccessTest {

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource(nullValues = "-", value = {
            // Power Apps QT_Users "Role"
            "SoQM Team,        -,           SOQM,        ALL",
            "SoQM Head,        -,           SOQM,        ALL",
            "Master,           -,           PARTICIPANT, ALL",
            "KDN,              -,           READ_ONLY,   KDN",
            "Read Only,        -,           READ_ONLY,   OWN",
            // QTracker users.role / secondary_role
            "SOQM_TEAM,        -,           SOQM,        ALL",
            "FACILITATOR,      -,           PARTICIPANT, OWN",
            "CONTROL_OPERATOR, FACILITATOR, PARTICIPANT, OWN",
            "Process Owner,    -,           PARTICIPANT, OWN",
            "FACILITATOR,      KDN,         READ_ONLY,   KDN",
            "SOQM_TEAM,        KDN,         READ_ONLY,   KDN",
            "ADMIN,            -,           PARTICIPANT, ALL",
            // No role or one nobody knows: the least access
            "-,                -,           READ_ONLY,   OWN",
            "SysAdmin,         -,           READ_ONLY,   OWN",
    })
    void mapsTheRole(String role, String secondaryRole, AccessLevel level, AccessScope scope) {
        assertThat(LegacyRoleAccess.of(role, secondaryRole)).isEqualTo(new LegacyRoleAccess.Access(level, scope));
    }
}
