package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlResponseDTO;
import com.kpmg.qtracker.dto.PerformanceDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.support.TestUsers;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDocumentsRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.repository.WorkflowStepRepository;

import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.ComponentControlsList;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.ControlDetailsService;
import com.kpmg.qtracker.service.DashboardService;
import com.kpmg.qtracker.service.ControlDocumentsService;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.IControlService;
import com.kpmg.qtracker.service.IPerformanceService;
import com.kpmg.qtracker.service.NotificationService;
import com.kpmg.qtracker.service.PermissionService;
import com.kpmg.qtracker.service.UserService;
import com.kpmg.qtracker.service.WorkflowService;
import com.kpmg.qtracker.service.WorkflowTransitionGuard;
import com.kpmg.qtracker.util.NotificationTypeDisplayMapper;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = ViewController.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Import(WorkflowTransitionGuard.class)
@AutoConfigureMockMvc(addFilters = false)
class ViewControllerStatusFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private IControlService controlService;

    @MockBean
    private IPerformanceService performanceService;

    @MockBean
    private DashboardService dashboardService;

    @MockBean
    private ControlAssignmentService controlAssignmentService;

    @MockBean
    private WorkflowService workflowService;

    @MockBean
    private ControlAssignmentRepository controlAssignmentRepository;

    @MockBean
    private ControlDetailsService controlDetailsService;

    @MockBean
    private ControlDocumentsRepository controlDocumentsRepository;

    @MockBean
    private ControlDocumentsService controlDocumentsService;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private NotificationTypeDisplayMapper notificationTypeDisplayMapper;

    @MockBean
    private WorkflowHistoryRepository workflowHistoryRepository;

    @MockBean
    private WorkflowStepRepository workflowStepRepository;

    @MockBean
    private ControlPermissionService controlPermissionService;

    @MockBean
    private PermissionService permissionService;

    @MockBean(name = "statusDisplayMapper")
    private StatusDisplayMapper statusDisplayMapper;

    @MockBean
    private com.kpmg.qtracker.service.ComponentControlsExcel componentControlsExcel;

    @Test
    void actionCentre_nonSoqm_redirectsToTheDashboardTab() throws Exception {
        User currentUser = new User();
        currentUser.setId(7L);
        TestUsers.withRole(currentUser, "FACILITATOR");
        currentUser.setMail("fac@kpmg.kz");

        mockMvc.perform(get("/action-centre").sessionAttr("currentUser", currentUser))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/#action-centre"));
    }

    @Test
    void actionCentre_soqm_redirectsToTheDashboardTab() throws Exception {
        User currentUser = new User();
        currentUser.setId(8L);
        TestUsers.withRole(currentUser, "SOQM_TEAM");
        currentUser.setMail("soqm@kpmg.kz");

        mockMvc.perform(get("/action-centre").sessionAttr("currentUser", currentUser))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/#action-centre"));
    }

    @Test
    void viewControl_draft_notVisibleToNonSoqm() throws Exception {
        User currentUser = new User();
        currentUser.setId(4L);
        TestUsers.withRole(currentUser, "FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        Control draftControl = new Control();
        draftControl.setId(30L);
        draftControl.setPerformanceStatus("IN_PROGRESS");

        when(controlService.getControlById(30L)).thenReturn(java.util.Optional.of(draftControl));
        ControlAssignmentDTO assignmentDTO = new ControlAssignmentDTO();
        when(controlAssignmentService.getAssignmentByControlId(30L)).thenReturn(assignmentDTO);
        when(permissionService.resolve(draftControl, currentUser, assignmentDTO))
                .thenReturn(ControlPermission.denied());
        when(permissionService.readAccess(eq(draftControl), eq(currentUser), any()))
                .thenReturn(AccessPolicy.ReadAccess.DENIED);

        mockMvc.perform(get("/view-control/30")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/403"))
                .andExpect(content().string(containsString("Access denied")));
    }

    @Test
    void viewControl_draft_visibleToSoqm() throws Exception {
        User currentUser = new User();
        currentUser.setId(5L);
        TestUsers.withRole(currentUser, "SOQM_TEAM");
        currentUser.setMail("soqm@kpmg.kz");
        currentUser.setDisplayName("SoQM User");

        Control draftControl = new Control();
        draftControl.setId(31L);
        draftControl.setControlStatus("DRAFT");
        User createdBy = new User();
        createdBy.setDisplayName("Creator User");
        createdBy.setMail("creator@kpmg.kz");
        draftControl.setCreatedBy(createdBy);

        when(controlService.getControlById(31L)).thenReturn(java.util.Optional.of(draftControl));
        ControlAssignmentDTO assignmentDTO = new ControlAssignmentDTO();
        when(controlAssignmentService.getAssignmentByControlId(31L)).thenReturn(assignmentDTO);
        when(permissionService.resolve(draftControl, currentUser, assignmentDTO))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, true,
                        false, false, false, true, false));
        when(permissionService.readAccess(eq(draftControl), eq(currentUser), any()))
                .thenReturn(AccessPolicy.ReadAccess.ALLOWED);

        mockMvc.perform(get("/view-control/31")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk());
    }

    @Test
    void viewControl_draft_sharedUser_getsFriendlyNotAvailablePage() throws Exception {
        User currentUser = new User();
        currentUser.setId(32L);
        TestUsers.withRole(currentUser, "CONTROL_OPERATOR");
        currentUser.setMail("shared.operator@kpmg.kz");
        currentUser.setDisplayName("Shared Operator");

        User creator = new User();
        creator.setMail("creator@kpmg.kz");
        creator.setDisplayName("Creator");

        Control draftControl = new Control();
        draftControl.setId(32L);
        draftControl.setControlId("QT-2026-001");
        draftControl.setPerformanceStatus("DRAFT");
        draftControl.setCreatedBy(creator);

        ControlAssignmentDTO assignmentDTO = new ControlAssignmentDTO();
        assignmentDTO.setControlSharedWith(List.of("shared.operator@kpmg.kz"));

        when(controlService.getControlById(32L)).thenReturn(java.util.Optional.of(draftControl));
        when(controlAssignmentService.getAssignmentByControlId(32L)).thenReturn(assignmentDTO);
        when(permissionService.resolve(draftControl, currentUser, assignmentDTO))
                .thenReturn(new ControlPermission(
                        true,
                        false,
                        java.util.Set.of(),
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        false
                ));
        when(permissionService.readAccess(eq(draftControl), eq(currentUser), any()))
                .thenReturn(AccessPolicy.ReadAccess.DRAFT_NOT_INITIATED);

        mockMvc.perform(get("/view-control/32")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andExpect(view().name("control-not-available"))
                .andExpect(content().string(containsString("Control Not Available Yet")))
                .andExpect(content().string(containsString("Back to Dashboard")))
                .andExpect(content().string(not(containsString("QT-2026-001"))));
    }

    @Test
    void viewControl_notFound_returns404Page() throws Exception {
        User currentUser = new User();
        currentUser.setId(33L);
        TestUsers.withRole(currentUser, "FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        when(controlService.getControlById(999L)).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/view-control/999")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"))
                .andExpect(content().string(containsString("Page not found")));
    }

    @Test
    void viewControl_normalAccess_returnsControlPage() throws Exception {
        User currentUser = new User();
        currentUser.setId(34L);
        TestUsers.withRole(currentUser, "FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        User creator = new User();
        creator.setMail("creator@kpmg.kz");
        creator.setDisplayName("Creator");

        Control control = new Control();
        control.setId(34L);
        control.setControlId("QT-2026-034");
        control.setPerformanceStatus("IN_PROGRESS");
        control.setCreatedBy(creator);

        ControlAssignmentDTO assignmentDTO = new ControlAssignmentDTO();
        assignmentDTO.setFacilitator(List.of("facilitator@kpmg.kz"));
        assignmentDTO.setControlOperationDeadline(
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).minusDays(2));

        when(controlService.getControlById(34L)).thenReturn(java.util.Optional.of(control));
        when(controlAssignmentService.getAssignmentByControlId(34L)).thenReturn(assignmentDTO);
        when(permissionService.resolve(control, currentUser, assignmentDTO))
                .thenReturn(new ControlPermission(
                        true,
                        true,
                        java.util.Set.of(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED),
                        true,
                        false,
                        false,
                        true,
                        false,
                        false,
                        false
                ));
        when(permissionService.readAccess(eq(control), eq(currentUser), any()))
                .thenReturn(AccessPolicy.ReadAccess.ALLOWED);

        mockMvc.perform(get("/view-control/34")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andExpect(view().name("view-control"))
                // header: shared sidebar, your-turn and overdue badges, workflow stepper on step 1
                .andExpect(content().string(containsString("class=\"col-md-2 sidebar p-3\"")))
                .andExpect(content().string(containsString("vc-badge vc-your-turn")))
                .andExpect(content().string(containsString("vc-badge vc-status-overdue")))
                .andExpect(content().string(containsString("aria-current=\"step\"")))
                .andExpect(content().string(containsString(">Creator · ")));
    }

    @Test
    void performanceChecklistUrl_redirectsToInitiatePage() throws Exception {
        User currentUser = new User();
        currentUser.setId(22L);
        TestUsers.withRole(currentUser, "FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");

        mockMvc.perform(get("/performance/18").sessionAttr("currentUser", currentUser))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/initiate/18"));
    }

    @Test
    void dashboard_counters_includeTheDraftsTheUserSees() throws Exception {
        User currentUser = new User();
        currentUser.setId(6L);
        TestUsers.withRole(currentUser, "FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        ControlResponseDTO draftControl = new ControlResponseDTO();
        draftControl.setId(40L);
        draftControl.setControlStatus("DRAFT");
        draftControl.setCreatedByEmail("facilitator@kpmg.kz");
        draftControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(2));

        ControlResponseDTO activeControl = new ControlResponseDTO();
        activeControl.setId(41L);
        activeControl.setControlStatus("IN_PROGRESS");
        activeControl.setFacilitators(List.of("facilitator@kpmg.kz"));
        activeControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        mockVisibleControls(currentUser, List.of(draftControl, activeControl));
        when(notificationService.countUnread(6L)).thenReturn(0L);

        MvcResult result = mockMvc.perform(get("/")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getModelAndView().getModel().get("totalControls")).isEqualTo(2);
        assertThat(result.getModelAndView().getModel().get("activeControls")).isEqualTo(2);
    }

    @Test
    void dashboard_activeCountsAllNonCompletedVisibleControls() throws Exception {
        User currentUser = new User();
        currentUser.setId(17L);
        TestUsers.withRole(currentUser, "FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        ControlResponseDTO reviewControl = new ControlResponseDTO();
        reviewControl.setId(42L);
        reviewControl.setControlStatus("REVIEW");
        reviewControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(2));

        ControlResponseDTO inProgressControl = new ControlResponseDTO();
        inProgressControl.setId(43L);
        inProgressControl.setControlStatus("IN_PROGRESS");
        inProgressControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        ControlResponseDTO completedControl = new ControlResponseDTO();
        completedControl.setId(44L);
        completedControl.setControlStatus("COMPLETED");
        completedControl.setCreatedAt(java.time.LocalDateTime.now().minusHours(10));

        mockVisibleControls(currentUser, List.of(reviewControl, inProgressControl, completedControl));
        when(notificationService.countUnread(17L)).thenReturn(0L);

        MvcResult result = mockMvc.perform(get("/")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getModelAndView().getModel().get("totalControls")).isEqualTo(3);
        assertThat(result.getModelAndView().getModel().get("activeControls")).isEqualTo(2);
        assertThat(result.getModelAndView().getModel().get("completedControls")).isEqualTo(1);
    }

    @Test
    void dashboard_overdueCountsTheUsersOverdueDrafts_butNotCompleted() throws Exception {
        User currentUser = new User();
        currentUser.setId(9L);
        TestUsers.withRole(currentUser, "FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        java.time.LocalDate yesterday =
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).minusDays(1);

        ControlResponseDTO overdueControl = new ControlResponseDTO();
        overdueControl.setId(50L);
        overdueControl.setControlStatus("IN_PROGRESS");
        overdueControl.setDeadline(yesterday);
        overdueControl.setFacilitators(List.of("facilitator@kpmg.kz"));
        overdueControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(2));

        ControlResponseDTO completedControl = new ControlResponseDTO();
        completedControl.setId(51L);
        completedControl.setControlStatus("COMPLETED");
        completedControl.setDeadline(yesterday);
        completedControl.setFacilitators(List.of("facilitator@kpmg.kz"));
        completedControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        ControlResponseDTO draftControl = new ControlResponseDTO();
        draftControl.setId(52L);
        draftControl.setControlStatus("DRAFT");
        draftControl.setDeadline(yesterday);
        draftControl.setFacilitators(List.of("facilitator@kpmg.kz"));
        draftControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        mockVisibleControls(currentUser, List.of(overdueControl, completedControl, draftControl));

        MvcResult result = mockMvc.perform(get("/")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getModelAndView().getModel().get("overdueControls")).isEqualTo(2);
    }

    @Test
    void componentAll_soqmDelegateCountersUseAllControls() throws Exception {
        User currentUser = new User();
        currentUser.setId(24L);
        TestUsers.withRole(currentUser, "SOQM_DELEGATE");
        currentUser.setMail("soqm.delegate@kpmg.kz");
        currentUser.setDisplayName("SoQM Delegate");

        java.time.LocalDate yesterday =
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).minusDays(1);
        java.time.LocalDate tomorrow =
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).plusDays(1);

        ControlResponseDTO draftControl = new ControlResponseDTO();
        draftControl.setId(120L);
        draftControl.setControlStatus("DRAFT");
        draftControl.setComponent("HR");
        draftControl.setDeadline(tomorrow);
        draftControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(3));

        ControlResponseDTO inProgressOverdue = new ControlResponseDTO();
        inProgressOverdue.setId(121L);
        inProgressOverdue.setControlStatus("IN_PROGRESS");
        inProgressOverdue.setComponent("INTR");
        inProgressOverdue.setDeadline(yesterday);
        inProgressOverdue.setCreatedAt(java.time.LocalDateTime.now().minusDays(2));

        ControlResponseDTO completedControl = new ControlResponseDTO();
        completedControl.setId(122L);
        completedControl.setControlStatus("COMPLETED");
        completedControl.setComponent("RER");
        completedControl.setDeadline(yesterday);
        completedControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        mockVisibleControls(currentUser, List.of(draftControl, inProgressOverdue, completedControl));

        // All components: every control the delegate sees, the draft too; Active as the tile counts it
        ComponentControlsList.Result all = listOf(currentUser, "/component/ALL");
        assertThat(all.rows()).hasSize(3);
        assertThat(all.counts().total()).isEqualTo(3);
        assertThat(all.counts().active()).isEqualTo(1);
        assertThat(all.counts().completed()).isEqualTo(1);
        assertThat(all.counts().overdue()).isEqualTo(1);
        assertThat(listOf(currentUser, "/component/ALL?status=ACTIVE").rows()).hasSize(1);
    }

    @Test
    void dashboard_showsControlsAwaitingUserAction_overdueFirst_andSharedSidebar() throws Exception {
        User currentUser = new User();
        currentUser.setId(20L);
        TestUsers.withRole(currentUser, "FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty"));

        ControlResponseDTO dueLater = new ControlResponseDTO();
        dueLater.setId(201L);
        dueLater.setControlId("HR-CTRL-MF-201");
        dueLater.setPerformanceStatus("IN_PROGRESS");
        dueLater.setFacilitators(List.of("facilitator@kpmg.kz"));
        dueLater.setDeadline(today.plusDays(5));

        ControlResponseDTO overdue = new ControlResponseDTO();
        overdue.setId(202L);
        overdue.setControlId("HR-CTRL-MF-202");
        overdue.setPerformanceStatus("IN_PROGRESS");
        overdue.setFacilitators(List.of("Facilitator@KPMG.kz"));
        overdue.setDeadline(today.minusDays(2));

        ControlResponseDTO someoneElsesStep = new ControlResponseDTO();
        someoneElsesStep.setId(203L);
        someoneElsesStep.setControlId("HR-CTRL-MF-203");
        someoneElsesStep.setPerformanceStatus("REVIEW");
        someoneElsesStep.setFacilitators(List.of("facilitator@kpmg.kz"));
        someoneElsesStep.setControlOperators(List.of("operator@kpmg.kz"));

        mockVisibleControls(currentUser, List.of(dueLater, overdue, someoneElsesStep));

        MvcResult result = mockMvc.perform(get("/").sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(content().string(containsString("Awaiting my action")))
                .andExpect(content().string(containsString("href=\"/view-control/202\"")))
                .andExpect(content().string(not(containsString("href=\"/view-control/203\""))))
                .andExpect(content().string(not(containsString("href=\"/controls"))))
                .andExpect(content().string(containsString("href=\"/component/ALL?status=ACTIVE\"")))
                // The sidebar shows the name, the e-mail and the role (not Administrator or Participant)
                .andExpect(content().string(containsString("class=\"sidebar-user-role\"")))
                .andExpect(content().string(containsString(">User</span>")))
                .andExpect(content().string(not(containsString(">Participant<"))))
                .andExpect(content().string(not(containsString("Administrator"))))
                .andExpect(content().string(containsString("class=\"sidebar-avatar\" aria-hidden=\"true\">FU<")))
                .andExpect(content().string(not(containsString("sidebar-new-control"))))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> actionItems =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("actionItems");
        assertThat(actionItems).extracting(ControlResponseDTO::getId).containsExactly(202L, 201L);
        assertThat(result.getModelAndView().getModel().get("actionItemsOverdue")).isEqualTo(1L);
    }

    @Test
    void dashboard_showsFirstPageOfNotifications_with24hTime_andShowMore() throws Exception {
        User currentUser = new User();
        currentUser.setId(21L);
        TestUsers.withRole(currentUser, "FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");
        mockVisibleControls(currentUser, List.of());

        java.time.LocalDateTime base = java.time.LocalDateTime.of(2025, 3, 10, 15, 45);
        List<com.kpmg.qtracker.entity.Notification> notifications = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            com.kpmg.qtracker.entity.Notification n = new com.kpmg.qtracker.entity.Notification();
            n.setId((long) i + 1);
            n.setUserId(21L);
            n.setControlId(1L);
            n.setType("WORKFLOW_STEP");
            n.setTitle("Title " + i);
            n.setMessage("Message " + i);
            n.setIsRead(false);
            n.setCreatedAt(base.minusMinutes(i));
            notifications.add(n);
        }
        when(notificationService.getUserNotifications(21L)).thenReturn(notifications);
        when(notificationTypeDisplayMapper.map(any(), any()))
                .thenReturn(new NotificationTypeDisplayMapper.Display("Workflow Update", "badge-default"));

        MvcResult result = mockMvc.perform(get("/").sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">15:45<")))
                .andExpect(content().string(containsString("10.03.2025")))
                .andExpect(content().string(containsString("Showing 50 of 60")))
                .andExpect(content().string(containsString("notifLimit=100")))
                .andReturn();

        assertThat(result.getModelAndView().getModel().get("notificationsShown")).isEqualTo(50);

        mockMvc.perform(get("/").param("notifLimit", "100").sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Show more"))));
    }

    @Test
    void newControl_soqm_rendersFormWithSidebar_othersRedirected() throws Exception {
        User soqm = new User();
        soqm.setId(30L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm@kpmg.kz");
        soqm.setDisplayName("SoQM User");

        mockMvc.perform(get("/new-control").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andExpect(view().name("new-control"))
                .andExpect(content().string(containsString("id=\"controlForm\"")))
                .andExpect(content().string(containsString("class=\"col-md-2 sidebar p-3\"")))
                .andExpect(content().string(containsString("id=\"controlId-status\"")))
                .andExpect(content().string(containsString("IDs starting with KDN are visible to KDN users")));

        User facilitator = new User();
        facilitator.setId(31L);
        TestUsers.withRole(facilitator, "FACILITATOR");
        facilitator.setMail("facilitator@kpmg.kz");

        mockMvc.perform(get("/new-control").sessionAttr("currentUser", facilitator))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void dashboard_actionCentreSummarisesComponents() throws Exception {
        User soqm = new User();
        soqm.setId(50L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm@kpmg.kz");
        soqm.setDisplayName("SoQM User");

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty"));
        ControlResponseDTO hrOverdue = new ControlResponseDTO();
        hrOverdue.setId(501L);
        hrOverdue.setComponent("HR");
        hrOverdue.setPerformanceStatus("IN_PROGRESS");
        hrOverdue.setDeadline(today.minusDays(3));
        ControlResponseDTO hrCompleted = new ControlResponseDTO();
        hrCompleted.setId(502L);
        hrCompleted.setComponent("HR");
        hrCompleted.setPerformanceStatus("COMPLETED");
        ControlResponseDTO epActive = new ControlResponseDTO();
        epActive.setId(503L);
        epActive.setComponent("EP");
        epActive.setPerformanceStatus("REVIEW");
        epActive.setDeadline(today.plusDays(10));
        mockVisibleControls(soqm, List.of(hrOverdue, hrCompleted, epActive));

        MvcResult result = mockMvc.perform(get("/").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">Human Resources<")))
                .andExpect(content().string(containsString("<dt>Overdue</dt><dd>1</dd>")))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ViewController.ComponentSummary> summaries =
                (List<ViewController.ComponentSummary>) result.getModelAndView().getModel().get("componentSummaries");
        ViewController.ComponentSummary hr = summaries.stream().filter(c -> c.code().equals("HR")).findFirst().orElseThrow();
        assertThat(hr.total()).isEqualTo(2);
        assertThat(hr.overdue()).isEqualTo(1);
        assertThat(hr.completed()).isEqualTo(1);
        assertThat(hr.active()).isZero();
        assertThat(hr.inProgress()).isEqualTo(1);
        assertThat(hr.inReview()).isZero();
        assertThat(hr.viewAllLabel()).isEqualTo("View all HR controls");
        ViewController.ComponentSummary all =
                (ViewController.ComponentSummary) result.getModelAndView().getModel().get("componentSummaryAll");
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.active()).isEqualTo(1);
    }

    @Test
    void performanceCycle_showsRealHistory_allPeople_andCurrentStep() throws Exception {
        User soqm = new User();
        soqm.setId(60L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm@kpmg.kz");
        soqm.setDisplayName("SoQM User");

        Control control = new Control();
        control.setId(601L);
        control.setControlId("HR-CTRL-MF-601");
        control.setControlDescription("Monthly payroll check");
        control.setPerformanceStatus("REVIEW");
        User creator = new User();
        creator.setDisplayName("Control Creator");
        control.setCreatedBy(creator);
        control.setCreatedAt(java.time.LocalDateTime.of(2026, 8, 20, 11, 0));
        when(controlService.getControlById(601L)).thenReturn(java.util.Optional.of(control));
        when(permissionService.readAccess(eq(control), eq(soqm)))
                .thenReturn(AccessPolicy.ReadAccess.ALLOWED);
        when(performanceService.buildPerformanceDTO(control)).thenReturn(new PerformanceDTO());

        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setFacilitator(List.of("fac1@kpmg.kz", "fac2@kpmg.kz"));
        assignment.setControlOperator(List.of("op@kpmg.kz"));
        assignment.setControlOperationDeadline(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).minusDays(1));
        when(controlAssignmentService.getAssignmentByControlId(601L)).thenReturn(assignment);

        com.kpmg.qtracker.entity.WorkflowHistory initiated = new com.kpmg.qtracker.entity.WorkflowHistory();
        initiated.setActionType(com.kpmg.qtracker.enums.WorkflowActionType.INITIATE);
        initiated.setPerformedByName("SoQM User");
        initiated.setCreatedAt(java.time.LocalDateTime.of(2026, 9, 1, 9, 30));
        com.kpmg.qtracker.entity.WorkflowHistory submitted = new com.kpmg.qtracker.entity.WorkflowHistory();
        submitted.setActionType(com.kpmg.qtracker.enums.WorkflowActionType.SUBMIT_TO_OPERATOR);
        submitted.setPerformedByName("Facilitator One");
        submitted.setFromStep("IN_PROGRESS");
        submitted.setToStep("REVIEW");
        submitted.setCreatedAt(java.time.LocalDateTime.of(2026, 9, 5, 14, 0));
        when(workflowHistoryRepository.findByControlIdOrderByCreatedAtDesc(601L)).thenReturn(List.of(submitted, initiated));

        mockMvc.perform(get("/performance-cycle/601").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andExpect(view().name("performance-cycle"))
                .andExpect(content().string(containsString("01.09.2026 09:30")))
                .andExpect(content().string(containsString("05.09.2026 14:00 · Facilitator One")))
                .andExpect(content().string(containsString("fac1@kpmg.kz, fac2@kpmg.kz")))
                .andExpect(content().string(containsString(">Submitted to Control Operator<")))
                .andExpect(content().string(containsString("aria-current=\"step\"")))
                .andExpect(content().string(containsString("status-badge status-overdue")))
                .andExpect(content().string(containsString("href=\"/view-control/601\"")))
                .andExpect(content().string(containsString("<dt>Created</dt>")))
                .andExpect(content().string(containsString("20.08.2026 · Control Creator")))
                .andExpect(content().string(not(containsString("Actual Operation Date"))));
    }

    @Test
    void componentPage_all_everyComponentsControls_theSameNumbersAsTheAllCard() throws Exception {
        User soqm = new User();
        soqm.setId(78L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm-all@kpmg.kz");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty"));
        ControlResponseDTO hr = dto(781L, "HR-781", "IN_PROGRESS", today.minusDays(2));
        ControlResponseDTO ep = dto(782L, "EP-782", "REVIEW", null);
        ep.setComponent("EP");
        ControlResponseDTO gov = dto(783L, "KDN-783", "COMPLETED", null);
        gov.setComponent(" gov ");
        ControlResponseDTO unknown = dto(784L, "XX-784", "REVIEW", null);
        unknown.setComponent("KDN");
        mockVisibleControls(soqm, List.of(hr, ep, gov, unknown));

        for (String path : List.of("/component/All", "/component/ALL", "/component/all")) {
            MvcResult result = mockMvc.perform(get(path).sessionAttr("currentUser", soqm))
                    .andExpect(status().isOk())
                    .andExpect(view().name("component-controls"))
                    .andReturn();
            ComponentControlsList.Result list = (ComponentControlsList.Result) result.getModelAndView().getModel().get("list");
            assertThat(list.rows()).as(path).extracting(ComponentControlsList.Row::id)
                    .containsExactly(782L, 781L, 783L, 784L);
            assertThat(list.counts()).isEqualTo(new ComponentControlsList.Counts(4, 0, 1, 2, 1, 1));
            assertThat(result.getResponse().getContentAsString()).contains(
                    "<h1>Performance: All components (ALL)</h1>", "aria-current=\"page\">All components</span>",
                    "Every control you can see, of every component", "href=\"/component/ALL?sort=id&amp;dir=desc\"",
                    "XX-784");
        }

        // The "All components" card counts the same controls and leads here
        MvcResult dashboard = mockMvc.perform(get("/").sessionAttr("currentUser", soqm)).andExpect(status().isOk()).andReturn();
        ViewController.ComponentSummary card =
                (ViewController.ComponentSummary) dashboard.getModelAndView().getModel().get("componentSummaryAll");
        assertThat(card.counts()).isEqualTo(new ComponentControlsList.Counts(4, 0, 1, 2, 1, 1));
        assertThat(dashboard.getResponse().getContentAsString()).contains("<a class=\"ac-summary\" href=\"/component/ALL\">");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} -> {1}")
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "/controls|/component/ALL",
            "/controls?scope=all|/component/ALL",
            "/controls?scope=active|/component/ALL?status=ACTIVE",
            "/controls?filter=OVERDUE|/component/ALL?status=OVERDUE",
            "/controls?status=overdue|/component/ALL?status=OVERDUE",
            "/controls?filter=COMPLETED&scope=active|/component/ALL?status=COMPLETED",
            "/controls?status=SOQM_HEAD_REVIEW|/component/ALL?status=SOQM_HEAD_REVIEW",
            "/controls?status=nonsense|/component/ALL",
            "/controls?component=hr&filter=OVERDUE|/component/HR?status=OVERDUE",
            "/controls?component=A%26C|/component/A&C",
            "/controls?component=nonsense|/component/ALL",
            "/controls?kdn=1|/component/ALL?kdn=1",
            "/controls?kdn=1&status=IN_PROGRESS|/component/ALL?status=IN_PROGRESS&kdn=1",
            "/controls?q=HR-1|/component/ALL?q=HR-1"
    })
    void theControlsPage_isGone_itsAddressLeadsToTheList_withTheKnownParameters(String from, String to)
            throws Exception {
        User soqm = new User();
        soqm.setId(69L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm-old-link@kpmg.kz");

        mockMvc.perform(get(java.net.URI.create(from)).sessionAttr("currentUser", soqm))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(to));
    }

    @Test
    void allComponents_hasTheControlsPagesFilters_activeReopenedYourTurnKdn_andItsBadges() throws Exception {
        User facilitator = new User();
        facilitator.setId(68L);
        TestUsers.withRole(facilitator, "FACILITATOR");
        facilitator.setMail("fac-list@kpmg.kz");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty"));
        ControlResponseDTO mine = dto(681L, "HR-681", "IN_PROGRESS", today.plusDays(2));
        mine.setFacilitators(List.of("fac-list@kpmg.kz"));
        ControlResponseDTO overdue = dto(682L, "HR-682", "REVIEW", today.minusDays(1));
        ControlResponseDTO reopened = dto(683L, "KDN-683", "SOQM_HEAD_REVIEW", today.plusDays(20));
        reopened.setReopened(true);
        ControlResponseDTO late = dto(684L, "HR-684", "COMPLETED", today.minusDays(5));
        late.setControlStatus("SUPERSEDED");
        ControlResponseDTO noComponent = dto(685L, "XX-685", "DRAFT", null);
        noComponent.setComponent(null);
        mockVisibleControls(facilitator, List.of(mine, overdue, reopened, late, noComponent));
        when(workflowStepRepository.findLatestCompletedAtByControlIds(anyList()))
                .thenReturn(java.util.Collections.singletonList(new Object[]{684L, today.minusDays(1).atTime(10, 0)}));
        when(workflowHistoryRepository.findLatestCompletionTimestampByControlIds(anyList())).thenReturn(List.of());

        // Active as the dashboard tile counts it (open, not overdue), drafts included; Your turn: the step is theirs
        assertThat(rowIds(listOf(facilitator, "/component/ALL?status=ACTIVE"))).containsExactly(681L, 683L, 685L);
        assertThat(rowIds(listOf(facilitator, "/component/ALL?status=OVERDUE"))).containsExactly(682L);
        assertThat(rowIds(listOf(facilitator, "/component/ALL?status=REOPENED"))).containsExactly(683L);
        assertThat(rowIds(listOf(facilitator, "/component/ALL?status=YOUR_TURN"))).containsExactly(681L);
        ComponentControlsList.Result kdn = listOf(facilitator, "/component/ALL?kdn=1");
        assertThat(rowIds(kdn)).containsExactly(683L);
        assertThat(kdn.counts().total()).isEqualTo(1);

        MvcResult page = mockMvc.perform(get("/component/ALL").sessionAttr("currentUser", facilitator))
                .andExpect(status().isOk()).andReturn();
        ComponentControlsList.Result all = (ComponentControlsList.Result) page.getModelAndView().getModel().get("list");
        java.util.Map<Long, ComponentControlsList.Row> rows = new java.util.HashMap<>();
        all.rows().forEach(row -> rows.put(row.id(), row));
        assertThat(rows.get(681L).yourTurn()).isTrue();
        assertThat(rows.get(681L).dueSoon()).isTrue();
        assertThat(rows.get(682L).overdue()).isTrue();
        assertThat(rows.get(683L).reopened()).isTrue();
        // The KDN badge is SoQM Admin Team's only
        assertThat(rows.get(683L).kdn()).isFalse();
        assertThat(rows.get(684L).closedLate()).isTrue();
        assertThat(rows.get(684L).controlStatusBadge()).isEqualTo("Superseded");
        String html = page.getResponse().getContentAsString();
        assertThat(html).contains(">Your turn<", ">Due soon<", ">Reopened<", ">Closed late<", ">Superseded<",
                "id=\"ccKdn\" name=\"kdn\" value=\"1\"", "<option value=\"ACTIVE\">", "<option value=\"REOPENED\">",
                "<option value=\"YOUR_TURN\">")
                // Export: SoQM Admin Team only
                .doesNotContain("id=\"ccExport\"", ">KDN</span>", "href=\"/controls");

        User soqm = new User();
        soqm.setId(67L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm-list@kpmg.kz");
        mockVisibleControls(soqm, List.of(mine, overdue, reopened, late, noComponent));
        MvcResult soqmPage = mockMvc.perform(get("/component/ALL").param("status", "OVERDUE").param("q", "HR")
                        .sessionAttr("currentUser", soqm)).andExpect(status().isOk()).andReturn();
        assertThat(soqmPage.getResponse().getContentAsString())
                .contains("id=\"ccExport\"", "href=\"/component/ALL/export?q=HR&amp;status=OVERDUE\"");
        ComponentControlsList.Result soqmAll = listOf(soqm, "/component/ALL");
        assertThat(soqmAll.rows().stream().filter(ComponentControlsList.Row::kdn).map(ComponentControlsList.Row::id))
                .containsExactly(683L);
    }

    @Test
    void theListExport_isSoqmAdminTeamOnly_everyoneElseGets403() throws Exception {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty"));
        // Every User kind and KDN
        for (String kind : List.of("PARTICIPANT/OWN", "PARTICIPANT/ALL", "READ_ONLY/OWN", "READ_ONLY/ALL", "READ_ONLY/KDN")) {
            String[] parts = kind.split("/");
            User user = TestUsers.user("export-" + parts[0].toLowerCase() + "-" + parts[1].toLowerCase() + "@kpmg.kz",
                    com.kpmg.qtracker.enums.AccessLevel.valueOf(parts[0]),
                    com.kpmg.qtracker.enums.AccessScope.valueOf(parts[1]), false);
            user.setId(66L);
            for (String path : List.of("/component/ALL/export", "/component/HR/export", "/component/KDN/export")) {
                mockMvc.perform(get(path).sessionAttr("currentUser", user))
                        .andExpect(status().isForbidden())
                        .andExpect(content().string("Only SoQM Admin Team can export lists"));
            }
        }
        org.mockito.Mockito.verifyNoInteractions(componentControlsExcel);

        User soqm = new User();
        soqm.setId(65L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm-export@kpmg.kz");
        mockVisibleControls(soqm, List.of(dto(651L, "HR-651", "IN_PROGRESS", today.minusDays(1)),
                dto(652L, "HR-652", "COMPLETED", null)));
        mockMvc.perform(get("/component/ALL/export").param("status", "OVERDUE").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("attachment; filename=\"QTracker_ALL_" + today + ".xlsx\"")));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ComponentControlsList.Row>> rows = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(componentControlsExcel).write(org.mockito.ArgumentMatchers.eq("All components"), rows.capture(), any());
        assertThat(rows.getValue()).extracting(ComponentControlsList.Row::id).containsExactly(651L);
        mockMvc.perform(get("/component/NOPE/export").sessionAttr("currentUser", soqm)).andExpect(status().isNotFound());
    }

    private ComponentControlsList.Result listOf(User user, String path) throws Exception {
        MvcResult result = mockMvc.perform(get(java.net.URI.create(path)).sessionAttr("currentUser", user))
                .andExpect(status().isOk()).andReturn();
        return (ComponentControlsList.Result) result.getModelAndView().getModel().get("list");
    }

    private static List<Long> rowIds(ComponentControlsList.Result list) {
        return list.rows().stream().map(ComponentControlsList.Row::id).toList();
    }

    @Test
    void componentPage_unknownCode_leadsToAllComponents() throws Exception {
        User soqm = new User();
        soqm.setId(70L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm@kpmg.kz");

        mockMvc.perform(get("/component/NOPE").sessionAttr("currentUser", soqm))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/component/ALL"));
        mockMvc.perform(get("/component/HR"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/login"));
        mockMvc.perform(get("/component/All"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/login"));
    }

    @Test
    void componentPage_titleCrumbsTenColumns_namesInOneQuery_rowsOpenViewControl() throws Exception {
        User soqm = new User();
        soqm.setId(72L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm-component@kpmg.kz");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty"));
        ControlResponseDTO hr = dto(721L, "HR-CTRL-MF-151A/FY26/UZB/OCT", "PROCESS_OWNER_REVIEW", today.minusDays(1));
        hr.setControlType("Manual");
        hr.setControlFrequency("Monthly");
        hr.setControlCategory("Key");
        hr.setControlOperationDate(java.time.LocalDate.of(2026, 9, 4));
        hr.setFacilitators(List.of("anna@kpmg.kz", "new.person@kpmg.kz"));
        hr.setControlOperators(List.of("op@kpmg.kz"));
        hr.setProcessOwners(List.of("po@kpmg.kz"));
        hr.setSoqmLeads(List.of("lead@kpmg.kz"));
        ControlResponseDTO draft = dto(722L, "HR-DRAFT-1", "DRAFT", null);
        ControlResponseDTO other = dto(723L, "EP-1", "REVIEW", null);
        other.setComponent("EP");
        mockVisibleControls(soqm, List.of(hr, draft, other));
        when(userService.displayNamesByEmail(any())).thenReturn(Map.of(
                "anna@kpmg.kz", "Anna Petrova", "op@kpmg.kz", "Oleg Operator",
                "po@kpmg.kz", "Polina Owner", "lead@kpmg.kz", "Lena Lead"));

        MvcResult result = mockMvc.perform(get("/component/hr").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andExpect(view().name("component-controls"))
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("<h1>Performance: Human Resources (HR)</h1>",
                "<a href=\"/#action-centre\" class=\"app-breadcrumb-link\">Action Centre</a>",
                "aria-current=\"page\">Human Resources</span>",
                "href=\"/view-control/721\"", "data-href=\"/view-control/722\"",
                "Anna Petrova, new.person", "Oleg Operator", "Polina Owner", "Lena Lead", "04.09.2026", "Overdue")
                .doesNotContain("EP-1", "/initiate/722");
        java.util.regex.Matcher header = java.util.regex.Pattern
                .compile("<a class=\"sort-link\"[^>]*>\\s*<span>([^<]+)</span>").matcher(html);
        List<String> headers = new java.util.ArrayList<>();
        while (header.find()) {
            headers.add(header.group(1));
        }
        assertThat(headers).containsExactly("Control ID", "Control Type", "Control Frequency",
                "Facilitator / Preparer(s)", "Control Operator", "Process Owner", "SoQM Lead / Delegate",
                "Control Category", "Control Operation Date", "Performance Status");
        // one query for all the people, none per person
        verify(userService, times(1)).displayNamesByEmail(any());
        verify(userService, never()).getUserByEmail(any());

        ComponentControlsList.Result list = (ComponentControlsList.Result) result.getModelAndView().getModel().get("list");
        assertThat(list.rows()).extracting(ComponentControlsList.Row::id).containsExactly(721L, 722L);
        assertThat(list.counts()).isEqualTo(new ComponentControlsList.Counts(2, 1, 0, 1, 0, 1));
    }

    @Test
    void componentPage_phoneCardsAndAccessibility_markup() throws Exception {
        User soqm = new User();
        soqm.setId(77L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm-a11y@kpmg.kz");
        mockVisibleControls(soqm, List.of(dto(771L, "HR-771", "REVIEW", null)));

        String html = mockMvc.perform(get("/component/HR").param("sort", "status").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(
                "<caption class=\"visually-hidden\">Performance: Human Resources (HR)</caption>",
                "<th scope=\"col\" aria-sort=\"ascending\" data-col=\"status\">",
                "<th scope=\"col\" aria-sort=\"none\" data-col=\"id\">",
                "tabindex=\"0\"", "data-label=\"SoQM Lead / Delegate\"", "data-label=\"Performance Status\"",
                "<label for=\"ccSearch\">Search</label>", "<label for=\"ccStatus\">Status</label>",
                "<label for=\"ccSortPhone\">Sort by</label>",
                "<option value=\"/component/HR?sort=status&amp;dir=desc\">Performance Status, descending</option>",
                "title=\"Not set\">&mdash;</span>",
                "/css/component-controls.css?v=5", "/js/component-controls.js?v=2", "<div class=\"cc-badges\">");
        // the phone's "Sort by" is not sent with the form (no name): one sort parameter only
        assertThat(html).doesNotContain("name=\"sort\" id=\"ccSortPhone\"");
    }

    @Test
    void componentPage_codeWithAmpersand_inEveryLink() throws Exception {
        User soqm = new User();
        soqm.setId(73L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm-ac@kpmg.kz");
        ControlResponseDTO ac = dto(731L, "AC-1", "REVIEW", null);
        ac.setComponent("A&C");
        mockVisibleControls(soqm, List.of(ac));

        MvcResult result = mockMvc.perform(get("/component/a&c").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("Performance: Acceptance &amp; Continuance (A&amp;C)", "href=\"/component/A&amp;C?sort=id&amp;dir=desc\"",
                        "action=\"/component/A&amp;C\"", "href=\"/component/A&amp;C?status=OVERDUE\"", "href=\"/view-control/731\"");
    }

    @Test
    void componentPage_kdn_onlyKdnControls_sameColumns() throws Exception {
        User soqm = new User();
        soqm.setId(74L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm-kdn-list@kpmg.kz");
        mockVisibleControls(soqm, List.of(
                dto(741L, "KDN_RER-CTRL-MF-33A/FY26/Central/1Q", "IN_PROGRESS", null),
                dto(742L, "KDN_EP-CTRL-MF-109A/FY26/Central/DEC-SEP", "DRAFT", null),
                dto(743L, "HR-CTRL-MF-151A/FY26/UZB/OCT", "REVIEW", null),
                dto(744L, "X-KDN-12", "REVIEW", null)));

        MvcResult result = mockMvc.perform(get("/component/kdn").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getModelAndView().getModel()).containsEntry("kdnList", true).containsEntry("listCode", "KDN");
        ComponentControlsList.Result list = (ComponentControlsList.Result) result.getModelAndView().getModel().get("list");
        assertThat(list.rows()).extracting(ComponentControlsList.Row::id).containsExactly(742L, 741L);
        assertThat(result.getResponse().getContentAsString())
                .contains("<h1>Performance: KDN controls (KDN)</h1>", "Performance Status")
                .doesNotContain("HR-CTRL-MF-151A", "X-KDN-12");
    }

    @Test
    void componentPage_nothingToSee_andNothingMatching_sayWhy() throws Exception {
        User soqm = new User();
        soqm.setId(75L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm-empty@kpmg.kz");
        mockVisibleControls(soqm, List.of(dto(751L, "HR-751", "REVIEW", null)));

        assertThat(mockMvc.perform(get("/component/GOV").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("There are no controls in this component that you can see.", "Back to Action Centre")
                .doesNotContain("id=\"ccTable\"", "id=\"ccFilters\"");
        assertThat(mockMvc.perform(get("/component/HR").param("q", "nobody").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("No controls match your search or filter.", "Clear search and filter", "value=\"nobody\"")
                .doesNotContain("id=\"ccTable\"");
    }

    @Test
    void componentPage_loadingFails_anErrorInsteadOfNumbers() throws Exception {
        User soqm = new User();
        soqm.setId(76L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm-error@kpmg.kz");
        when(controlService.findVisibleControlsForUser(soqm)).thenThrow(new IllegalStateException("database down"));

        String html = mockMvc.perform(get("/component/HR").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("role=\"alert\"", "The controls could not be loaded.", "contact SoQM Admin Team",
                        "<h1>Performance: Human Resources (HR)</h1>")
                .doesNotContain("id=\"ccTable\"", "stat-num", "database down");
    }

    @Test
    void unexpectedError_pageShowsOnlyAGeneralText_causeGoesToTheLogWithTheCorrelationId(CapturedOutput output) throws Exception {
        User user = new User();
        user.setId(5L);
        TestUsers.withRole(user, "FACILITATOR");
        user.setMail("fac@kpmg.kz");
        when(notificationService.getUserNotifications(5L))
                .thenThrow(new IllegalStateException("JDBC failure on table notifications, host db-internal-01"));

        MDC.put("correlationId", "cid-500-test");
        try {
            mockMvc.perform(get("/notification/7").sessionAttr("currentUser", user))
                    .andExpect(status().isInternalServerError())
                    .andExpect(view().name("error/500"))
                    .andExpect(content().string(containsString("Something went wrong")))
                    .andExpect(content().string(not(containsString("JDBC"))))
                    .andExpect(content().string(not(containsString("db-internal-01"))))
                    .andExpect(content().string(not(containsString("/notification/7"))));
        } finally {
            MDC.remove("correlationId");
        }

        assertThat(output.getAll())
                .contains("Unexpected error on GET /notification/7 (correlationId=cid-500-test)")
                .contains("JDBC failure on table notifications, host db-internal-01");
    }

    @Test
    void notificationDetail_linksToViewControl_draftToItsAssignmentTab() throws Exception {
        User user = new User();
        user.setId(6L);
        TestUsers.withRole(user, "FACILITATOR");
        user.setMail("fac@kpmg.kz");
        user.setDisplayName("Fac User");
        when(notificationTypeDisplayMapper.map(any(), any()))
                .thenReturn(new NotificationTypeDisplayMapper.Display("Returned", "badge-default"));

        for (String status : List.of("IN_PROGRESS", "DRAFT")) {
            Control control = new Control();
            control.setId(41L);
            control.setControlId("CTRL-041");
            control.setComponent("HR");
            control.setPerformanceStatus(status);
            when(controlService.getControlById(41L)).thenReturn(java.util.Optional.of(control));
            com.kpmg.qtracker.entity.Notification notification = new com.kpmg.qtracker.entity.Notification();
            notification.setId(9L);
            notification.setUserId(6L);
            notification.setControlId(41L);
            notification.setType("RETURN_TO_FACILITATOR");
            notification.setTitle("Control CTRL-041 returned to you");
            notification.setMessage("Comment: attach the signed review");
            notification.setCreatedAt(java.time.LocalDateTime.of(2026, 10, 2, 14, 30));
            when(notificationService.getUserNotifications(6L)).thenReturn(List.of(notification));

            String expectedUrl = "DRAFT".equals(status) ? "/view-control/41#assignment" : "/view-control/41";
            mockMvc.perform(get("/notification/9").sessionAttr("currentUser", user))
                    .andExpect(status().isOk())
                    .andExpect(view().name("notification-detail"))
                    .andExpect(content().string(containsString("href=\"" + expectedUrl + "\"")))
                    .andExpect(content().string(containsString("Human Resources (HR)")))
                    .andExpect(content().string(containsString("Comment: attach the signed review")))
                    .andExpect(content().string(containsString("class=\"col-md-2 sidebar")))
                    .andExpect(content().string(not(containsString("/performance-cycle/"))))
                    .andExpect(content().string(not(containsString("System"))));
        }
    }

    private static ControlResponseDTO dto(long id, String controlId, String status, java.time.LocalDate deadline) {
        ControlResponseDTO dto = new ControlResponseDTO();
        dto.setId(id);
        dto.setControlId(controlId);
        dto.setComponent("HR");
        dto.setPerformanceStatus(status);
        dto.setDeadline(deadline);
        dto.setCreatedAt(java.time.LocalDateTime.now().minusDays(id % 100));
        return dto;
    }

    private static String actionCentre(String html) {
        return html.substring(html.indexOf("id=\"pane-action\""), html.indexOf("id=\"pane-notifications\""));
    }

    /** The Action Centre card whose link is {@code href} ({@code <a class="ac-card" href=...>...</a>}). */
    private static String card(String pane, String href) {
        int at = pane.indexOf("href=\"" + href + "\"");
        if (at < 0) {
            return null;
        }
        int start = pane.lastIndexOf("<a ", at);
        return pane.substring(start, pane.indexOf("</a>", at) + 4);
    }

    private static List<String> cardLinks(String pane) {
        java.util.regex.Matcher link = java.util.regex.Pattern.compile("<a class=\"ac-card[^\"]*\"[^>]*href=\"([^\"]+)\"")
                .matcher(pane);
        List<String> links = new java.util.ArrayList<>();
        while (link.find()) {
            links.add(link.group(1));
        }
        return links;
    }

    @Test
    void dashboard_kdnUser_kdnCardFirst_likeAComponent_andNoActionBlocks() throws Exception {
        User kdn = TestUsers.user("kdn-ac@kpmg.kz", com.kpmg.qtracker.enums.AccessLevel.READ_ONLY,
                com.kpmg.qtracker.enums.AccessScope.KDN, false);
        kdn.setId(91L);
        kdn.setDisplayName("KDN Reader");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty"));
        // KDN users are listed in a step field of old data: still no "Awaiting my action"
        ControlResponseDTO onIt = dto(914L, "KDN-914", "IN_PROGRESS", today.plusDays(9));
        onIt.setFacilitators(List.of("kdn-ac@kpmg.kz"));
        mockVisibleControls(kdn, List.of(
                dto(911L, "KDN-911", "DRAFT", null),
                dto(912L, "kdn-912", "IN_PROGRESS", today.minusDays(4)),
                dto(913L, "KDN-913", "COMPLETED", today.minusDays(9)),
                onIt));

        MvcResult result = mockMvc.perform(get("/").sessionAttr("currentUser", kdn))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString();
        String pane = actionCentre(html);

        assertThat(html).doesNotContain("<section class=\"action-queue\"", "id=\"actionQueueTitle\"",
                "awaiting your action", "Nothing is awaiting your action");
        assertThat(result.getModelAndView().getModel()).doesNotContainKeys("actionItems", "actionItemsTotal")
                .containsEntry("kdnUser", true);
        // The first card of the grid, the same card as a component's: badge, name, five counters, bar, link text
        assertThat(cardLinks(pane)).first().isEqualTo("/component/KDN");
        assertThat(card(pane, "/component/KDN")).contains("class=\"ac-card has-overdue\"",
                "<span class=\"ac-code\">KDN</span>", "<div class=\"ac-name\">KDN controls</div>",
                "<dt>Total</dt><dd>4</dd>", "<dt>In progress</dt><dd>2</dd>", "<dt>In review</dt><dd>0</dd>",
                "<dt>Completed</dt><dd>1</dd>", "<dt>Overdue</dt><dd>1</dd>", "View all KDN controls",
                "aria-label=\"25% completed, 1 overdue\"");
        ViewController.ComponentSummary summary =
                (ViewController.ComponentSummary) result.getModelAndView().getModel().get("kdnSummary");
        assertThat(summary).isEqualTo(new ViewController.ComponentSummary("KDN", "KDN controls",
                new ComponentControlsList.Counts(4, 1, 2, 0, 1, 1)));
    }

    @Test
    void dashboard_soqm_kdnCardLast_actionQueueKept_nonKdnNeverCounted() throws Exception {
        User soqm = new User();
        soqm.setId(92L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm-ac@kpmg.kz");
        soqm.setDisplayName("SoQM AC");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty"));
        List<ControlResponseDTO> dtos = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            dtos.add(dto(920L + i, String.format("KDN-%02d", i), "REVIEW", today.plusDays(i)));
        }
        dtos.add(dto(931L, "HR-931", "IN_PROGRESS", today.minusDays(3)));
        dtos.add(dto(932L, "X-KDN-932", "IN_PROGRESS", today.minusDays(3)));
        mockVisibleControls(soqm, dtos);

        MvcResult result = mockMvc.perform(get("/").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString();
        String pane = actionCentre(html);

        assertThat(html).contains("<section class=\"action-queue\"", "id=\"actionQueueTitle\"");
        assertThat(cardLinks(pane)).hasSize(11).last().isEqualTo("/component/KDN");
        assertThat(card(pane, "/component/KDN")).contains("<dt>Total</dt><dd>10</dd>", "<dt>In review</dt><dd>10</dd>",
                        "<dt>Completed</dt><dd>0</dd>", "<dt>Overdue</dt><dd>0</dd>")
                .doesNotContain("ac-stat-late", "has-overdue");
    }

    @Test
    void dashboard_noKdnControls_emptyCardForThoseWhoSeeThemAll_nothingForMyControls() throws Exception {
        User allRead = TestUsers.user("all-ac@kpmg.kz", com.kpmg.qtracker.enums.AccessLevel.READ_ONLY,
                com.kpmg.qtracker.enums.AccessScope.ALL, false);
        allRead.setId(93L);
        mockVisibleControls(allRead, List.of(dto(941L, "HR-941", "REVIEW", null)));
        String all = mockMvc.perform(get("/").sessionAttr("currentUser", allRead))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(card(actionCentre(all), "/component/KDN")).contains("class=\"ac-card is-empty\"",
                "<span class=\"ac-code\">KDN</span>", "No controls");

        User mine = TestUsers.user("mine-ac@kpmg.kz", com.kpmg.qtracker.enums.AccessLevel.PARTICIPANT,
                com.kpmg.qtracker.enums.AccessScope.OWN, false);
        mine.setId(94L);
        mockVisibleControls(mine, List.of(dto(942L, "HR-942", "REVIEW", null)));
        MvcResult none = mockMvc.perform(get("/").sessionAttr("currentUser", mine))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(none.getModelAndView().getModel()).doesNotContainKey("kdnSummary");
        assertThat(actionCentre(none.getResponse().getContentAsString())).doesNotContain("kdn=1", "KDN");

        mockVisibleControls(mine, List.of(dto(942L, "HR-942", "REVIEW", null), dto(943L, "KDN-943", "REVIEW", null)));
        String some = mockMvc.perform(get("/").sessionAttr("currentUser", mine))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(card(actionCentre(some), "/component/KDN")).contains("<dt>Total</dt><dd>1</dd>",
                "<dt>In review</dt><dd>1</dd>");
    }

    private void mockVisibleControls(User user, List<ControlResponseDTO> dtos) {
        List<Control> controls = new java.util.ArrayList<>();
        for (ControlResponseDTO dto : dtos) {
            Control control = new Control();
            control.setId(dto.getId());
            control.setPerformanceStatus(dto.getPerformanceStatus());
            control.setCreatedAt(dto.getCreatedAt());
            control.setUpdatedAt(dto.getUpdatedAt());
            control.setDeadline(dto.getDeadline());
            control.setComponent(dto.getComponent());
            controls.add(control);
        }
        when(controlService.findVisibleControlsForUser(user))
                .thenReturn(controls);
        when(controlService.convertToResponseDTOs(controls)).thenReturn(dtos);
    }
}

