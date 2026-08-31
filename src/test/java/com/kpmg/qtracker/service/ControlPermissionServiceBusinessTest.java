package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Тесты бизнес-логики: Права доступа к контролям.
 *
 * Покрывает ControlPermissionService — сервис, определяющий,
 * кто может видеть контроль, кто может его редактировать и
 * какие именно поля разрешены для редактирования.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ControlPermissionService — права доступа к контролям")
class ControlPermissionServiceBusinessTest {

    @Mock
    private IControlService controlService;

    @Mock
    private ControlAssignmentService controlAssignmentService;

    private ControlPermissionService permissionService;

    // ─── Вспомогательные данные ─────────────────────────────────────────
    private static final String FAC_EMAIL = "facilitator@test.com";
    private static final String CO_EMAIL  = "operator@test.com";
    private static final String SOQM_EMAIL = "soqm@test.com";
    private static final String PO_EMAIL  = "processowner@test.com";
    private static final String ADMIN_EMAIL = "admin@test.com";
    private static final String OTHER_EMAIL = "other@test.com";

    @BeforeEach
    void setUp() {
        permissionService = new ControlPermissionService(controlService, controlAssignmentService);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. ADMIN — должен иметь полный доступ
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Admin — полный доступ")
    class AdminTests {

        @Test
        @DisplayName("Admin с adminAccess=true может видеть и редактировать любой контроль")
        void adminHasFullAccess() {
            User admin = makeUser(ADMIN_EMAIL, "SOQM_TEAM", true);
            Control control = makeControl(1L, "HR-001", "IN_PROGRESS");
            ControlAssignmentDTO assignment = emptyAssignment();

            ControlPermission perm = permissionService.resolve(control, admin, assignment);

            assertThat(perm.canView()).isTrue();
            assertThat(perm.canEdit()).isTrue();
            assertThat(perm.canEditAll()).isTrue();
            assertThat(perm.canUseWorkflowActions()).isTrue();
        }

        @Test
        @DisplayName("SOQM_TEAM без adminAccess тоже имеет canEditAll")
        void soqmTeamHasEditAll() {
            User soqm = makeUser(SOQM_EMAIL, "SOQM_TEAM", false);
            Control control = makeControl(1L, "HR-001", "SOQM_HEAD_REVIEW");
            ControlAssignmentDTO assignment = emptyAssignment();

            ControlPermission perm = permissionService.resolve(control, soqm, assignment);

            assertThat(perm.canEditAll()).isTrue();
            assertThat(perm.canView()).isTrue();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. FACILITATOR — видит и редактирует только назначенные контроли
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Facilitator — права назначенного пользователя")
    class FacilitatorTests {

        @Test
        @DisplayName("Назначенный Facilitator видит контроль при статусе IN_PROGRESS")
        void assignedFacilitatorCanViewInProgress() {
            User user = makeUser(FAC_EMAIL, "FACILITATOR", false);
            Control control = makeControl(1L, "HR-001", "IN_PROGRESS");
            ControlAssignmentDTO assignment = assignmentWithFacilitator(FAC_EMAIL);

            ControlPermission perm = permissionService.resolve(control, user, assignment);

            assertThat(perm.canView()).isTrue();
            assertThat(perm.canEdit()).isTrue();
            assertThat(perm.getAllowedEditableFields())
                    .contains(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED);
        }

        @Test
        @DisplayName("НЕ назначенный Facilitator НЕ может видеть чужой контроль")
        void unassignedFacilitatorCannotView() {
            User user = makeUser(FAC_EMAIL, "FACILITATOR", false);
            Control control = makeControl(1L, "HR-001", "IN_PROGRESS");
            ControlAssignmentDTO assignment = emptyAssignment(); // пустое назначение

            ControlPermission perm = permissionService.resolve(control, user, assignment);

            assertThat(perm.canView()).isFalse();
            assertThat(perm.canEdit()).isFalse();
        }

        @Test
        @DisplayName("Facilitator НЕ может редактировать при статусе REVIEW (не его очередь)")
        void facilitatorCannotEditWhenStatusIsReview() {
            User user = makeUser(FAC_EMAIL, "FACILITATOR", false);
            Control control = makeControl(1L, "HR-001", "REVIEW");
            ControlAssignmentDTO assignment = assignmentWithFacilitator(FAC_EMAIL);

            ControlPermission perm = permissionService.resolve(control, user, assignment);

            // Может видеть, но поля для редактирования пусты
            assertThat(perm.canView()).isTrue();
            assertThat(perm.canEdit()).isFalse();
            assertThat(perm.getAllowedEditableFields()).isEmpty();
        }

        @Test
        @DisplayName("Facilitator не является Control Operator и наоборот")
        void facilitatorIsNotControlOperator() {
            User user = makeUser(FAC_EMAIL, "FACILITATOR", false);
            Control control = makeControl(1L, "HR-001", "IN_PROGRESS");
            ControlAssignmentDTO assignment = assignmentWithFacilitator(FAC_EMAIL);

            ControlPermission perm = permissionService.resolve(control, user, assignment);

            assertThat(perm.isFacilitator()).isTrue();
            assertThat(perm.isControlOperator()).isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. CONTROL OPERATOR — редактирует при статусе REVIEW
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Control Operator — права назначенного оператора")
    class ControlOperatorTests {

        @Test
        @DisplayName("Control Operator может редактировать Steps Performed при статусе REVIEW")
        void controlOperatorCanEditStepsAtReviewStatus() {
            User user = makeUser(CO_EMAIL, "CONTROL_OPERATOR", false);
            Control control = makeControl(1L, "HR-001", "REVIEW");
            ControlAssignmentDTO assignment = assignmentWithControlOperator(CO_EMAIL);

            ControlPermission perm = permissionService.resolve(control, user, assignment);

            assertThat(perm.canView()).isTrue();
            assertThat(perm.canEdit()).isTrue();
            assertThat(perm.getAllowedEditableFields())
                    .contains(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED);
        }

        @Test
        @DisplayName("Control Operator НЕ может редактировать при IN_PROGRESS (ещё не его очередь)")
        void controlOperatorCannotEditAtInProgressStatus() {
            User user = makeUser(CO_EMAIL, "CONTROL_OPERATOR", false);
            Control control = makeControl(1L, "HR-001", "IN_PROGRESS");
            ControlAssignmentDTO assignment = assignmentWithControlOperator(CO_EMAIL);

            ControlPermission perm = permissionService.resolve(control, user, assignment);

            assertThat(perm.canEdit()).isFalse();
            assertThat(perm.getAllowedEditableFields()).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. PROCESS OWNER — редактирует комментарии при PROCESS_OWNER_REVIEW
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Process Owner — права владельца процесса")
    class ProcessOwnerTests {

        @Test
        @DisplayName("Process Owner может редактировать свои комментарии при PROCESS_OWNER_REVIEW")
        void processOwnerCanEditCommentsAtCorrectStatus() {
            User user = makeUser(PO_EMAIL, "PROCESS_OWNER", false);
            Control control = makeControl(1L, "HR-001", "PROCESS_OWNER_REVIEW");
            ControlAssignmentDTO assignment = assignmentWithProcessOwner(PO_EMAIL);

            ControlPermission perm = permissionService.resolve(control, user, assignment);

            assertThat(perm.canView()).isTrue();
            assertThat(perm.canEdit()).isTrue();
            assertThat(perm.getAllowedEditableFields())
                    .contains(ControlPermission.FIELD_PROCESS_OWNER_COMMENTS);
            assertThat(perm.getAllowedEditableFields())
                    .doesNotContain(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED);
        }

        @Test
        @DisplayName("Process Owner НЕ может редактировать при REVIEW (не его очередь)")
        void processOwnerCannotEditAtReviewStatus() {
            User user = makeUser(PO_EMAIL, "PROCESS_OWNER", false);
            Control control = makeControl(1L, "HR-001", "REVIEW");
            ControlAssignmentDTO assignment = assignmentWithProcessOwner(PO_EMAIL);

            ControlPermission perm = permissionService.resolve(control, user, assignment);

            assertThat(perm.canEdit()).isFalse();
            assertThat(perm.getAllowedEditableFields()).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. SHARED VIEWER — ограниченный доступ на COMPLETED контроль
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Shared Viewer — доступ к завершённым контролям")
    class SharedViewerTests {

        @Test
        @DisplayName("Shared viewer с ролью FACILITATOR может редактировать Steps в COMPLETED контроле")
        void sharedFacilitatorCanEditStepsOnCompletedControl() {
            User user = makeUser(FAC_EMAIL, "FACILITATOR", false);
            Control control = makeControl(1L, "HR-001", "COMPLETED");

            ControlAssignmentDTO assignment = new ControlAssignmentDTO();
            assignment.setControlSharedWith(List.of(FAC_EMAIL)); // добавлен как shared

            ControlPermission perm = permissionService.resolve(control, user, assignment);

            assertThat(perm.canView()).isTrue();
            assertThat(perm.isSharedViewer()).isTrue();
            assertThat(perm.isSharedCompleted()).isTrue();
            assertThat(perm.getAllowedEditableFields())
                    .contains(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED);
        }

        @Test
        @DisplayName("Shared viewer НЕ может использовать workflow actions на COMPLETED контроле")
        void sharedViewerCannotUseWorkflowActionsOnCompletedControl() {
            User user = makeUser(OTHER_EMAIL, "FACILITATOR", false);
            Control control = makeControl(1L, "HR-001", "COMPLETED");

            ControlAssignmentDTO assignment = new ControlAssignmentDTO();
            assignment.setControlSharedWith(List.of(OTHER_EMAIL));

            ControlPermission perm = permissionService.resolve(control, user, assignment);

            assertThat(perm.canUseWorkflowActions()).isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 6. Null-safety и граничные случаи
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Граничные случаи и null-safety")
    class EdgeCaseTests {

        @Test
        @DisplayName("resolve(null, user) возвращает denied permission")
        void nullControlReturnsDenied() {
            User user = makeUser(FAC_EMAIL, "FACILITATOR", false);
            ControlPermission perm = permissionService.resolve((Control) null, user, null);

            assertThat(perm.canView()).isFalse();
            assertThat(perm.canEdit()).isFalse();
        }

        @Test
        @DisplayName("resolve(control, null) возвращает denied permission")
        void nullUserReturnsDenied() {
            Control control = makeControl(1L, "HR-001", "IN_PROGRESS");
            ControlPermission perm = permissionService.resolve(control, null, null);

            assertThat(perm.canView()).isFalse();
            assertThat(perm.canEdit()).isFalse();
        }

        @Test
        @DisplayName("resolve(controlId=null, user) возвращает denied permission")
        void nullControlIdReturnsDenied() {
            User user = makeUser(FAC_EMAIL, "FACILITATOR", false);
            ControlPermission perm = permissionService.resolve((Long) null, user);

            assertThat(perm.canView()).isFalse();
        }

        @Test
        @DisplayName("Пользователь с email в разном регистре считается тем же человеком")
        void emailMatchingIsCaseInsensitive() {
            User user = makeUser("Facilitator@Test.COM", "FACILITATOR", false);
            Control control = makeControl(1L, "HR-001", "IN_PROGRESS");
            ControlAssignmentDTO assignment = assignmentWithFacilitator("facilitator@test.com");

            ControlPermission perm = permissionService.resolve(control, user, assignment);

            assertThat(perm.isFacilitator()).isTrue();
            assertThat(perm.canView()).isTrue();
        }

        @Test
        @DisplayName("Контроль без статуса трактуется как DRAFT — никто не редактирует")
        void controlWithNullStatusTreatedAsDraft() {
            User user = makeUser(FAC_EMAIL, "FACILITATOR", false);
            Control control = makeControl(1L, "HR-001", null); // null status
            ControlAssignmentDTO assignment = assignmentWithFacilitator(FAC_EMAIL);

            ControlPermission perm = permissionService.resolve(control, user, assignment);

            assertThat(perm.canView()).isTrue();
            assertThat(perm.getAllowedEditableFields()).isEmpty();
            assertThat(perm.canEdit()).isFalse();
        }
    }

    // ─── Вспомогательные методы ─────────────────────────────────────────

    private User makeUser(String email, String role, boolean adminAccess) {
        User user = new User();
        user.setMail(email);
        user.setRole(role);
        user.setAdminAccess(adminAccess);
        return user;
    }

    private Control makeControl(Long id, String controlId, String status) {
        Control control = new Control();
        control.setId(id);
        control.setControlId(controlId);
        control.setPerformanceStatus(status);
        return control;
    }

    private ControlAssignmentDTO emptyAssignment() {
        return new ControlAssignmentDTO();
    }

    private ControlAssignmentDTO assignmentWithFacilitator(String email) {
        ControlAssignmentDTO dto = new ControlAssignmentDTO();
        dto.setFacilitator(List.of(email));
        return dto;
    }

    private ControlAssignmentDTO assignmentWithControlOperator(String email) {
        ControlAssignmentDTO dto = new ControlAssignmentDTO();
        dto.setControlOperator(List.of(email));
        return dto;
    }

    private ControlAssignmentDTO assignmentWithProcessOwner(String email) {
        ControlAssignmentDTO dto = new ControlAssignmentDTO();
        dto.setProcessOwner(List.of(email));
        return dto;
    }
}
