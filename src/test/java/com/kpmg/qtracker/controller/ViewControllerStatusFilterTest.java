package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlResponseDTO;
import com.kpmg.qtracker.dto.PerformanceDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDocumentsRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;

import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.ControlDetailsService;
import com.kpmg.qtracker.service.ControlDocumentsService;
import com.kpmg.qtracker.service.IControlService;
import com.kpmg.qtracker.service.IPerformanceService;
import com.kpmg.qtracker.service.NotificationService;
import com.kpmg.qtracker.service.UserService;
import com.kpmg.qtracker.service.WorkflowService;
import com.kpmg.qtracker.util.NotificationTypeDisplayMapper;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ViewController.class)
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

    @MockBean(name = "statusDisplayMapper")
    private StatusDisplayMapper statusDisplayMapper;

    @Test
    void controls_withStatusFilter_returnsOnlyMatchingStatus() throws Exception {
        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setRole("SOQM_LEAD");
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

        when(controlService.getUserControlsDTO("soqm@kpmg.kz"))
                .thenReturn(List.of(reviewControl, inProgressControl));
        when(controlService.getSoqmLeadControls("soqm@kpmg.kz"))
                .thenReturn(List.of());
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

        when(controlService.getUserControlsDTO("facilitator@kpmg.kz"))
                .thenReturn(List.of(assignedControl, otherControl));
        when(controlService.getFacilitatorControlsDTO("facilitator@kpmg.kz"))
                .thenReturn(List.of());
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
        overdueControl.setFacilitators(List.of());
        overdueControl.setControlOperators(List.of());
        overdueControl.setSoqmLeads(List.of());
        overdueControl.setProcessOwners(List.of());
        overdueControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(2));

        ControlResponseDTO notOverdueControl = new ControlResponseDTO();
        notOverdueControl.setId(61L);
        notOverdueControl.setControlStatus("REVIEW");
        notOverdueControl.setFacilitators(List.of());
        notOverdueControl.setControlOperators(List.of());
        notOverdueControl.setSoqmLeads(List.of());
        notOverdueControl.setProcessOwners(List.of());
        notOverdueControl.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        when(controlService.getUserControlsDTO("facilitator@kpmg.kz"))
                .thenReturn(List.of(overdueControl, notOverdueControl));
        when(controlService.getFacilitatorControlsDTO("facilitator@kpmg.kz"))
                .thenReturn(List.of());

        java.time.LocalDate today =
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Almaty"));
        when(controlAssignmentRepository.findOverdueControlIds(today))
                .thenReturn(List.of(60L));

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

        when(controlService.getUserControlsDTO("facilitator@kpmg.kz"))
                .thenReturn(List.of(overdueControl));
        when(controlService.getFacilitatorControlsDTO("facilitator@kpmg.kz"))
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
        assertThat(controls.get(0).isOverdue()).isTrue();
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

        when(controlService.getUserControlsDTO("shared@kpmg.kz")).thenReturn(List.of());
        when(controlService.getFacilitatorControlsDTO("shared@kpmg.kz")).thenReturn(List.of());
        when(controlService.getAllControls()).thenReturn(List.of(sharedControl));
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

        when(controlService.getUserControlsDTO("operator@kpmg.kz"))
                .thenReturn(List.of(draftControl, reviewControl));
        when(controlService.getControlOperatorControls("operator@kpmg.kz"))
                .thenReturn(List.of());
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
    void controls_completedFilter_returnsOnlyCompleted() throws Exception {
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

        when(controlService.getUserControlsDTO("operator@kpmg.kz"))
                .thenReturn(List.of(completedControl, inProgressControl));
        when(controlService.getControlOperatorControls("operator@kpmg.kz"))
                .thenReturn(List.of());

        MvcResult result = mockMvc.perform(get("/controls")
                        .param("filter", "COMPLETED")
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

        when(controlService.getUserControlsDTO("facilitator@kpmg.kz"))
                .thenReturn(List.of(completedControl, reviewControl));
        when(controlService.getFacilitatorControlsDTO("facilitator@kpmg.kz"))
                .thenReturn(List.of());

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
        currentUser.setRole("SOQM_LEAD");
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
        draftControl.setControlStatus("DRAFT");

        when(controlService.getControlById(30L)).thenReturn(java.util.Optional.of(draftControl));
        when(controlService.getFacilitatorsForControl(30L)).thenReturn(List.of());
        when(controlAssignmentRepository.findByControlId(30L))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/view-control/30")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isNotFound());
    }

    @Test
    void viewControl_draft_visibleToSoqm() throws Exception {
        User currentUser = new User();
        currentUser.setId(5L);
        currentUser.setRole("SOQM_LEAD");
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
        when(controlAssignmentRepository.findByControlId(31L))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/view-control/31")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk());
    }

    @Test
    void performanceChecklist_usesPerformanceStatusFromControl() throws Exception {
        User currentUser = new User();
        currentUser.setId(22L);
        currentUser.setRole("FACILITATOR");
        currentUser.setMail("facilitator@kpmg.kz");
        currentUser.setDisplayName("Facilitator User");

        Control control = new Control();
        control.setId(18L);
        control.setControlStatus("ACTIVE");
        control.setPerformanceStatus("REVIEW");

        PerformanceDTO performanceDTO = new PerformanceDTO();

        when(controlService.getControlById(18L)).thenReturn(java.util.Optional.of(control));
        when(controlAssignmentService.getAssignmentByControlId(18L)).thenReturn(null);
        when(performanceService.buildPerformanceDTO(control)).thenReturn(performanceDTO);

        MvcResult result = mockMvc.perform(get("/performance/18")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getModelAndView().getModel().get("performanceStatus"))
                .isEqualTo("REVIEW");
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

        when(controlService.getUserControlsDTO("facilitator@kpmg.kz"))
                .thenReturn(List.of(draftControl));
        when(controlService.getFacilitatorControlsDTO("facilitator@kpmg.kz"))
                .thenReturn(List.of(activeControl));
        when(notificationService.countUnread(6L)).thenReturn(0L);

        MvcResult result = mockMvc.perform(get("/")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getModelAndView().getModel().get("totalControls")).isEqualTo(1);
        assertThat(result.getModelAndView().getModel().get("activeControls")).isEqualTo(1);
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

        when(controlService.getUserControlsDTO("facilitator@kpmg.kz"))
                .thenReturn(List.of(overdueControl, completedControl, draftControl));
        when(controlService.getFacilitatorControlsDTO("facilitator@kpmg.kz"))
                .thenReturn(List.of());

        MvcResult result = mockMvc.perform(get("/")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getModelAndView().getModel().get("overdueControls")).isEqualTo(1);
    }
}
