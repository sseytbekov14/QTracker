package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ControlPermissionServiceKdnTest {

    @Mock
    private IControlService controlService;

    @Mock
    private ControlAssignmentService controlAssignmentService;

    private ControlPermissionService permissionService;

    @BeforeEach
    void setUp() {
        permissionService = new ControlPermissionService(controlService, controlAssignmentService);
    }

    @Test
    void kdnUserCanViewOnlyKdnControlAndCannotEdit() {
        User user = new User();
        user.setMail("kdn.user@kpmg.kz");
        user.setRole("KDN");

        Control control = new Control();
        control.setId(1L);
        control.setControlId("KDN-1001");

        ControlPermission permission = permissionService.resolve(control, user, null);

        assertThat(permission.canView()).isTrue();
        assertThat(permission.canEdit()).isFalse();
        assertThat(permission.canUseWorkflowActions()).isFalse();
        assertThat(permission.getAllowedEditableFields()).isEmpty();
    }

    @Test
    void kdnUserCannotViewNonKdnControlEvenWithSecondaryRole() {
        User user = new User();
        user.setMail("reviewer@kpmg.kz");
        user.setRole("FACILITATOR");
        user.setSecondaryRole("KDN");

        Control control = new Control();
        control.setId(2L);
        control.setControlId("HR-2002");

        ControlPermission permission = permissionService.resolve(control, user, null);

        assertThat(permission.canView()).isFalse();
        assertThat(permission.canEdit()).isFalse();
        assertThat(permission.canUseWorkflowActions()).isFalse();
    }
}
