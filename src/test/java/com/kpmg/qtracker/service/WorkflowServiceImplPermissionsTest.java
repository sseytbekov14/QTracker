package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.repository.WorkflowStepRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceImplPermissionsTest {

    @Mock
    private WorkflowStepRepository workflowStepRepository;
    @Mock
    private ControlAssignmentService controlAssignmentService;
    @Mock
    private WorkflowHistoryRepository workflowHistoryRepository;
    @Mock
    private ControlService controlService;
    @Mock
    private UserService userService;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private WorkflowServiceImpl workflowService;

    @Test
    void soqmRoleAlwaysHasEditPermissions() {
        Long controlId = 10L;
        String userEmail = "soqm.lead@kpmg.com";

        User user = new User();
        user.setRole("SOQM_TEAM");
        user.setMail(userEmail);

        when(controlAssignmentService.getUserRolesForControl(controlId, userEmail))
                .thenReturn(Collections.emptyList());
        when(userService.getUserByEmail(userEmail)).thenReturn(Optional.of(user));
        when(workflowStepRepository.findCurrentStep(controlId)).thenReturn(Optional.empty());
        when(workflowStepRepository.findByControlIdOrderBySequenceOrderAsc(controlId))
                .thenReturn(Collections.emptyList());

        Map<String, Boolean> permissions = workflowService.getUserPermissions(controlId, userEmail);

        assertThat(permissions.get("canEdit")).isTrue();
        assertThat(permissions.get("canEditAll")).isTrue();
        assertThat(permissions.get("isSoqmRole")).isTrue();
    }

    @Test
    void facilitatorGetsStepsPerformedPermissionOnly() {
        Long controlId = 22L;
        String userEmail = "facilitator@kpmg.com";

        User user = new User();
        user.setRole("FACILITATOR");
        user.setMail(userEmail);

        when(controlAssignmentService.getUserRolesForControl(controlId, userEmail))
                .thenReturn(Collections.singletonList("FACILITATOR"));
        when(userService.getUserByEmail(userEmail)).thenReturn(Optional.of(user));
        when(workflowStepRepository.findCurrentStep(controlId)).thenReturn(Optional.empty());
        when(workflowStepRepository.findByControlIdOrderBySequenceOrderAsc(controlId))
                .thenReturn(Collections.emptyList());

        Map<String, Boolean> permissions = workflowService.getUserPermissions(controlId, userEmail);

        assertThat(permissions.get("canEditStepsPerformed")).isTrue();
        assertThat(permissions.get("canEditAll")).isFalse();
        assertThat(permissions.get("canEdit")).isFalse();
        assertThat(permissions.get("isSoqmRole")).isFalse();
    }

    @Test
    void controlOperatorGetsStepsPerformedPermissionOnly() {
        Long controlId = 33L;
        String userEmail = "operator@kpmg.com";

        User user = new User();
        user.setRole("CONTROL_OPERATOR");
        user.setMail(userEmail);

        when(controlAssignmentService.getUserRolesForControl(controlId, userEmail))
                .thenReturn(Collections.singletonList("CONTROL_OPERATOR"));
        when(userService.getUserByEmail(userEmail)).thenReturn(Optional.of(user));
        when(workflowStepRepository.findCurrentStep(controlId)).thenReturn(Optional.empty());
        when(workflowStepRepository.findByControlIdOrderBySequenceOrderAsc(controlId))
                .thenReturn(Collections.emptyList());

        Map<String, Boolean> permissions = workflowService.getUserPermissions(controlId, userEmail);

        assertThat(permissions.get("canEditStepsPerformed")).isTrue();
        assertThat(permissions.get("canEditAll")).isFalse();
        assertThat(permissions.get("canEdit")).isFalse();
        assertThat(permissions.get("isSoqmRole")).isFalse();
    }

    @Test
    void processOwnerGetsCommentsPermissionOnly() {
        Long controlId = 44L;
        String userEmail = "owner@kpmg.com";

        User user = new User();
        user.setRole("PROCESS_OWNER");
        user.setMail(userEmail);

        when(controlAssignmentService.getUserRolesForControl(controlId, userEmail))
                .thenReturn(Collections.singletonList("PROCESS_OWNER"));
        when(userService.getUserByEmail(userEmail)).thenReturn(Optional.of(user));
        when(workflowStepRepository.findCurrentStep(controlId)).thenReturn(Optional.empty());
        when(workflowStepRepository.findByControlIdOrderBySequenceOrderAsc(controlId))
                .thenReturn(Collections.emptyList());

        Map<String, Boolean> permissions = workflowService.getUserPermissions(controlId, userEmail);

        assertThat(permissions.get("canEditProcessOwnerComments")).isTrue();
        assertThat(permissions.get("canEditAll")).isFalse();
        assertThat(permissions.get("canEdit")).isFalse();
        assertThat(permissions.get("isSoqmRole")).isFalse();
    }
}
