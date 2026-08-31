package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.WorkflowButtonDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.repository.WorkflowStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Тесты бизнес-логики: Кнопки Workflow — кто видит какие кнопки.
 *
 * Покрывает WorkflowServiceImpl.getAvailableButtons() — критическая логика
 * определения доступных действий в зависимости от роли пользователя
 * и текущего статуса контроля.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow — доступные кнопки действий по ролям")
class WorkflowButtonPermissionsTest {

    @Mock private WorkflowStepRepository workflowStepRepository;
    @Mock private ControlAssignmentService controlAssignmentService;
    @Mock private WorkflowHistoryRepository workflowHistoryRepository;
    @Mock private ControlService controlService;
    @Mock private UserService userService;
    @Mock private NotificationService notificationService;

    @InjectMocks
    private WorkflowServiceImpl workflowService;

    private static final Long CONTROL_ID = 1L;
    private static final String FAC_EMAIL  = "facilitator@test.com";
    private static final String CO_EMAIL   = "operator@test.com";
    private static final String SOQM_EMAIL = "soqm@test.com";
    private static final String PO_EMAIL   = "owner@test.com";
    private static final String OTHER_EMAIL = "other@test.com";

    // ════════════════════════════════════════════════════════════════════
    // 1. FACILITATOR — кнопки при статусе IN_PROGRESS
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Facilitator — кнопки при IN_PROGRESS")
    class FacilitatorButtonTests {

        @Test
        @DisplayName("Назначенный Facilitator видит кнопку 'Submit for Review' при IN_PROGRESS")
        void assignedFacilitatorSeesSubmitButton() {
            setupControl("IN_PROGRESS");
            setupUserWithRole(FAC_EMAIL, "FACILITATOR");
            when(controlAssignmentService.getUserRolesForControl(CONTROL_ID, FAC_EMAIL))
                    .thenReturn(List.of("FACILITATOR"));

            List<WorkflowButtonDTO> buttons = workflowService.getAvailableButtons(CONTROL_ID, FAC_EMAIL);

            assertThat(buttons).extracting(WorkflowButtonDTO::getAction)
                    .contains("SUBMIT_FOR_REVIEW");
        }

        @Test
        @DisplayName("Facilitator НЕ видит кнопок при статусе REVIEW (не его очередь)")
        void facilitatorSeesNoButtonsAtReview() {
            setupControl("REVIEW");
            setupUserWithRole(FAC_EMAIL, "FACILITATOR");
            when(controlAssignmentService.getUserRolesForControl(CONTROL_ID, FAC_EMAIL))
                    .thenReturn(List.of("FACILITATOR"));

            List<WorkflowButtonDTO> buttons = workflowService.getAvailableButtons(CONTROL_ID, FAC_EMAIL);

            assertThat(buttons).extracting(WorkflowButtonDTO::getAction)
                    .doesNotContain("SUBMIT_FOR_REVIEW");
        }

