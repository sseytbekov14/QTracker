package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ControlScopeTest {

    @Test
    void adminsAndEverySoqmRole_seeAllControls() {
        assertThat(ControlScope.seesAllControls("ADMIN", false)).isTrue();
        assertThat(ControlScope.seesAllControls(" admin ", false)).isTrue();
        assertThat(ControlScope.seesAllControls("SOQM_TEAM", false)).isTrue();
        assertThat(ControlScope.seesAllControls("SoQM Team", false)).isTrue();
        assertThat(ControlScope.seesAllControls("soqm-head", false)).isTrue();
    }

    @Test
    void adminAccess_seesAllControls_whateverTheRole() {
        assertThat(ControlScope.seesAllControls("FACILITATOR", true)).isTrue();
        assertThat(ControlScope.seesAllControls(null, true)).isTrue();
    }

    @Test
    void workflowParticipants_seeOnlyTheirControls() {
        assertThat(ControlScope.seesAllControls("FACILITATOR", false)).isFalse();
        assertThat(ControlScope.seesAllControls("CONTROL_OPERATOR", false)).isFalse();
        assertThat(ControlScope.seesAllControls("PROCESS_OWNER", false)).isFalse();
        assertThat(ControlScope.seesAllControls(null, false)).isFalse();
    }

    @Test
    void userOverload_readsRoleAndAdminAccess() {
        User admin = new User();
        admin.setRole("PROCESS_OWNER");
        admin.setAdminAccess(true);
        User facilitator = new User();
        facilitator.setRole("FACILITATOR");

        assertThat(ControlScope.seesAllControls(admin)).isTrue();
        assertThat(ControlScope.seesAllControls(facilitator)).isFalse();
        assertThat(ControlScope.seesAllControls((User) null)).isFalse();
    }
}
