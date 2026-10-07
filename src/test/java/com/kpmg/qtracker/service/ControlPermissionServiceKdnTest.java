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

/** Scope KDN (always read-only): every KDN control (Control ID starting with KDN), on it or not, and no other. */
@ExtendWith(MockitoExtension.class)
class ControlPermissionServiceKdnTest {

    private static final String MAIL = "kdn.user@kpmg.kz";

    @Mock
    private IControlService controlService;

    @Mock
    private ControlAssignmentService controlAssignmentService;

    private ControlPermissionService permissionService;

    private final User user = TestUsers.user(MAIL, AccessLevel.READ_ONLY, AccessScope.KDN, false);

    @BeforeEach
    void setUp() {
        permissionService = new ControlPermissionService(controlService, controlAssignmentService);
    }

    @Test
    void kdnUser_seesAKdnControlTheyAreNotOn_draftsIncluded_readOnly() {
        for (String status : new String[] {"IN_PROGRESS", "DRAFT", null}) {
            ControlPermission permission = permissionService.resolve(control("KDN-1001", status), user,
                    new ControlAssignmentDTO());

            assertThat(permission.canView()).as(status).isTrue();
            assertThat(permission.canEdit()).as(status).isFalse();
            assertThat(permission.canUseWorkflowActions()).as(status).isFalse();
        }
    }

    @Test
    void kdnUser_doesNotSeeAControlWithKdnFurtherOnInItsId() {
        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlSharedWith(List.of(MAIL));

        assertThat(permissionService.resolve(control("X-KDN-12", "IN_PROGRESS"), user, assignment).canView()).isFalse();
    }

    @Test
    void assignedKdnUser_seesTheirKdnControl_butNeitherEditsNorPerformsTheStep() {
        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setFacilitator(List.of(MAIL));

        ControlPermission permission = permissionService.resolve(control("KDN-1001", "IN_PROGRESS"), user, assignment);

        assertThat(permission.canView()).isTrue();
        assertThat(permission.isFacilitator()).isFalse();
        assertThat(permission.canEdit()).isFalse();
        assertThat(permission.getAllowedEditableFields()).isEmpty();
        assertThat(permission.canUseWorkflowActions()).isFalse();
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
