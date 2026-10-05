package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
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

    @ParameterizedTest
    @EnumSource(value = WorkflowTransition.class, names = "SHARED_RESUBMIT_TO_SOQM_TEAM", mode = EnumSource.Mode.EXCLUDE)
    void assignedActorInSourceStatus_isAllowed(WorkflowTransition transition) {
        WorkflowTransitionGuard.Decision decision = guard.check(
                control(transition.getFromStatus()), permissionFor(transition.getActor()), transition);

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
                            control(transition.getFromStatus()), permissionFor(actor), transition);
                    assertThat(decision.allowed()).as("%s by %s", transition, actor).isFalse();
                    assertThat(decision.httpStatus()).as("%s by %s", transition, actor).isEqualTo(403);
                });
    }

    @ParameterizedTest
    @EnumSource(value = WorkflowTransition.class, names = "SHARED_RESUBMIT_TO_SOQM_TEAM", mode = EnumSource.Mode.EXCLUDE)
    void assignedActorInAnyOtherStatus_getsConflict(WorkflowTransition transition) {
        STATUSES.stream()
                .filter(status -> !status.equals(transition.getFromStatus()))
                .forEach(status -> {
                    WorkflowTransitionGuard.Decision decision = guard.check(control(status), permissionFor(transition.getActor()), transition);
                    assertThat(decision.allowed()).as("%s from %s", transition, status).isFalse();
                    assertThat(decision.httpStatus()).as("%s from %s", transition, status).isEqualTo(409);
                });
    }

    @Test
    void roleIsCheckedBeforeStatus() {
        WorkflowTransitionGuard.Decision decision = guard.check(control("REVIEW"), permissionFor(WorkflowTransition.Actor.FACILITATOR), WorkflowTransition.COMPLETE);

        assertThat(decision.httpStatus()).isEqualTo(403);
    }

    @Test
    void userWhoCannotViewControl_isForbidden() {
        WorkflowTransitionGuard.Decision decision = guard.check(control("PROCESS_OWNER_REVIEW"), ControlPermission.denied(), WorkflowTransition.COMPLETE);

        assertThat(decision.httpStatus()).isEqualTo(403);
        assertThat(decision.message()).isEqualTo("Forbidden");
    }

    @Test
    void sharedUser_performsNoTransition_noteventheSharedResubmit() {
        WorkflowTransitionGuard.Decision decision = guard.check(control("COMPLETED"),
                permissionFor(WorkflowTransition.Actor.SHARED_VIEWER), WorkflowTransition.SHARED_RESUBMIT_TO_SOQM_TEAM);

        assertThat(decision.httpStatus()).isEqualTo(403);
        assertThat(decision.message()).isEqualTo("Users the control is shared with can only view it");
    }

    @Test
    void readOnlyUser_isForbiddenWithTheReadOnlyMessage() {
        ControlPermission readOnly = new ControlPermission(true, false, Set.of(), false, false,
                false, true, false, false, false);

        WorkflowTransitionGuard.Decision decision = guard.check(
                control("IN_PROGRESS"), readOnly, WorkflowTransition.SUBMIT_TO_CONTROL_OPERATOR);

        assertThat(decision.httpStatus()).isEqualTo(403);
        assertThat(decision.message()).isEqualTo("Your access is read-only");
    }

    @Test
    void initiate_isSoqmOnly() {
        WorkflowTransitionGuard.Decision participant = guard.check(control("DRAFT"),
                permissionFor(WorkflowTransition.Actor.FACILITATOR), WorkflowTransition.INITIATE);
        WorkflowTransitionGuard.Decision soqm = guard.check(control("DRAFT"),
                permissionFor(WorkflowTransition.Actor.SOQM_TEAM), WorkflowTransition.INITIATE);

        assertThat(participant.httpStatus()).isEqualTo(403);
        assertThat(participant.message()).isEqualTo("Only SoQM can perform \"Initiate\" on this control");
        assertThat(soqm.allowed()).isTrue();
    }

    @Test
    void returnToFacilitator_resolvesByRoleAndStatus() {
        List<WorkflowTransition> candidates = WorkflowTransition.forAction("return_to_facilitator");

        assertThat(guard.check(control("REVIEW"),
                permissionFor(WorkflowTransition.Actor.CONTROL_OPERATOR), candidates).transition())
                .isEqualTo(WorkflowTransition.RETURN_TO_FACILITATOR);
        // The Process Owner returns only to the Control Operator
        assertThat(guard.check(control("PROCESS_OWNER_REVIEW"),
                permissionFor(WorkflowTransition.Actor.PROCESS_OWNER), candidates).httpStatus())
                .isEqualTo(403);
        // Control Operator cannot use the Process Owner's return path
        assertThat(guard.check(control("PROCESS_OWNER_REVIEW"),
                permissionFor(WorkflowTransition.Actor.CONTROL_OPERATOR), candidates).httpStatus())
                .isEqualTo(409);
        assertThat(guard.check(control("REVIEW"),
                permissionFor(WorkflowTransition.Actor.SOQM_TEAM), candidates).httpStatus())
                .isEqualTo(403);
    }

    @Test
    void returnToOperator_resolvesSoqmAndProcessOwnerByStatus() {
        List<WorkflowTransition> candidates =
                List.of(WorkflowTransition.RETURN_TO_OPERATOR, WorkflowTransition.OWNER_RETURN_TO_OPERATOR);

        assertThat(guard.check(control("SOQM_HEAD_REVIEW"),
                permissionFor(WorkflowTransition.Actor.SOQM_TEAM), candidates).transition())
                .isEqualTo(WorkflowTransition.RETURN_TO_OPERATOR);
        assertThat(guard.check(control("PROCESS_OWNER_REVIEW"),
                permissionFor(WorkflowTransition.Actor.PROCESS_OWNER), candidates).transition())
                .isEqualTo(WorkflowTransition.OWNER_RETURN_TO_OPERATOR);
        assertThat(guard.check(control("PROCESS_OWNER_REVIEW"),
                permissionFor(WorkflowTransition.Actor.SOQM_TEAM), candidates).httpStatus())
                .isEqualTo(409);
    }

    @Test
    void returns_areTheThreeOfTheSpec() {
        assertThat(java.util.Arrays.stream(WorkflowTransition.values()).filter(WorkflowTransition::isReturn))
                .containsExactlyInAnyOrder(WorkflowTransition.RETURN_TO_FACILITATOR,
                        WorkflowTransition.RETURN_TO_OPERATOR, WorkflowTransition.OWNER_RETURN_TO_OPERATOR);
        assertThat(WorkflowTransition.forAction("RETURN_TO_SOQM_TEAM")).isEmpty();
        assertThat(WorkflowTransition.forAction("REJECT")).isEmpty();
    }

    @Test
    void blankStatus_isTreatedAsDraft() {
        WorkflowTransitionGuard.Decision decision = guard.check(control(" "), permissionFor(WorkflowTransition.Actor.FACILITATOR),
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
    void initiate_isNotAPerformAction() {
        assertThat(WorkflowTransition.forAction("INITIATE")).isEmpty();
        assertThat(WorkflowTransition.forAction("SUBMIT_FOR_REVIEW")).isEmpty();
    }

    private ControlPermission permissionFor(WorkflowTransition.Actor actor) {
        return switch (actor) {
            case FACILITATOR -> permission(true, false, false, false, false, false);
            case CONTROL_OPERATOR -> permission(false, true, false, false, false, false);
            // Any SoQM user performs the SoQM steps and Initiate
            case SOQM_TEAM, COORDINATOR -> permission(false, false, true, false, true, false);
            case PROCESS_OWNER -> permission(false, false, false, true, false, false);
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
                sharedViewer, facilitator, controlOperator, soqmLead, processOwner);
    }

    private Control control(String status) {
        Control control = new Control();
        control.setId(1L);
        control.setPerformanceStatus(status);
        return control;
    }
}
