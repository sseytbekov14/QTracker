package com.kpmg.qtracker.service.notification;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static com.kpmg.qtracker.service.WorkflowTransition.Actor.CONTROL_OPERATOR;
import static com.kpmg.qtracker.service.WorkflowTransition.Actor.FACILITATOR;
import static com.kpmg.qtracker.service.WorkflowTransition.Actor.PROCESS_OWNER;
import static com.kpmg.qtracker.service.WorkflowTransition.Actor.SOQM_TEAM;
import static org.assertj.core.api.Assertions.assertThat;

/** Decision 4: the notification of every change of status, by the two statuses alone. */
class WorkflowNotificationsTest {

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource(nullValues = "-", value = {
            // the route
            "IN_PROGRESS,          REVIEW,               T6,  CONTROL_OPERATOR, -",
            "REVIEW,               IN_PROGRESS,          T7,  FACILITATOR,      Control Operator",
            "REVIEW,               SOQM_HEAD_REVIEW,     T8,  SOQM_TEAM,        -",
            "SOQM_HEAD_REVIEW,     REVIEW,               T9,  CONTROL_OPERATOR, -",
            "SOQM_HEAD_REVIEW,     IN_PROGRESS,          T7,  FACILITATOR,      SoQM Admin Team",
            "SOQM_HEAD_REVIEW,     PROCESS_OWNER_REVIEW, T10, PROCESS_OWNER,    -",
            "PROCESS_OWNER_REVIEW, REVIEW,               T11, CONTROL_OPERATOR, -",
            // the other moves SoQM may make, by the target
            "PROCESS_OWNER_REVIEW, IN_PROGRESS,          T7,  FACILITATOR,      Process Owner",
            "PROCESS_OWNER_REVIEW, SOQM_HEAD_REVIEW,     T8,  SOQM_TEAM,        -",
            "COMPLETED,            REVIEW,               T9,  CONTROL_OPERATOR, -",
            "COMPLETED,            IN_PROGRESS,          T7,  FACILITATOR,      SoQM Admin Team",
            "COMPLETED,            SOQM_HEAD_REVIEW,     T8,  SOQM_TEAM,        -",
            "COMPLETED,            PROCESS_OWNER_REVIEW, T10, PROCESS_OWNER,    -",
    })
    void theTemplateAndThePeople_followTheStatuses(String from, String to, Template template, String role,
                                                   String returnedBy) {
        WorkflowNotifications.Event event = WorkflowNotifications.event(from, to);

        assertThat(event.template()).isEqualTo(template);
        assertThat(event.roles()).containsExactly(com.kpmg.qtracker.service.WorkflowTransition.Actor.valueOf(role));
        assertThat(event.returnedBy()).isEqualTo(returnedBy);
    }

    @ParameterizedTest
    @CsvSource({"PROCESS_OWNER_REVIEW", "SOQM_HEAD_REVIEW", "REVIEW", "IN_PROGRESS"})
    void completed_isT12_toAllFourRoles(String from) {
        WorkflowNotifications.Event event = WorkflowNotifications.event(from, "COMPLETED");

        assertThat(event.template()).isEqualTo(Template.T12);
        assertThat(event.roles()).isEqualTo(List.of(FACILITATOR, CONTROL_OPERATOR, SOQM_TEAM, PROCESS_OWNER));
    }

    @ParameterizedTest
    @CsvSource({"DRAFT, IN_PROGRESS", "IN_PROGRESS, DRAFT", "REVIEW, CANCELLED"})
    void initiationAndUnknownStatuses_giveNothingHere(String from, String to) {
        assertThat(WorkflowNotifications.event(from, to)).isNull();
    }
}
