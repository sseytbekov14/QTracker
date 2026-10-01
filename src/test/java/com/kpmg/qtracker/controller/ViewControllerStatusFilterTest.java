package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlResponseDTO;
import com.kpmg.qtracker.dto.PerformanceDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDocumentsRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.repository.WorkflowStepRepository;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = ViewController.class)
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

    @Test
    void controls_withStatusFilter_returnsOnlyMatchingStatus() throws Exception {
        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setRole("SOQM_TEAM");
        currentUser.setMail("soqm@kpmg.kz");
        currentUser.setDisplayName("SoQM User");

        ControlResponseDTO reviewControl = new ControlResponseDTO();
        reviewControl.setId(1L);
        reviewControl.setControlStatus("REVIEW");
        reviewControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        ControlResponseDTO inProgressControl = new ControlResponseDTO();
        inProgressControl.setId(2L);
        inProgressControl.setControlStatus("IN_PROGRESS");
        inProgressControl.setCreatedAt(java.time.LocalDateTime.now());

        mockVisibleControls(currentUser, List.of(reviewControl, inProgressControl));
        when(notificationService.countUnread(1L)).thenReturn(0L);

        MvcResult result = mockMvc.perform(get("/controls")
                        .param("scope", "all")
                        .param("status", "REVIEW")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");

        assertThat(controls).hasSize(1);
        assertThat(controls.get(0).getControlStatus()).isEqualTo("REVIEW");
    }

    @Test
    void controls_activeScope_forNonSoqm_returnsOnlyAssignedQueueControls() throws Exception {
        User currentUser = new User();
        currentUser.setId(2L);
        currentUser.setRole("FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        ControlResponseDTO assignedControl = new ControlResponseDTO();
        assignedControl.setId(10L);
        assignedControl.setControlStatus("IN_PROGRESS");
        assignedControl.setFacilitators(List.of("facilitator@kpmg.kz"));
        assignedControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(2));

        ControlResponseDTO otherControl = new ControlResponseDTO();
        otherControl.setId(11L);
        otherControl.setControlStatus("IN_PROGRESS");
        otherControl.setFacilitators(List.of("other@kpmg.kz"));
        otherControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        mockVisibleControls(currentUser, List.of(assignedControl, otherControl));
        when(notificationService.countUnread(2L)).thenReturn(0L);

        MvcResult result = mockMvc.perform(get("/controls")
                        .param("scope", "active")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");

        assertThat(controls).hasSize(1);
        assertThat(controls.get(0).getId()).isEqualTo(10L);
    }

    @Test
    void controls_overdueFilter_returnsOnlyOverdue() throws Exception {
        User currentUser = new User();
        currentUser.setId(12L);
        currentUser.setRole("FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        ControlResponseDTO overdueControl = new ControlResponseDTO();
        overdueControl.setId(60L);
        overdueControl.setControlStatus("IN_PROGRESS");
        overdueControl.setDeadline(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).minusDays(1));
        overdueControl.setFacilitators(List.of());
        overdueControl.setControlOperators(List.of());
        overdueControl.setSoqmLeads(List.of());
        overdueControl.setProcessOwners(List.of());
        overdueControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(2));

        ControlResponseDTO notOverdueControl = new ControlResponseDTO();
        notOverdueControl.setId(61L);
        notOverdueControl.setControlStatus("REVIEW");
        notOverdueControl.setDeadline(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).plusDays(1));
        notOverdueControl.setFacilitators(List.of());
        notOverdueControl.setControlOperators(List.of());
        notOverdueControl.setSoqmLeads(List.of());
        notOverdueControl.setProcessOwners(List.of());
        notOverdueControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        mockVisibleControls(currentUser, List.of(overdueControl, notOverdueControl));

        MvcResult result = mockMvc.perform(get("/controls")
                        .param("filter", "OVERDUE")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");

        assertThat(controls).hasSize(1);
        assertThat(controls.get(0).getId()).isEqualTo(60L);
    }

    @Test
    void controls_setsOverdueFlagForPastDeadline() throws Exception {
        User currentUser = new User();
        currentUser.setId(13L);
        currentUser.setRole("FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        java.time.LocalDate yesterday =
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).minusDays(1);

        ControlResponseDTO overdueControl = new ControlResponseDTO();
        overdueControl.setId(70L);
        overdueControl.setControlStatus("IN_PROGRESS");
        overdueControl.setDeadline(yesterday);
        overdueControl.setFacilitators(List.of());
        overdueControl.setControlOperators(List.of());
        overdueControl.setSoqmLeads(List.of());
        overdueControl.setProcessOwners(List.of());
        overdueControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(2));

        mockVisibleControls(currentUser, List.of(overdueControl));

        MvcResult result = mockMvc.perform(get("/controls")
                        .param("scope", "all")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");

        assertThat(controls).hasSize(1);
        assertThat(controls.get(0).isOverdue()).isTrue();
    }

    @Test
    void controls_completedAfterDeadline_isClosedLate_notOverdue() throws Exception {
        User currentUser = new User();
        currentUser.setId(14L);
        currentUser.setRole("FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        java.time.LocalDate deadline =
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).minusDays(2);

        ControlResponseDTO completedControl = new ControlResponseDTO();
        completedControl.setId(80L);
        completedControl.setControlStatus("COMPLETED");
        completedControl.setDeadline(deadline);
        completedControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(10));

        mockVisibleControls(currentUser, List.of(completedControl));
        when(workflowStepRepository.findLatestCompletedAtByControlIds(anyList()))
                .thenReturn(java.util.Collections.singletonList(new Object[]{80L, java.time.LocalDateTime.now().minusDays(1)}));
        when(workflowHistoryRepository.findLatestCompletionTimestampByControlIds(anyList()))
                .thenReturn(List.of());

        MvcResult result = mockMvc.perform(get("/controls")
                        .param("scope", "all")
                        .param("status", "COMPLETED")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");

        assertThat(controls).hasSize(1);
        assertThat(controls.get(0).isOverdue()).isFalse();
        assertThat(controls.get(0).isClosedLate()).isTrue();
        assertThat(result.getResponse().getContentAsString())
                .contains(">Closed late<")
                .contains("data-closed-late=\"true\"")
                .doesNotContain("title=\"Deadline passed\"");

        // The Overdue filter only lists controls that are still open
        MvcResult overdueResult = mockMvc.perform(get("/controls")
                        .param("filter", "OVERDUE")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> overdueControls =
                (List<ControlResponseDTO>) overdueResult.getModelAndView().getModel().get("controls");
        assertThat(overdueControls).isEmpty();
        assertThat(overdueResult.getModelAndView().getModel().get("overdueControls")).isEqualTo(0);
    }

    @Test
    void controls_completedBeforeDeadline_isNotOverdue() throws Exception {
        User currentUser = new User();
        currentUser.setId(15L);
        currentUser.setRole("FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        java.time.LocalDate deadline =
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).plusDays(1);

        ControlResponseDTO completedControl = new ControlResponseDTO();
        completedControl.setId(81L);
        completedControl.setControlStatus("COMPLETED");
        completedControl.setDeadline(deadline);
        completedControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(3));

        mockVisibleControls(currentUser, List.of(completedControl));
        when(workflowStepRepository.findLatestCompletedAtByControlIds(anyList()))
                .thenReturn(java.util.Collections.singletonList(new Object[]{81L, java.time.LocalDateTime.now().minusDays(1)}));
        when(workflowHistoryRepository.findLatestCompletionTimestampByControlIds(anyList()))
                .thenReturn(List.of());

        MvcResult result = mockMvc.perform(get("/controls")
                        .param("scope", "all")
                        .param("status", "COMPLETED")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");

        assertThat(controls).hasSize(1);
        assertThat(controls.get(0).isOverdue()).isFalse();
        assertThat(controls.get(0).isClosedLate()).isFalse();
    }

    @Test
    void controls_futureDeadline_isNotOverdue() throws Exception {
        User currentUser = new User();
        currentUser.setId(16L);
        currentUser.setRole("FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        ControlResponseDTO inProgressControl = new ControlResponseDTO();
        inProgressControl.setId(82L);
        inProgressControl.setControlStatus("IN_PROGRESS");
        inProgressControl.setDeadline(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).plusDays(5));
        inProgressControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        mockVisibleControls(currentUser, List.of(inProgressControl));
        when(workflowStepRepository.findLatestCompletedAtByControlIds(anyList()))
                .thenReturn(List.of());
        when(workflowHistoryRepository.findLatestCompletionTimestampByControlIds(anyList()))
                .thenReturn(List.of());

        MvcResult result = mockMvc.perform(get("/controls")
                        .param("scope", "all")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");

        assertThat(controls).hasSize(1);
        assertThat(controls.get(0).isOverdue()).isFalse();
    }

    @Test
    void controls_includesSharedControlsWithViewOnlyFlag() throws Exception {
        User currentUser = new User();
        currentUser.setId(20L);
        currentUser.setRole("FACILITATOR");
        currentUser.setMail("shared@kpmg.kz");
        currentUser.setDisplayName("Shared User");

        Control sharedControl = new Control();
        sharedControl.setId(200L);
        sharedControl.setPerformanceStatus("IN_PROGRESS");

        ControlResponseDTO sharedDto = new ControlResponseDTO();
        sharedDto.setId(200L);
        sharedDto.setControlId("SH-1");
        sharedDto.setPerformanceStatus("IN_PROGRESS");
        sharedDto.setCreatedAt(java.time.LocalDateTime.now());
        sharedDto.setFacilitators(List.of());
        sharedDto.setControlOperators(List.of());
        sharedDto.setSoqmLeads(List.of());
        sharedDto.setProcessOwners(List.of());

        ControlAssignmentDTO assignmentDTO = new ControlAssignmentDTO();
        assignmentDTO.setControlSharedWith(List.of("shared@kpmg.kz"));

        when(controlService.findVisibleControlsForUser("shared@kpmg.kz", "FACILITATOR"))
                .thenReturn(List.of(sharedControl));
        when(controlService.convertToResponseDTO(sharedControl)).thenReturn(sharedDto);
        when(controlAssignmentService.getAssignmentByControlId(200L)).thenReturn(assignmentDTO);

        MvcResult result = mockMvc.perform(get("/controls")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");

        assertThat(controls).hasSize(1);
        assertThat(controls.get(0).isSharedViewOnly()).isTrue();
    }

    @Test
    void controls_sortNewestFirst_controlsWithoutDatesLast() throws Exception {
        User soqm = new User();
        soqm.setId(90L);
        soqm.setRole("SOQM_TEAM");
        soqm.setMail("soqm@kpmg.kz");
        soqm.setDisplayName("SoQM User");

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        ControlResponseDTO noDates = listControl(901L);
        ControlResponseDTO createdLastWeek = listControl(902L);
        createdLastWeek.setCreatedAt(now.minusDays(7));
        ControlResponseDTO updatedToday = listControl(903L);
        updatedToday.setCreatedAt(now.minusDays(30));
        updatedToday.setUpdatedAt(now);
        ControlResponseDTO alsoNoDates = listControl(904L);
        mockVisibleControls(soqm, List.of(noDates, createdLastWeek, updatedToday, alsoNoDates));

        MvcResult result = mockMvc.perform(get("/controls").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");
        // Updated date wins over created date; controls with neither keep their order at the end
        assertThat(controls).extracting(ControlResponseDTO::getId).containsExactly(903L, 902L, 901L, 904L);
    }

    private ControlResponseDTO listControl(Long id) {
        ControlResponseDTO control = new ControlResponseDTO();
        control.setId(id);
        control.setControlStatus("REVIEW");
        control.setFacilitators(List.of());
        control.setControlOperators(List.of());
        control.setSoqmLeads(List.of());
        control.setProcessOwners(List.of());
        return control;
    }

    @Test
    void controls_allScope_forNonSoqm_excludesDraft() throws Exception {
        User currentUser = new User();
        currentUser.setId(3L);
        currentUser.setRole("CONTROL_OPERATOR");
        currentUser.setMail("operator@kpmg.kz");
        currentUser.setDisplayName("Operator User");

        ControlResponseDTO draftControl = new ControlResponseDTO();
        draftControl.setId(20L);
        draftControl.setControlStatus("DRAFT");
        draftControl.setCreatedByEmail("operator@kpmg.kz");
        draftControl.setFacilitators(List.of());
        draftControl.setControlOperators(List.of());
        draftControl.setSoqmLeads(List.of());
        draftControl.setProcessOwners(List.of());
        draftControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(2));

        ControlResponseDTO reviewControl = new ControlResponseDTO();
        reviewControl.setId(21L);
        reviewControl.setControlStatus("REVIEW");
        reviewControl.setCreatedByEmail("operator@kpmg.kz");
        reviewControl.setFacilitators(List.of());
        reviewControl.setControlOperators(List.of());
        reviewControl.setSoqmLeads(List.of());
        reviewControl.setProcessOwners(List.of());
        reviewControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        mockVisibleControls(currentUser, List.of(draftControl, reviewControl));
        when(notificationService.countUnread(3L)).thenReturn(0L);

        MvcResult result = mockMvc.perform(get("/controls")
                        .param("scope", "all")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");

        assertThat(controls).hasSize(1);
        assertThat(controls.get(0).getId()).isEqualTo(21L);
    }

    @Test
    void controls_completedStatusFilter_returnsOnlyCompleted() throws Exception {
        User currentUser = new User();
        currentUser.setId(14L);
        currentUser.setRole("CONTROL_OPERATOR");
        currentUser.setMail("operator@kpmg.kz");
        currentUser.setDisplayName("Operator User");

        ControlResponseDTO completedControl = new ControlResponseDTO();
        completedControl.setId(80L);
        completedControl.setControlStatus("COMPLETED");
        completedControl.setCreatedByEmail("operator@kpmg.kz");
        completedControl.setFacilitators(List.of());
        completedControl.setControlOperators(List.of());
        completedControl.setSoqmLeads(List.of());
        completedControl.setProcessOwners(List.of());
        completedControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(2));

        ControlResponseDTO inProgressControl = new ControlResponseDTO();
        inProgressControl.setId(81L);
        inProgressControl.setControlStatus("IN_PROGRESS");
        inProgressControl.setCreatedByEmail("operator@kpmg.kz");
        inProgressControl.setFacilitators(List.of());
        inProgressControl.setControlOperators(List.of());
        inProgressControl.setSoqmLeads(List.of());
        inProgressControl.setProcessOwners(List.of());
        inProgressControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        mockVisibleControls(currentUser, List.of(completedControl, inProgressControl));

        MvcResult result = mockMvc.perform(get("/controls")
                        .param("status", "COMPLETED")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");

        assertThat(controls).hasSize(1);
        assertThat(controls.get(0).getId()).isEqualTo(80L);
    }

    @Test
    void controls_defaultFilter_showsAllVisibleControls() throws Exception {
        User currentUser = new User();
        currentUser.setId(15L);
        currentUser.setRole("FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        ControlResponseDTO completedControl = new ControlResponseDTO();
        completedControl.setId(90L);
        completedControl.setControlStatus("COMPLETED");
        completedControl.setCreatedByEmail("facilitator@kpmg.kz");
        completedControl.setFacilitators(List.of());
        completedControl.setControlOperators(List.of());
        completedControl.setSoqmLeads(List.of());
        completedControl.setProcessOwners(List.of());
        completedControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(2));

        ControlResponseDTO reviewControl = new ControlResponseDTO();
        reviewControl.setId(91L);
        reviewControl.setControlStatus("REVIEW");
        reviewControl.setCreatedByEmail("facilitator@kpmg.kz");
        reviewControl.setFacilitators(List.of());
        reviewControl.setControlOperators(List.of());
        reviewControl.setSoqmLeads(List.of());
        reviewControl.setProcessOwners(List.of());
        reviewControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        mockVisibleControls(currentUser, List.of(completedControl, reviewControl));

        MvcResult result = mockMvc.perform(get("/controls")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");

        assertThat(controls).hasSize(2);
    }

    @Test
    void actionCentre_nonSoqm_countsActiveByComponent() throws Exception {
        User currentUser = new User();
        currentUser.setId(7L);
        currentUser.setRole("FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        User creator = new User();
        creator.setMail("facilitator@kpmg.kz");

        Control control1 = new Control();
        control1.setId(100L);
        control1.setComponent("HR");
        control1.setControlStatus("IN_PROGRESS");
        control1.setCreatedBy(creator);

        Control control2 = new Control();
        control2.setId(101L);
        control2.setComponent("INTR");
        control2.setControlStatus("IN_PROGRESS");
        control2.setCreatedBy(creator);

        Control control3 = new Control();
        control3.setId(102L);
        control3.setComponent("HR");
        control3.setControlStatus("COMPLETED");
        control3.setCreatedBy(creator);

        Control control4 = new Control();
        control4.setId(103L);
        control4.setComponent("HR");
        control4.setControlStatus("DRAFT");
        control4.setCreatedBy(creator);

        when(controlService.getAllControls())
                .thenReturn(List.of(control1, control2, control3, control4));

        MvcResult result = mockMvc.perform(get("/action-centre")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Long> componentStats =
                (Map<String, Long>) result.getModelAndView().getModel().get("componentStats");

        assertThat(componentStats.get("All")).isEqualTo(3L);
        assertThat(componentStats.get("HR")).isEqualTo(2L);
        assertThat(componentStats.get("INTR")).isEqualTo(1L);
    }

    @Test
    void actionCentre_soqm_countsAllControls() throws Exception {
        User currentUser = new User();
        currentUser.setId(8L);
        currentUser.setRole("SOQM_TEAM");
        currentUser.setMail("soqm@kpmg.kz");
        currentUser.setDisplayName("SoQM User");

        Control control1 = new Control();
        control1.setId(200L);
        control1.setComponent("HR");
        control1.setControlStatus("DRAFT");

        Control control2 = new Control();
        control2.setId(201L);
        control2.setComponent("INTR");
        control2.setControlStatus("COMPLETED");

        Control control3 = new Control();
        control3.setId(202L);
        control3.setComponent("HR");
        control3.setControlStatus("IN_PROGRESS");

        when(controlService.getAllControls())
                .thenReturn(List.of(control1, control2, control3));

        MvcResult result = mockMvc.perform(get("/action-centre")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"sidebar-new-control\"")))
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Long> componentStats =
                (Map<String, Long>) result.getModelAndView().getModel().get("componentStats");

        assertThat(componentStats.get("All")).isEqualTo(3L);
        assertThat(componentStats.get("HR")).isEqualTo(2L);
        assertThat(componentStats.get("INTR")).isEqualTo(1L);
    }

    @Test
    void viewControl_draft_notVisibleToNonSoqm() throws Exception {
        User currentUser = new User();
        currentUser.setId(4L);
        currentUser.setRole("FACILITATOR");
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
        currentUser.setRole("SOQM_TEAM");
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
                        false, false, false, false, true, false));

        mockMvc.perform(get("/view-control/31")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk());
    }

    @Test
    void viewControl_draft_sharedUser_getsFriendlyNotAvailablePage() throws Exception {
        User currentUser = new User();
        currentUser.setId(32L);
        currentUser.setRole("CONTROL_OPERATOR");
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
                        false,
                        false
                ));
        when(permissionService.isSharedOnly(any(Control.class), any(User.class), any(ControlPermission.class)))
                .thenReturn(true);

        mockMvc.perform(get("/view-control/32")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andExpect(view().name("control-not-available"))
                .andExpect(content().string(containsString("Control Not Available Yet")))
                .andExpect(content().string(containsString("Back to Controls")))
                .andExpect(content().string(not(containsString("QT-2026-001"))));
    }

    @Test
    void viewControl_notFound_returns404Page() throws Exception {
        User currentUser = new User();
        currentUser.setId(33L);
        currentUser.setRole("FACILITATOR");
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
        currentUser.setRole("FACILITATOR");
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
                        false,
                        true,
                        false,
                        false,
                        false
                ));

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
    void performanceChecklistUrl_redirectsToViewControl() throws Exception {
        User currentUser = new User();
        currentUser.setId(22L);
        currentUser.setRole("FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");

        mockMvc.perform(get("/performance/18").sessionAttr("currentUser", currentUser))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/view-control/18"));
    }

    @Test
    void dashboard_counters_excludeDraft_forNonSoqm() throws Exception {
        User currentUser = new User();
        currentUser.setId(6L);
        currentUser.setRole("FACILITATOR");
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

        assertThat(result.getModelAndView().getModel().get("totalControls")).isEqualTo(1);
        assertThat(result.getModelAndView().getModel().get("activeControls")).isEqualTo(1);
    }

    @Test
    void dashboard_activeCountsAllNonCompletedVisibleControls() throws Exception {
        User currentUser = new User();
        currentUser.setId(17L);
        currentUser.setRole("FACILITATOR");
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
    void dashboard_overdueCountsExcludeDraftAndCompleted() throws Exception {
        User currentUser = new User();
        currentUser.setId(9L);
        currentUser.setRole("FACILITATOR");
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

        assertThat(result.getModelAndView().getModel().get("overdueControls")).isEqualTo(1);
    }

    @Test
    void controls_counters_useSameRulesAsListFiltering() throws Exception {
        User currentUser = new User();
        currentUser.setId(16L);
        currentUser.setRole("ADMIN");
        currentUser.setMail("admin@kpmg.kz");
        currentUser.setDisplayName("Admin User");

        java.time.LocalDate yesterday =
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).minusDays(1);
        java.time.LocalDate tomorrow =
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).plusDays(1);

        ControlResponseDTO completedControl = new ControlResponseDTO();
        completedControl.setId(1000L);
        completedControl.setControlStatus("COMPLETED");
        completedControl.setDeadline(yesterday);
        completedControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(3));

        ControlResponseDTO inProgressOverdue = new ControlResponseDTO();
        inProgressOverdue.setId(1001L);
        inProgressOverdue.setControlStatus("IN_PROGRESS");
        inProgressOverdue.setDeadline(yesterday);
        inProgressOverdue.setCreatedAt(java.time.LocalDateTime.now().minusDays(2));

        ControlResponseDTO draftFuture = new ControlResponseDTO();
        draftFuture.setId(1002L);
        draftFuture.setControlStatus("DRAFT");
        draftFuture.setDeadline(tomorrow);
        draftFuture.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        mockVisibleControls(currentUser, List.of(completedControl, inProgressOverdue, draftFuture));

        MvcResult result = mockMvc.perform(get("/controls")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getModelAndView().getModel().get("totalControls")).isEqualTo(3);
        assertThat(result.getModelAndView().getModel().get("completedControls")).isEqualTo(1);
        assertThat(result.getModelAndView().getModel().get("activeControls")).isEqualTo(1);
        assertThat(result.getModelAndView().getModel().get("overdueControls")).isEqualTo(1);
    }

    @Test
    void controls_soqmDelegateSeesAllVisibleIncludingDraft() throws Exception {
        User currentUser = new User();
        currentUser.setId(23L);
        currentUser.setRole("SOQM_DELEGATE");
        currentUser.setMail("soqm.delegate@kpmg.kz");
        currentUser.setDisplayName("SoQM Delegate");

        ControlResponseDTO draftControl = new ControlResponseDTO();
        draftControl.setId(110L);
        draftControl.setControlStatus("DRAFT");
        draftControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(3));

        ControlResponseDTO reviewControl = new ControlResponseDTO();
        reviewControl.setId(111L);
        reviewControl.setControlStatus("REVIEW");
        reviewControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(2));

        ControlResponseDTO completedControl = new ControlResponseDTO();
        completedControl.setId(112L);
        completedControl.setControlStatus("COMPLETED");
        completedControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        mockVisibleControls(currentUser, List.of(draftControl, reviewControl, completedControl));

        MvcResult result = mockMvc.perform(get("/controls")
                        .param("scope", "all")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");

        assertThat(controls).hasSize(3);
        assertThat(result.getModelAndView().getModel().get("totalControls")).isEqualTo(3);
        assertThat(result.getModelAndView().getModel().get("activeControls")).isEqualTo(2);
        assertThat(result.getModelAndView().getModel().get("completedControls")).isEqualTo(1);
    }

    @Test
    void componentAll_soqmDelegateCountersUseAllControls() throws Exception {
        User currentUser = new User();
        currentUser.setId(24L);
        currentUser.setRole("SOQM_DELEGATE");
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

        // "/component/All" now redirects to the Controls list, so the counters are checked there
        MvcResult result = mockMvc.perform(get("/controls")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");

        assertThat(controls).hasSize(3);
        assertThat(result.getModelAndView().getModel().get("totalControls")).isEqualTo(3);
        assertThat(result.getModelAndView().getModel().get("activeControls")).isEqualTo(1);
        assertThat(result.getModelAndView().getModel().get("completedControls")).isEqualTo(1);
        assertThat(result.getModelAndView().getModel().get("overdueControls")).isEqualTo(1);
    }

    @Test
    void dashboard_showsControlsAwaitingUserAction_overdueFirst_andSharedSidebar() throws Exception {
        User currentUser = new User();
        currentUser.setId(20L);
        currentUser.setRole("FACILITATOR");
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
                .andExpect(content().string(containsString("href=\"/controls\"")))
                .andExpect(content().string(containsString(">Facilitator<")))
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
        currentUser.setRole("FACILITATOR");
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
        soqm.setRole("SOQM_TEAM");
        soqm.setMail("soqm@kpmg.kz");
        soqm.setDisplayName("SoQM User");

        mockMvc.perform(get("/new-control").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andExpect(view().name("new-control"))
                .andExpect(content().string(containsString("id=\"controlForm\"")))
                .andExpect(content().string(containsString("class=\"col-md-2 sidebar p-3\"")))
                .andExpect(content().string(containsString("id=\"controlId-status\"")));

        User facilitator = new User();
        facilitator.setId(31L);
        facilitator.setRole("FACILITATOR");
        facilitator.setMail("facilitator@kpmg.kz");

        mockMvc.perform(get("/new-control").sessionAttr("currentUser", facilitator))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void controls_soqmActiveScope_excludesCompleted() throws Exception {
        User soqm = new User();
        soqm.setId(40L);
        soqm.setRole("SOQM_TEAM");
        soqm.setMail("soqm@kpmg.kz");
        soqm.setDisplayName("SoQM User");

        ControlResponseDTO inProgress = new ControlResponseDTO();
        inProgress.setId(401L);
        inProgress.setPerformanceStatus("IN_PROGRESS");
        inProgress.setCreatedAt(java.time.LocalDateTime.now());
        ControlResponseDTO completed = new ControlResponseDTO();
        completed.setId(402L);
        completed.setPerformanceStatus("COMPLETED");
        completed.setCreatedAt(java.time.LocalDateTime.now());
        mockVisibleControls(soqm, List.of(inProgress, completed));

        MvcResult result = mockMvc.perform(get("/controls").param("scope", "active").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");
        assertThat(controls).extracting(ControlResponseDTO::getId).containsExactly(401L);
    }

    @Test
    void controls_marksYourTurn_andShowsCurrentAssignee() throws Exception {
        User facilitator = new User();
        facilitator.setId(41L);
        facilitator.setRole("FACILITATOR");
        facilitator.setMail("facilitator@kpmg.kz");
        facilitator.setDisplayName("Facilitator User");

        com.kpmg.qtracker.dto.UserDTO facilitatorUser = new com.kpmg.qtracker.dto.UserDTO();
        facilitatorUser.setDisplayName("Aigerim Facilitator");

        ControlResponseDTO mine = new ControlResponseDTO();
        mine.setId(411L);
        mine.setControlId("HR-CTRL-MF-411");
        mine.setControlDescription("Monthly HR check");
        mine.setPerformanceStatus("IN_PROGRESS");
        mine.setFacilitators(List.of("facilitator@kpmg.kz"));
        mine.setFacilitatorUsers(List.of(facilitatorUser));
        mine.setCreatedAt(java.time.LocalDateTime.now());
        mockVisibleControls(facilitator, List.of(mine));

        mockMvc.perform(get("/controls").sessionAttr("currentUser", facilitator))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/performance-cycle/411\"")))
                .andExpect(content().string(containsString(">Your turn<")))
                .andExpect(content().string(containsString(">Aigerim Facilitator<")))
                .andExpect(content().string(containsString(">Monthly HR check<")));
    }

    @Test
    void dashboard_actionCentreSummarisesComponents() throws Exception {
        User soqm = new User();
        soqm.setId(50L);
        soqm.setRole("SOQM_TEAM");
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
                .andExpect(content().string(containsString(">1 overdue<")))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ViewController.ComponentSummary> summaries =
                (List<ViewController.ComponentSummary>) result.getModelAndView().getModel().get("componentSummaries");
        ViewController.ComponentSummary hr = summaries.stream().filter(c -> c.code().equals("HR")).findFirst().orElseThrow();
        assertThat(hr.total()).isEqualTo(2);
        assertThat(hr.overdue()).isEqualTo(1);
        assertThat(hr.completed()).isEqualTo(1);
        assertThat(hr.active()).isZero();
        ViewController.ComponentSummary all =
                (ViewController.ComponentSummary) result.getModelAndView().getModel().get("componentSummaryAll");
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.active()).isEqualTo(1);
    }

    @Test
    void performanceCycle_showsRealHistory_allPeople_andCurrentStep() throws Exception {
        User soqm = new User();
        soqm.setId(60L);
        soqm.setRole("SOQM_TEAM");
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
        when(controlPermissionService.resolve(any(Control.class), any(User.class)))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, true, false, false, false, false, true, false));
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
    void componentPage_redirectsToControlsWithComponentFilter() throws Exception {
        User soqm = new User();
        soqm.setId(70L);
        soqm.setRole("SOQM_TEAM");
        soqm.setMail("soqm@kpmg.kz");

        mockMvc.perform(get("/component/hr").sessionAttr("currentUser", soqm))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/controls?component=HR"));
        mockMvc.perform(get("/component/A&C").sessionAttr("currentUser", soqm))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/controls?component=A%26C"));
        mockMvc.perform(get("/component/All").sessionAttr("currentUser", soqm))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/controls"));
    }

    @Test
    void controls_componentFilter_keepsComponentInStatusLinks() throws Exception {
        User soqm = new User();
        soqm.setId(71L);
        soqm.setRole("SOQM_TEAM");
        soqm.setMail("soqm@kpmg.kz");
        soqm.setDisplayName("SoQM User");

        ControlResponseDTO hr = new ControlResponseDTO();
        hr.setId(711L);
        hr.setComponent("HR");
        hr.setPerformanceStatus("IN_PROGRESS");
        hr.setCreatedAt(java.time.LocalDateTime.now());
        ControlResponseDTO ac = new ControlResponseDTO();
        ac.setId(712L);
        ac.setComponent("A&C");
        ac.setPerformanceStatus("IN_PROGRESS");
        ac.setCreatedAt(java.time.LocalDateTime.now());
        mockVisibleControls(soqm, List.of(hr, ac));

        MvcResult result = mockMvc.perform(get("/controls").param("component", "A&C").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Acceptance &amp; Continuance (A&amp;C)")))
                .andExpect(content().string(containsString("href=\"/controls?filter=OVERDUE&amp;component=A%26C\"")))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> controls =
                (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");
        assertThat(controls).extracting(ControlResponseDTO::getId).containsExactly(712L);
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
            when(controlService.convertToResponseDTO(control)).thenReturn(dto);
        }
        when(controlService.findVisibleControlsForUser(user.getMail(), user.getRole()))
                .thenReturn(controls);
    }
}

