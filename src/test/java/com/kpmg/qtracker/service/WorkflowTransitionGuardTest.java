package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.WorkflowStepType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowTransitionGuardTest {

    private static final List<String> STATUSES = List.of(
            "DRAFT", "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED");

    private final WorkflowTransitionGuard guard = new WorkflowTransitionGuard();
    private final User user = user("user@kpmg.kz");

    @ParameterizedTest
    @EnumSource(WorkflowTransition.class)
    void assignedActorInSourceStatus_isAllowed(WorkflowTransition transition) {
        WorkflowTransitionGuard.Decision decision = guard.check(
                control(transition.getFromStatus()), user, permissionFor(transition.getActor()), transition);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.transition()).isEqualTo(transition);
    }

    @ParameterizedTest
    @EnumSource(WorkflowTransition.class)
    void otherParticipants_areForbidden(WorkflowTransition transition) {
        Arrays.stream(WorkflowTransition.Actor.values())
                .filter(actor -> actor != transition.getActor())
                // A SoQM-role user is both SoQM Team and coordinator
                .filter(actor -> !Set.of(transition.getActor(), actor).equals(
                        Set.of(WorkflowTransition.Actor.COORDINATOR, WorkflowTransition.Actor.SOQM_TEAM)))
                .forEach(actor -> {
                    WorkflowTransitionGuard.Decision decision = guard.check(
                            control(transition.getFromStatus()), user, permissionFor(actor), transition);
                    assertThat(decision.allowed()).as("%s by %s", transition, actor).isFalse();
                    assertThat(decision.httpStatus()).as("%s by %s", transition, actor).isEqualTo(403);
                });
    }

    @ParameterizedTest
    @EnumSource(WorkflowTransition.class)
    void assignedActorInAnyOtherStatus_getsConflict(WorkflowTransition transition) {
        STATUSES.stream()
                .filter(status -> !status.equals(transition.getFromStatus()))
                .forEach(status -> {
                    WorkflowTransitionGuard.Decision decision = guard.check(
                            control(status), user, permissionFor(transition.getActor()), transition);
                    assertThat(decision.allowed()).as("%s from %s", transition, status).isFalse();
                    assertThat(decision.httpStatus()).as("%s from %s", transition, status).isEqualTo(409);
                });
    }

    @Test
    void roleIsCheckedBeforeStatus() {
        WorkflowTransitionGuard.Decision decision = guard.check(
                control("REVIEW"), user, permissionFor(WorkflowTransition.Actor.FACILITATOR), WorkflowTransition.COMPLETE);

        assertThat(decision.httpStatus()).isEqualTo(403);
    }

    @Test
    void userWhoCannotViewControl_isForbidden() {
        WorkflowTransitionGuard.Decision decision = guard.check(
                control("PROCESS_OWNER_REVIEW"), user, ControlPermission.denied(), WorkflowTransition.COMPLETE);

        assertThat(decision.httpStatus()).isEqualTo(403);
        assertThat(decision.message()).isEqualTo("Forbidden");
    }

    @Test
    void sharedUserOnCompletedControl_isForbiddenEvenForOwnTransition() {
        ControlPermission sharedCompleted = new ControlPermission(true, true, Set.of(), false, false,
                true, true, false, false, false, false);

        WorkflowTransitionGuard.Decision decision = guard.check(
                control("COMPLETED"), user, sharedCompleted, WorkflowTransition.SHARED_RESUBMIT_TO_SOQM_TEAM);

        assertThat(decision.httpStatus()).isEqualTo(403);
    }

    @Test
    void creatorWithoutSoqmRole_canInitiateDraft() {
        Control control = control("DRAFT");
        control.setCreatedBy(user(" USER@kpmg.kz "));

        WorkflowTransitionGuard.Decision decision = guard.check(
                control, user, permission(false, false, false, false, false, false), WorkflowTransition.INITIATE);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void returnToFacilitator_resolvesByRoleAndStatus() {
        List<WorkflowTransition> candidates = WorkflowTransition.forAction("return_to_facilitator");

        assertThat(guard.check(control("REVIEW"), user,
                permissionFor(WorkflowTransition.Actor.CONTROL_OPERATOR), candidates).transition())
                .isEqualTo(WorkflowTransition.RETURN_TO_FACILITATOR);
        assertThat(guard.check(control("PROCESS_OWNER_REVIEW"), user,
                permissionFor(WorkflowTransition.Actor.PROCESS_OWNER), candidates).transition())
                .isEqualTo(WorkflowTransition.OWNER_RETURN_TO_FACILITATOR);
        // Control Operator cannot use the Process Owner's return path
        assertThat(guard.check(control("PROCESS_OWNER_REVIEW"), user,
                permissionFor(WorkflowTransition.Actor.CONTROL_OPERATOR), candidates).httpStatus())
                .isEqualTo(409);
        assertThat(guard.check(control("REVIEW"), user,
                permissionFor(WorkflowTransition.Actor.SOQM_TEAM), candidates).httpStatus())
                .isEqualTo(403);
    }

    @Test
    void blankStatus_isTreatedAsDraft() {
        WorkflowTransitionGuard.Decision decision = guard.check(
                control(" "), user, permissionFor(WorkflowTransition.Actor.FACILITATOR),
                WorkflowTransition.SUBMIT_TO_CONTROL_OPERATOR);

        assertThat(decision.httpStatus()).isEqualTo(409);
        assertThat(decision.message()).contains("Draft");
    }

    @Test
    void unknownAction_hasNoTransitions() {
        assertThat(WorkflowTransition.forAction("DELETE_EVERYTHING")).isEmpty();
        assertThat(WorkflowTransition.forAction(null)).isEmpty();
    }

    @Test
    void facilitatorStep_cannotBeReturned() {
        assertThat(WorkflowTransition.forStepReturn(WorkflowStepType.FACILITATOR)).isEmpty();
        assertThat(WorkflowTransition.forStepApproval(WorkflowStepType.PROCESS_OWNER))
                .contains(WorkflowTransition.COMPLETE);
    }

    private ControlPermission permissionFor(WorkflowTransition.Actor actor) {
        return switch (actor) {
            case FACILITATOR -> permission(true, false, false, false, false, false);
            case CONTROL_OPERATOR -> permission(false, true, false, false, false, false);
            case SOQM_TEAM -> permission(false, false, true, false, false, false);
            case PROCESS_OWNER -> permission(false, false, false, true, false, false);
            case COORDINATOR -> permission(false, false, true, false, true, false);
            case SHARED_VIEWER -> permission(false, false, false, false, false, true);
        };
    }

    private ControlPermission permission(boolean facilitator,
                                         boolean controlOperator,
                                         boolean soqmLead,
                                         boolean processOwner,
                                         boolean canEditAll,
                                         boolean sharedViewer) {
        return new ControlPermission(true, true, Set.of(), true, canEditAll,
                sharedViewer, false, facilitator, controlOperator, soqmLead, processOwner);
    }

    private Control control(String status) {
        Control control = new Control();
        control.setId(1L);
        control.setPerformanceStatus(status);
        return control;
    }

    private static User user(String mail) {
        User user = new User();
        user.setMail(mail);
        return user;
    }
}
