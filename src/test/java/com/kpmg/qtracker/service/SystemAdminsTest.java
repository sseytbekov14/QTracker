package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The System Admin Team list from SYSTEM_ADMIN_EMAILS, and how a listed account is seen. */
class SystemAdminsTest {

    @AfterEach
    void clear() {
        SystemAdmins.replace(List.of());
    }

    @Test
    void theSetting_isAListOfEMails_anyCase_anySeparator_blanksIgnored() {
        SystemAdmins.replace(" Anna@KPMG.kz, b@kpmg.kz;c@kpmg.kz  d@kpmg.kz,, ");

        assertThat(SystemAdmins.size()).isEqualTo(4);
        assertThat(SystemAdmins.contains("anna@kpmg.kz")).isTrue();
        assertThat(SystemAdmins.contains("  ANNA@kpmg.KZ ")).isTrue();
        assertThat(SystemAdmins.contains("d@kpmg.kz")).isTrue();
        assertThat(SystemAdmins.contains("e@kpmg.kz")).isFalse();
        assertThat(SystemAdmins.contains(null)).isFalse();

        SystemAdmins.replace((String) null);
        assertThat(SystemAdmins.size()).isZero();
        SystemAdmins.replace("   ");
        assertThat(SystemAdmins.size()).isZero();
    }

    @Test
    void aListedAccount_isReadOnlyOnEveryControl_whateverItSays_andShownAsSystemAdminTeam() {
        User user = new User();
        user.setMail("Anna@kpmg.kz");
        user.setEnabled(true);
        user.setAccessLevel(AccessLevel.SOQM);
        user.setAccessScope(AccessScope.ALL);
        assertThat(AccessPolicy.Subject.of(user).systemAdmin()).isFalse();
        assertThat(AccessPolicy.isSoqm(AccessPolicy.Subject.of(user))).isTrue();

        SystemAdmins.replace("anna@kpmg.kz");

        AccessPolicy.Subject subject = AccessPolicy.Subject.of(user);
        assertThat(subject.systemAdmin()).isTrue();
        assertThat(subject.level()).isEqualTo(AccessLevel.READ_ONLY);
        assertThat(subject.scope()).isEqualTo(AccessScope.ALL);
        assertThat(AccessPolicy.isSoqm(subject)).isFalse();
        assertThat(com.kpmg.qtracker.util.RoleDisplayMapper.role(user)).isEqualTo("System Admin Team");
        assertThat(com.kpmg.qtracker.service.notification.NotificationRecipients.receives(user)).isFalse();

        // Off the list: the account as it says again
        SystemAdmins.replace(List.of());
        assertThat(AccessPolicy.isSoqm(AccessPolicy.Subject.of(user))).isTrue();
        assertThat(com.kpmg.qtracker.util.RoleDisplayMapper.role(user)).isEqualTo("SoQM Admin Team");
    }
}
