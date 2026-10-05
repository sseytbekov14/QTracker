package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Scope KDN: only KDN controls, and of those the ones the user works on or is shared with. */
@ExtendWith(MockitoExtension.class)
class ControlPermissionServiceKdnTest {

    private static final String MAIL = "kdn.user@kpmg.kz";

    @Mock
    private IControlService controlService;

    @Mock
    private ControlAssignmentService controlAssignmentService;

    private ControlPermissionService permissionService;

    private final User user = TestUsers.user(MAIL, AccessLevel.PARTICIPANT, AccessScope.KDN, false);

    @BeforeEach
    void setUp() {
        permissionService = new ControlPermissionService(controlService, controlAssignmentService);
    }

    @Test
    void kdnUser_doesNotSeeAKdnControlTheyAreNotOn() {
        ControlPermission permission = permissionService.resolve(control("KDN-1001", "IN_PROGRESS"), user,
                new ControlAssignmentDTO());

        assertThat(permission.canView()).isFalse();
        assertThat(permission.canEdit()).isFalse();
    }

    @Test
    void assignedKdnUser_performsTheirStepOnAKdnControl() {
        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setFacilitator(List.of(MAIL));

        ControlPermission permission = permissionService.resolve(control("KDN-1001", "IN_PROGRESS"), user, assignment);

        assertThat(permission.canView()).isTrue();
        assertThat(permission.isFacilitator()).isTrue();
        assertThat(permission.getAllowedEditableFields()).containsExactly(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED);
        assertThat(permission.canEditAll()).isFalse();
    }

    @Test
    void kdnUser_neverSeesANonKdnControl_evenWhenListedOnIt() {
        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setFacilitator(List.of(MAIL));
        assignment.setControlSharedWith(List.of(MAIL));

        ControlPermission permission = permissionService.resolve(control("HR-2002", "IN_PROGRESS"), user, assignment);

        assertThat(permission.canView()).isFalse();
        assertThat(permission.canEdit()).isFalse();
        assertThat(permission.canUseWorkflowActions()).isFalse();
    }

    private Control control(String controlId, String status) {
        Control control = new Control();
        control.setId(1L);
        control.setControlId(controlId);
        control.setPerformanceStatus(status);
        return control;
    }
}
