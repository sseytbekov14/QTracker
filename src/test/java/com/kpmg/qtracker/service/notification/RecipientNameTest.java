package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** "Dear {NAME}," and "Your role: {ROLES}" of one person (NotificationRecipients.Person). */
class RecipientNameTest {

    private static User user(long id, String name) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(name);
        user.setMail("anna@example.test");
        user.setEnabled(true);
        return user;
    }

    private static NotificationRecipients.Person person(List<User> users, String... roles) {
        return new NotificationRecipients.Person("anna@example.test", users, List.of(roles));
    }

    @Test
    void theName_isTheUsersName_asItIs_ofTheOldestUserOfTheAddress() {
        assertThat(person(List.of(user(7, "  Anna O'Neil <QA>  ")), "Facilitator").name()).isEqualTo("Anna O'Neil <QA>");
        assertThat(person(List.of(user(9, "Second Anna"), user(4, "first ANNA")), "Facilitator").name())
                .isEqualTo("first ANNA");
    }

    @Test
    void withoutAName_theRoleOnTheControl() {
        assertThat(person(List.of(user(7, " ")), "Control Operator", "Process Owner").name()).isEqualTo("Control Operator");
        assertThat(person(List.of(user(7, null)), "SoQM Admin Team").name()).isEqualTo("SoQM Admin Team");
    }

    @Test
    void theRoles_inTheOrderFacilitatorControlOperatorProcessOwner() {
        List<User> anna = List.of(user(1, "Anna"));
        assertThat(person(anna, "Facilitator").rolesText()).isEqualTo("Facilitator");
        assertThat(person(anna, "Control Operator", "Facilitator").rolesText()).isEqualTo("Facilitator and Control Operator");
        assertThat(person(anna, "Process Owner", "Facilitator", "Control Operator").rolesText())
                .isEqualTo("Facilitator, Control Operator and Process Owner");
        assertThat(person(anna, "Process Owner").rolesText()).isEqualTo("Process Owner");
        assertThat(person(anna, "SoQM Admin Team").rolesText()).isEqualTo("SoQM Admin Team");
    }
}