        @Test
        @DisplayName("НЕ назначенный пользователь не видит кнопок Facilitator")
        void unassignedUserSeesNoFacilitatorButtons() {
            setupControl("IN_PROGRESS");
            setupUserWithRole(OTHER_EMAIL, "FACILITATOR");
            when(controlAssignmentService.getUserRolesForControl(CONTROL_ID, OTHER_EMAIL))
                    .thenReturn(List.of()); // не назначен

            List<WorkflowButtonDTO> buttons = workflowService.getAvailableButtons(CONTROL_ID, OTHER_EMAIL);

            assertThat(buttons).extracting(WorkflowButtonDTO::getAction)
                    .doesNotContain("SUBMIT_FOR_REVIEW");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. CONTROL OPERATOR — кнопки при статусе REVIEW
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Control Operator — кнопки при REVIEW")
    class ControlOperatorButtonTests {

        @Test
        @DisplayName("Control Operator видит 'Submit for SoQM' и 'Return to Facilitator' при REVIEW")
        void controlOperatorSeesCorrectButtonsAtReview() {
            setupControl("REVIEW");
            setupUserWithRole(CO_EMAIL, "CONTROL_OPERATOR");
            when(controlAssignmentService.getUserRolesForControl(CONTROL_ID, CO_EMAIL))
                    .thenReturn(List.of("CONTROL_OPERATOR"));

            List<WorkflowButtonDTO> buttons = workflowService.getAvailableButtons(CONTROL_ID, CO_EMAIL);

            assertThat(buttons).extracting(WorkflowButtonDTO::getAction)
                    .contains("SUBMIT_FOR_SOQM", "RETURN_TO_FACILITATOR");
        }

        @Test
        @DisplayName("Control Operator НЕ видит кнопок при IN_PROGRESS")
        void controlOperatorSeesNoButtonsAtInProgress() {
            setupControl("IN_PROGRESS");
            setupUserWithRole(CO_EMAIL, "CONTROL_OPERATOR");
            when(controlAssignmentService.getUserRolesForControl(CONTROL_ID, CO_EMAIL))
                    .thenReturn(List.of("CONTROL_OPERATOR"));

            List<WorkflowButtonDTO> buttons = workflowService.getAvailableButtons(CONTROL_ID, CO_EMAIL);

            assertThat(buttons).extracting(WorkflowButtonDTO::getAction)
                    .doesNotContain("SUBMIT_FOR_SOQM", "RETURN_TO_FACILITATOR");
        }

        @Test
        @DisplayName("Кнопка 'Return to Facilitator' у Control Operator требует комментарий")
        void returnToFacilitatorRequiresComment() {
            setupControl("REVIEW");
            setupUserWithRole(CO_EMAIL, "CONTROL_OPERATOR");
            when(controlAssignmentService.getUserRolesForControl(CONTROL_ID, CO_EMAIL))
                    .thenReturn(List.of("CONTROL_OPERATOR"));

            List<WorkflowButtonDTO> buttons = workflowService.getAvailableButtons(CONTROL_ID, CO_EMAIL);

            WorkflowButtonDTO returnBtn = buttons.stream()
                    .filter(b -> "RETURN_TO_FACILITATOR".equals(b.getAction()))
                    .findFirst().orElseThrow();

            assertThat(returnBtn.isRequiresComment()).isTrue();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. SOQM TEAM — кнопки при SOQM_HEAD_REVIEW
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("SOQM Team — кнопки при SOQM_HEAD_REVIEW")
    class SoqmTeamButtonTests {

        @Test
        @DisplayName("SOQM Team видит 3 кнопки: Send to PO, Send back to CO, SOQM Comment")
        void soqmTeamSeesAllButtonsAtSoqmReview() {
            setupControl("SOQM_HEAD_REVIEW");
            setupUserWithRole(SOQM_EMAIL, "SOQM_TEAM");
            when(controlAssignmentService.getUserRolesForControl(CONTROL_ID, SOQM_EMAIL))
                    .thenReturn(List.of("SOQM_TEAM"));

            List<WorkflowButtonDTO> buttons = workflowService.getAvailableButtons(CONTROL_ID, SOQM_EMAIL);

            assertThat(buttons).extracting(WorkflowButtonDTO::getAction)
                    .contains("SEND_TO_PROCESS_OWNER", "SEND_BACK_TO_OPERATOR", "SOQM_COMMENT");
            assertThat(buttons).hasSize(3);
        }

        @Test
        @DisplayName("SOQM Team НЕ видит кнопок при IN_PROGRESS")
        void soqmTeamSeesNoButtonsAtInProgress() {
            setupControl("IN_PROGRESS");
            setupUserWithRole(SOQM_EMAIL, "SOQM_TEAM");
            when(controlAssignmentService.getUserRolesForControl(CONTROL_ID, SOQM_EMAIL))
                    .thenReturn(List.of("SOQM_TEAM"));

            List<WorkflowButtonDTO> buttons = workflowService.getAvailableButtons(CONTROL_ID, SOQM_EMAIL);

            assertThat(buttons).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. PROCESS OWNER — кнопки при PROCESS_OWNER_REVIEW
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Process Owner — кнопки при PROCESS_OWNER_REVIEW")
    class ProcessOwnerButtonTests {

        @Test
        @DisplayName("Process Owner видит 4 кнопки при PROCESS_OWNER_REVIEW")
        void processOwnerSeesAllButtonsAtPOReview() {
            setupControl("PROCESS_OWNER_REVIEW");
            setupUserWithRole(PO_EMAIL, "PROCESS_OWNER");
            when(controlAssignmentService.getUserRolesForControl(CONTROL_ID, PO_EMAIL))
                    .thenReturn(List.of("PROCESS_OWNER"));

            List<WorkflowButtonDTO> buttons = workflowService.getAvailableButtons(CONTROL_ID, PO_EMAIL);

            assertThat(buttons).extracting(WorkflowButtonDTO::getAction)
                    .contains("COMPLETE", "RETURN_TO_FACILITATOR",
                              "SEND_FOR_REVISION", "SUBMIT_FOR_SOQM_REVIEW");
        }

        @Test
        @DisplayName("Кнопка 'Complete' у Process Owner НЕ требует комментарий")
        void completeButtonDoesNotRequireComment() {
            setupControl("PROCESS_OWNER_REVIEW");
            setupUserWithRole(PO_EMAIL, "PROCESS_OWNER");
            when(controlAssignmentService.getUserRolesForControl(CONTROL_ID, PO_EMAIL))
                    .thenReturn(List.of("PROCESS_OWNER"));

            List<WorkflowButtonDTO> buttons = workflowService.getAvailableButtons(CONTROL_ID, PO_EMAIL);

            WorkflowButtonDTO completeBtn = buttons.stream()
                    .filter(b -> "COMPLETE".equals(b.getAction()))
                    .findFirst().orElseThrow();

            assertThat(completeBtn.isRequiresComment()).isFalse();
        }

        @Test
        @DisplayName("Process Owner НЕ видит кнопок при SOQM_HEAD_REVIEW (не его очередь)")
        void processOwnerSeesNoButtonsAtSoqmReview() {
            setupControl("SOQM_HEAD_REVIEW");
            setupUserWithRole(PO_EMAIL, "PROCESS_OWNER");
            when(controlAssignmentService.getUserRolesForControl(CONTROL_ID, PO_EMAIL))
                    .thenReturn(List.of("PROCESS_OWNER"));

            List<WorkflowButtonDTO> buttons = workflowService.getAvailableButtons(CONTROL_ID, PO_EMAIL);

            assertThat(buttons).extracting(WorkflowButtonDTO::getAction)
                    .doesNotContain("COMPLETE", "RETURN_TO_FACILITATOR");
        }
    }

    // ─── Вспомогательные методы ─────────────────────────────────────────

    private void setupControl(String status) {
        Control control = new Control();
        control.setId(CONTROL_ID);
        control.setControlId("HR-001");
        control.setPerformanceStatus(status);
        when(controlService.getControlById(CONTROL_ID)).thenReturn(Optional.of(control));
    }

    private void setupUserWithRole(String email, String role) {
        User user = new User();
        user.setMail(email);
        user.setRole(role);
        when(userService.getUserByEmail(email)).thenReturn(Optional.of(user));
    }
}
