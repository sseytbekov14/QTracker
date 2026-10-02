package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.exception.ControlReadDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** The read rule on the real permission resolver; only the control and its assignment are stubbed. */
@ExtendWith(MockitoExtension.class)
class PermissionServiceReadAccessTest {

    @Mock
    private IControlService controlService;

    @Mock
    private ControlAssignmentService controlAssignmentService;

    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        permissionService = new PermissionService(
                new ControlPermissionService(controlService, controlAssignmentService), controlService);
    }

    @Test
    void stranger_isDenied() {
        Control control = control(1L, "IN_PROGRESS", new ControlAssignmentDTO());

        assertThat(readAccess(control, user("stranger@kpmg.kz", "FACILITATOR")))
                .isEqualTo(PermissionService.ReadAccess.DENIED);
    }

    @Test
    void assignedFacilitator_readsDraftAndRunningControl() {
        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setFacilitator(List.of("fac@kpmg.kz"));
        User facilitator = user("fac@kpmg.kz", "FACILITATOR");

        assertThat(readAccess(control(2L, "DRAFT", assignment), facilitator))
                .isEqualTo(PermissionService.ReadAccess.ALLOWED);
        assertThat(readAccess(control(3L, "IN_PROGRESS", assignment), facilitator))
                .isEqualTo(PermissionService.ReadAccess.ALLOWED);
    }

    @Test
    void sharedOnlyUser_waitsForInitiation_thenReads() {
        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlSharedWith(List.of("shared@kpmg.kz"));
        User shared = user("shared@kpmg.kz", "CONTROL_OPERATOR");

        assertThat(readAccess(control(4L, "DRAFT", assignment), shared))
                .isEqualTo(PermissionService.ReadAccess.DRAFT_NOT_INITIATED);
        // A control without a performance status is a draft as well
        assertThat(readAccess(control(5L, " ", assignment), shared))
                .isEqualTo(PermissionService.ReadAccess.DRAFT_NOT_INITIATED);
        assertThat(readAccess(control(6L, "REVIEW", assignment), shared))
                .isEqualTo(PermissionService.ReadAccess.ALLOWED);
    }

    @Test
    void sharedUserWhoAlsoWorksOnTheControl_readsTheDraft() {
        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlSharedWith(List.of("op@kpmg.kz"));
        assignment.setControlOperator(List.of("op@kpmg.kz"));

        assertThat(readAccess(control(7L, "DRAFT", assignment), user("op@kpmg.kz", "CONTROL_OPERATOR")))
                .isEqualTo(PermissionService.ReadAccess.ALLOWED);
    }

    @Test
    void soqm_readsEveryDraft() {
        assertThat(readAccess(control(8L, "DRAFT", new ControlAssignmentDTO()), user("soqm@kpmg.kz", "SOQM_TEAM")))
                .isEqualTo(PermissionService.ReadAccess.ALLOWED);
    }

    @Test
    void requireReadable_returnsTheControl_orRefusesWith401_404_403() {
        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setFacilitator(List.of("fac@kpmg.kz"));
        Control control = control(9L, "IN_PROGRESS", assignment);
        when(controlService.getControlById(9L)).thenReturn(Optional.of(control));
        when(controlService.getControlById(404L)).thenReturn(Optional.empty());

        assertThat(permissionService.requireReadable(9L, user("fac@kpmg.kz", "FACILITATOR"))).isSameAs(control);
        assertRefused(() -> permissionService.requireReadable(9L, null), HttpStatus.UNAUTHORIZED);
        assertRefused(() -> permissionService.requireReadable(404L, user("fac@kpmg.kz", "FACILITATOR")),
                HttpStatus.NOT_FOUND);
        assertRefused(() -> permissionService.requireReadable(null, user("fac@kpmg.kz", "FACILITATOR")),
                HttpStatus.NOT_FOUND);
        assertRefused(() -> permissionService.requireReadable(9L, user("stranger@kpmg.kz", "FACILITATOR")),
                HttpStatus.FORBIDDEN);
    }

    private PermissionService.ReadAccess readAccess(Control control, User user) {
        return permissionService.readAccess(control, user, permissionService.resolve(control, user));
    }

    private void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call)
                .isInstanceOf(ControlReadDeniedException.class)
                .extracting(e -> ((ControlReadDeniedException) e).getStatus())
                .isEqualTo(status);
    }

    private Control control(Long id, String performanceStatus, ControlAssignmentDTO assignment) {
        Control control = new Control();
        control.setId(id);
        control.setControlId("HR-" + id);
        control.setPerformanceStatus(performanceStatus);
        User creator = user("creator@kpmg.kz", "SOQM_TEAM");
        control.setCreatedBy(creator);
        assignment.setControlId(id);
        when(controlAssignmentService.getAssignmentByControlId(id)).thenReturn(assignment);
        return control;
    }

    private User user(String mail, String role) {
        User user = new User();
        user.setMail(mail);
        user.setRole(role);
        return user;
    }
}
