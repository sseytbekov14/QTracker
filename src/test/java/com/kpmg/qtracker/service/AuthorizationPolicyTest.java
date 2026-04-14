package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlDetailsDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.security.UserPrincipal;
import com.kpmg.qtracker.security.UserPrincipalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationPolicyTest {

    @Mock
    private ControlPermissionService controlPermissionService;

    @Mock
    private IControlService controlService;

    @Mock
    private UserService userService;

    @Mock
    private UserPrincipalService userPrincipalService;

    @Mock
    private ControlAssignmentService controlAssignmentService;

    @Mock
    private ControlDetailsService controlDetailsService;

    private AuthorizationPolicy authorizationPolicy;

    @BeforeEach
    void setUp() {
        authorizationPolicy = new AuthorizationPolicy(
                controlPermissionService,
                controlService,
                userService,
                userPrincipalService,
                controlAssignmentService,
                controlDetailsService
        );
    }

    @Test
    void soqmLeadCanReadControl() {
        UserPrincipal principal = principal(10L, "soqm@kpmg.kz", "SOQM_TEAM");
        User user = user("soqm@kpmg.kz", "SOQM_TEAM");
        when(userService.getUserByEmail("soqm@kpmg.kz")).thenReturn(Optional.of(user));
        when(controlPermissionService.resolve(101L, user)).thenReturn(permission(true, false, Set.of(), true));

        assertThatCode(() -> authorizationPolicy.checkCanReadControl(101L, principal))
                .doesNotThrowAnyException();
    }

    @Test
    void adminCanModifyWhenPermissionAllowsEdit() {
        UserPrincipal principal = principal(11L, "admin@kpmg.kz", "ADMIN");
        User user = user("admin@kpmg.kz", "ADMIN");
        when(userService.getUserByEmail("admin@kpmg.kz")).thenReturn(Optional.of(user));
        when(controlPermissionService.resolve(201L, user)).thenReturn(permission(true, true, Set.of(), true));

        assertThatCode(() -> authorizationPolicy.checkCanModifyControl(201L, principal))
                .doesNotThrowAnyException();
    }

    @Test
    void editingSomeoneElsesControlIsDenied() {
        UserPrincipal principal = principal(12L, "facilitator@kpmg.kz", "FACILITATOR");
        User user = user("facilitator@kpmg.kz", "FACILITATOR");
        when(userService.getUserByEmail("facilitator@kpmg.kz")).thenReturn(Optional.of(user));
        when(controlPermissionService.resolve(202L, user)).thenReturn(permission(true, false, Set.of(), true));

        assertThatThrownBy(() -> authorizationPolicy.checkCanModifyControl(202L, principal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    void modifyingAttachmentWithoutRightsIsDenied() {
        UserPrincipal principal = principal(13L, "facilitator@kpmg.kz", "FACILITATOR");
        User user = user("facilitator@kpmg.kz", "FACILITATOR");
        Control control = control(301L, "report.pdf", null, "owner@kpmg.kz");
        when(userService.getUserByEmail("facilitator@kpmg.kz")).thenReturn(Optional.of(user));
        when(controlService.getAllControls()).thenReturn(List.of(control));
        when(controlPermissionService.resolve(control, user)).thenReturn(permission(true, false, Set.of(), true));
        when(controlAssignmentService.getAssignmentByControlId(301L)).thenReturn(new ControlAssignmentDTO());

        assertThatThrownBy(() -> authorizationPolicy.checkAttachmentAccess("report.pdf", principal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    void controlOperatorCanPerformWorkflowWhenAssignedAndEditable() {
        UserPrincipal principal = principal(14L, "operator@kpmg.kz", "CONTROL_OPERATOR");
        User user = user("operator@kpmg.kz", "CONTROL_OPERATOR");
        when(userService.getUserByEmail("operator@kpmg.kz")).thenReturn(Optional.of(user));
        when(controlPermissionService.resolve(401L, user)).thenReturn(permission(
                true,
                true,
                Set.of(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED),
                true
        ));
        when(controlAssignmentService.getAssignmentByControlId(401L)).thenReturn(assignment(null, List.of("operator@kpmg.kz"), null, null, null));

        assertThatCode(() -> authorizationPolicy.checkWorkflowPermission(401L, "submit to review", principal))
                .doesNotThrowAnyException();
    }

    @Test
    void processOwnerForbiddenWorkflowTransitionIsDenied() {
        UserPrincipal principal = principal(15L, "owner@kpmg.kz", "PROCESS_OWNER");
        User user = user("owner@kpmg.kz", "PROCESS_OWNER");
        when(userService.getUserByEmail("owner@kpmg.kz")).thenReturn(Optional.of(user));
        when(controlPermissionService.resolve(402L, user)).thenReturn(permission(true, true, Set.of(), true));
        when(controlAssignmentService.getAssignmentByControlId(402L)).thenReturn(assignment(null, null, null, List.of("owner@kpmg.kz"), null));

        assertThatThrownBy(() -> authorizationPolicy.checkWorkflowPermission(402L, "submit_to_operator", principal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    void processOwnerAllowedWorkflowTransitionPasses() {
        UserPrincipal principal = principal(16L, "owner@kpmg.kz", "PROCESS_OWNER");
        User user = user("owner@kpmg.kz", "PROCESS_OWNER");
        when(userService.getUserByEmail("owner@kpmg.kz")).thenReturn(Optional.of(user));
        when(controlPermissionService.resolve(403L, user)).thenReturn(permission(true, true, Set.of(), true));
        when(controlAssignmentService.getAssignmentByControlId(403L)).thenReturn(assignment(null, null, null, List.of("owner@kpmg.kz"), null));

        assertThatCode(() -> authorizationPolicy.checkWorkflowPermission(403L, "complete_control", principal))
                .doesNotThrowAnyException();
    }

    @Test
    void facilitatorCannotChangeSoqmComments() {
        UserPrincipal principal = principal(17L, "facilitator@kpmg.kz", "FACILITATOR");
        ControlDetailsDTO existing = new ControlDetailsDTO();
        existing.setSoqmHeadComments("old comment");
        ControlDetailsDTO incoming = new ControlDetailsDTO();
        incoming.setControlId(501L);
        incoming.setSoqmHeadComments("new comment");
        when(controlDetailsService.getDetailsByControlId(501L)).thenReturn(existing);

        assertThatThrownBy(() -> authorizationPolicy.validateEditableFields(incoming, principal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied");
    }

    private UserPrincipal principal(Long id, String email, String role) {
        return new UserPrincipal(id, email, Set.of(role));
    }

    private User user(String email, String role) {
        User user = new User();
        user.setId(1L);
        user.setMail(email);
        user.setRole(role);
        user.setDisplayName(email);
        return user;
    }

    private Control control(Long id, String detailsPath, String documentsPath, String creatorEmail) {
        Control control = new Control();
        control.setId(id);
        control.setAttachmentDetailsPath(detailsPath);
        control.setAttachmentDocumentsPath(documentsPath);
        User creator = new User();
        creator.setMail(creatorEmail);
        control.setCreatedBy(creator);
        return control;
    }

    private ControlPermission permission(boolean canView, boolean canEdit, Set<String> allowedFields, boolean canUseWorkflowActions) {
        return new ControlPermission(
                canView,
                canEdit,
                allowedFields,
                canUseWorkflowActions,
                false,
                false,
                false,
                false,
                false,
                false,
                false
        );
    }

    private ControlAssignmentDTO assignment(List<String> facilitators,
                                            List<String> operators,
                                            List<String> soqmLeads,
                                            List<String> owners,
                                            List<String> sharedWith) {
        ControlAssignmentDTO dto = new ControlAssignmentDTO();
        dto.setFacilitator(facilitators);
        dto.setControlOperator(operators);
        dto.setSoqmLead(soqmLeads);
        dto.setProcessOwner(owners);
        dto.setControlSharedWith(sharedWith);
        return dto;
    }
}