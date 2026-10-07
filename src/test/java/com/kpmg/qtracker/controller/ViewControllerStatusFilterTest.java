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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = ViewController.class)
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

    @Test
    void controls_withStatusFilter_returnsOnlyMatchingStatus() throws Exception {
        User currentUser = new User();
        currentUser.setId(1L);
        TestUsers.withRole(currentUser, "SOQM_TEAM");
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
        TestUsers.withRole(currentUser, "FACILITATOR");
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
        TestUsers.withRole(currentUser, "FACILITATOR");
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
        TestUsers.withRole(currentUser, "FACILITATOR");
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
        TestUsers.withRole(currentUser, "FACILITATOR");
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
        TestUsers.withRole(currentUser, "FACILITATOR");
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
        TestUsers.withRole(currentUser, "FACILITATOR");
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
        TestUsers.withRole(currentUser, "FACILITATOR");
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

        when(controlService.findVisibleControlsForUser(currentUser))
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
        TestUsers.withRole(soqm, "SOQM_TEAM");
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
    void controls_allScope_listsTheDraftsTheUserSees() throws Exception {
        User currentUser = new User();
        currentUser.setId(3L);
        TestUsers.withRole(currentUser, "CONTROL_OPERATOR");
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

        // The service returns only the controls the user sees; drafts among them stay in the list
        assertThat(controls).extracting(ControlResponseDTO::getId).containsExactly(21L, 20L);
    }

    @Test
    void controls_completedStatusFilter_returnsOnlyCompleted() throws Exception {
        User currentUser = new User();
        currentUser.setId(14L);
        TestUsers.withRole(currentUser, "CONTROL_OPERATOR");
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
        TestUsers.withRole(currentUser, "FACILITATOR");
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
    void controls_counters_useSameRulesAsListFiltering() throws Exception {
        User currentUser = new User();
        currentUser.setId(16L);
        TestUsers.withRole(currentUser, "ADMIN");
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
        TestUsers.withRole(currentUser, "SOQM_DELEGATE");
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
                .andExpect(content().string(containsString("href=\"/controls\"")))
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
    void controls_soqmActiveScope_excludesCompleted() throws Exception {
        User soqm = new User();
        soqm.setId(40L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
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
    void controls_kdn_activeIsEveryNotCompletedKdnControl_withTheKdnSubtitle_andNoSharedTag() throws Exception {
        User kdn = TestUsers.user("kdn@kpmg.kz", com.kpmg.qtracker.enums.AccessLevel.READ_ONLY,
                com.kpmg.qtracker.enums.AccessScope.KDN, false);
        kdn.setId(41L);

        ControlResponseDTO notOnIt = new ControlResponseDTO();
        notOnIt.setId(411L);
        notOnIt.setControlId("KDN-411");
        notOnIt.setPerformanceStatus("REVIEW");
        notOnIt.setCreatedAt(java.time.LocalDateTime.now());
        ControlResponseDTO draft = new ControlResponseDTO();
        draft.setId(412L);
        draft.setControlId("KDN-412");
        draft.setPerformanceStatus("DRAFT");
        draft.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));
        ControlResponseDTO completed = new ControlResponseDTO();
        completed.setId(413L);
        completed.setControlId("KDN-413");
        completed.setPerformanceStatus("COMPLETED");
        completed.setCreatedAt(java.time.LocalDateTime.now().minusDays(2));
        mockVisibleControls(kdn, List.of(notOnIt, draft, completed));

        MvcResult active = mockMvc.perform(get("/controls").param("scope", "active").sessionAttr("currentUser", kdn))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> activeControls =
                (List<ControlResponseDTO>) active.getModelAndView().getModel().get("controls");
        assertThat(activeControls).extracting(ControlResponseDTO::getId).containsExactly(411L, 412L);
        assertThat(active.getModelAndView().getModel().get("controlsSubtitle"))
                .isEqualTo("All KDN controls (Control ID starting with KDN), drafts included");
        assertThat(active.getResponse().getContentAsString())
                .contains("All KDN controls (Control ID starting with KDN), drafts included")
                .doesNotContain("Controls assigned to you, shared with you or created by you");

        MvcResult all = mockMvc.perform(get("/controls").sessionAttr("currentUser", kdn))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> allControls =
                (List<ControlResponseDTO>) all.getModelAndView().getModel().get("controls");
        assertThat(allControls).extracting(ControlResponseDTO::getId).containsExactly(411L, 412L, 413L);
        assertThat(allControls).noneMatch(ControlResponseDTO::isSharedViewOnly);
        assertThat(all.getModelAndView().getModel().get("activeControls")).isEqualTo(2);
        assertThat(all.getModelAndView().getModel().get("completedControls")).isEqualTo(1);
    }

    @Test
    void controls_kdnMark_onlyForSoqm_andOnlyOnIdsStartingWithKdn() throws Exception {
        User soqm = new User();
        soqm.setId(42L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm-mark@kpmg.kz");
        List<ControlResponseDTO> dtos = new java.util.ArrayList<>();
        String[] ids = {"KDN-421", "X-KDN-422", "HR-423", " kdn424 "};
        for (int i = 0; i < ids.length; i++) {
            ControlResponseDTO dto = new ControlResponseDTO();
            dto.setId(421L + i);
            dto.setControlId(ids[i]);
            dto.setPerformanceStatus("IN_PROGRESS");
            dto.setCreatedAt(java.time.LocalDateTime.now().minusDays(i));
            dtos.add(dto);
        }
        mockVisibleControls(soqm, dtos);

        MvcResult result = mockMvc.perform(get("/controls").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getModelAndView().getModel().get("kdnControlIds")).isEqualTo(java.util.Set.of(421L, 424L));
        assertThat(result.getResponse().getContentAsString().split("class=\"tag tag-kdn\"", -1)).hasSize(3);

        User reader = new User();
        reader.setId(43L);
        TestUsers.withRole(reader, "FACILITATOR");
        reader.setMail("reader@kpmg.kz");
        reader.setAccessScope(com.kpmg.qtracker.enums.AccessScope.ALL);
        mockVisibleControls(reader, dtos);
        MvcResult other = mockMvc.perform(get("/controls").sessionAttr("currentUser", reader))
                .andExpect(status().isOk())
                .andReturn();
        assertThat((java.util.Set<?>) other.getModelAndView().getModel().get("kdnControlIds")).isEmpty();
        assertThat(other.getResponse().getContentAsString()).doesNotContain("class=\"tag tag-kdn\"");
    }

    @Test
    void controls_marksYourTurn_andShowsCurrentAssignee() throws Exception {
        User facilitator = new User();
        facilitator.setId(41L);
        TestUsers.withRole(facilitator, "FACILITATOR");
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
    void componentPage_redirectsToControlsWithComponentFilter() throws Exception {
        User soqm = new User();
        soqm.setId(70L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
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
        TestUsers.withRole(soqm, "SOQM_TEAM");
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

    @SuppressWarnings("unchecked")
    private List<Long> listedIds(MvcResult result) {
        return ((List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls")).stream()
                .map(ControlResponseDTO::getId).toList();
    }

    @Test
    void controls_kdnFilter_onlyKdnControls_inEveryLinkOfThePage_andARemovableChip() throws Exception {
        User soqm = new User();
        soqm.setId(81L);
        TestUsers.withRole(soqm, "SOQM_TEAM");
        soqm.setMail("soqm-kdn-filter@kpmg.kz");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty"));
        mockVisibleControls(soqm, List.of(
                dto(811L, "KDN-811", "IN_PROGRESS", today.minusDays(2)),
                dto(812L, "kdn812", "REVIEW", today.plusDays(3)),
                dto(813L, "X-KDN-813", "IN_PROGRESS", today.minusDays(2)),
                dto(814L, "HR-814", "SOQM_HEAD_REVIEW", today.minusDays(1)),
                dto(815L, " Kdn-815", "COMPLETED", today.minusDays(9)),
                dto(816L, "KDN-816", "PROCESS_OWNER_REVIEW", today.minusDays(1)),
                dto(817L, "KDN-817", "DRAFT", null)));

        MvcResult all = mockMvc.perform(get("/controls").param("kdn", "1").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(listedIds(all)).containsExactlyInAnyOrder(811L, 812L, 815L, 816L, 817L);
        assertThat(all.getModelAndView().getModel()).containsEntry("kdnFilter", true)
                .containsEntry("totalControls", 5).containsEntry("overdueControls", 2)
                .containsEntry("completedControls", 1).containsEntry("kdnFilterClearHref", "/controls");
        assertThat(all.getResponse().getContentAsString())
                .contains("<h1>KDN controls</h1>", "KDN controls only", "aria-label=\"Remove the KDN controls filter\"",
                        "href=\"/controls?filter=OVERDUE&amp;kdn=1\"", "href=\"/controls?status=REVIEW&amp;kdn=1\"",
                        "value=\"/controls?component=HR&amp;kdn=1\"");

        MvcResult overdue = mockMvc.perform(get("/controls").param("kdn", "1").param("filter", "OVERDUE")
                        .sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(listedIds(overdue)).containsExactlyInAnyOrder(811L, 816L);
        assertThat(overdue.getModelAndView().getModel()).containsEntry("kdnFilterClearHref", "/controls?filter=OVERDUE");

        MvcResult inProgress = mockMvc.perform(get("/controls").param("kdn", "1").param("status", "IN_PROGRESS")
                        .sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(listedIds(inProgress)).containsExactly(811L);

        MvcResult unfiltered = mockMvc.perform(get("/controls").sessionAttr("currentUser", soqm))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(listedIds(unfiltered)).hasSize(7);
        assertThat(unfiltered.getModelAndView().getModel()).containsEntry("kdnFilter", false);
        assertThat(unfiltered.getResponse().getContentAsString()).doesNotContain("KDN controls only", "kdn=1");
    }

    @Test
    void controls_kdnFilter_forMyControls_onlyTheirOwnKdnControls() throws Exception {
        User mine = TestUsers.user("mine-kdn@kpmg.kz", com.kpmg.qtracker.enums.AccessLevel.PARTICIPANT,
                com.kpmg.qtracker.enums.AccessScope.OWN, false);
        mine.setId(83L);
        // The visible list is the policy's: the filter only narrows it, never adds a control
        mockVisibleControls(mine, List.of(dto(831L, "KDN-831", "IN_PROGRESS", null), dto(832L, "HR-832", "REVIEW", null)));

        MvcResult result = mockMvc.perform(get("/controls").param("kdn", "1").sessionAttr("currentUser", mine))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(listedIds(result)).containsExactly(831L);
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
        // The first card of the grid, the same card as a component's: badge, name, counts, bar, overdue flag
        assertThat(cardLinks(pane)).first().isEqualTo("/controls?kdn=1");
        assertThat(card(pane, "/controls?kdn=1")).contains("class=\"ac-card has-overdue\"",
                "<span class=\"ac-code\">KDN</span>", "<span class=\"ac-overdue-flag\">1 overdue</span>",
                "<div class=\"ac-name\">KDN controls</div>", "<strong>4</strong>", "2 active", "1 done",
                "aria-label=\"25% completed, 1 overdue\"");
        ViewController.ComponentSummary summary =
                (ViewController.ComponentSummary) result.getModelAndView().getModel().get("kdnSummary");
        assertThat(summary).isEqualTo(new ViewController.ComponentSummary("KDN", "KDN controls", 4, 2, 1, 1));
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
        assertThat(cardLinks(pane)).hasSize(11).last().isEqualTo("/controls?kdn=1");
        assertThat(card(pane, "/controls?kdn=1")).contains("<strong>10</strong>", "10 active", "0 done")
                .doesNotContain("ac-overdue-flag", "has-overdue");
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
        assertThat(card(actionCentre(all), "/controls?kdn=1")).contains("class=\"ac-card is-empty\"",
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
        assertThat(card(actionCentre(some), "/controls?kdn=1")).contains("<strong>1</strong>", "1 active");
    }

    @Test
    void controls_completedFilter_listsTheCompletedControls_forAllControlsAndKdnToo() throws Exception {
        User allRead = TestUsers.user("all-completed@kpmg.kz", com.kpmg.qtracker.enums.AccessLevel.READ_ONLY,
                com.kpmg.qtracker.enums.AccessScope.ALL, false);
        allRead.setId(95L);
        User kdn = TestUsers.user("kdn-completed@kpmg.kz", com.kpmg.qtracker.enums.AccessLevel.READ_ONLY,
                com.kpmg.qtracker.enums.AccessScope.KDN, false);
        kdn.setId(96L);
        for (User user : List.of(allRead, kdn)) {
            mockVisibleControls(user, List.of(dto(951L, "KDN-951", "COMPLETED", null),
                    dto(952L, "KDN-952", "REVIEW", null), dto(953L, "KDN-953", "COMPLETED", null)));

            MvcResult completed = mockMvc.perform(get("/controls").param("filter", "COMPLETED")
                            .sessionAttr("currentUser", user))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(listedIds(completed)).as(user.getMail()).containsExactlyInAnyOrder(951L, 953L);
            assertThat(completed.getModelAndView().getModel()).containsEntry("completedControls", 2);

            MvcResult kdnCompleted = mockMvc.perform(get("/controls").param("filter", "COMPLETED").param("kdn", "1")
                            .sessionAttr("currentUser", user))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(listedIds(kdnCompleted)).as(user.getMail()).containsExactlyInAnyOrder(951L, 953L);
        }
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
        when(controlService.findVisibleControlsForUser(user))
                .thenReturn(controls);
    }
}

