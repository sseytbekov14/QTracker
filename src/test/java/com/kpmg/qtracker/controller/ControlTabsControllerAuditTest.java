package com.kpmg.qtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlDetailsDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.support.TestUsers;
import com.kpmg.qtracker.service.AdhocNotificationService;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.PermissionService;
import com.kpmg.qtracker.service.NotificationService;
import com.kpmg.qtracker.service.AnnualNotificationService;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.ControlDetailsService;
import com.kpmg.qtracker.service.ControlDocumentsService;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.MonthlyNotificationService;
import com.kpmg.qtracker.service.QuarterlyNotificationService;
import com.kpmg.qtracker.service.RecurringNotificationService;
import com.kpmg.qtracker.service.SemiAnnualNotificationService;
import com.kpmg.qtracker.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@WebMvcTest(controllers = ControlTabsController.class)
@AutoConfigureMockMvc(addFilters = false)
class ControlTabsControllerAuditTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ControlDetailsService controlDetailsService;
    @MockBean
    private ControlAssignmentService controlAssignmentService;
    @MockBean
    private ControlDocumentsService controlDocumentsService;
    @MockBean
    private UserService userService;
    @MockBean
    private ControlService controlService;
    @MockBean
    private AdminAuditService adminAuditService;
    @MockBean
    private MonthlyNotificationService MonthlyNotificationService;
    @MockBean
    private QuarterlyNotificationService QuarterlyNotificationService;
    @MockBean
    private RecurringNotificationService RecurringNotificationService;
    @MockBean
    private AdhocNotificationService AdhocNotificationService;
    @MockBean
    private AnnualNotificationService AnnualNotificationService;
    @MockBean
    private SemiAnnualNotificationService semiAnnualNotificationService;
    @MockBean
    private NotificationService notificationService;
    @MockBean
    private ControlPermissionService controlPermissionService;

    @MockBean
    private PermissionService permissionService;

    @Test
    void saveControlDetails_whenNoChanges_doesNotLogAudit() throws Exception {
        User sessionUser = new User();
        sessionUser.setMail("fac@kpmg.com");
        TestUsers.withRole(sessionUser, "FACILITATOR");
        sessionUser.setDisplayName("Facilitator One");

        Control control = new Control();
        control.setId(1L);
        control.setPerformanceStatus("IN_PROGRESS");
        control.setCreatedBy(sessionUser);

        ControlAssignmentDTO assignmentDTO = new ControlAssignmentDTO();
        assignmentDTO.setFacilitator(List.of("fac@kpmg.com"));

        ControlDetailsDTO existingDetails = new ControlDetailsDTO();
        existingDetails.setControlId(1L);
        existingDetails.setHomogeneity("Homogenous");
        existingDetails.setReferencesToControl("sad");

        when(controlService.getControlById(1L)).thenReturn(Optional.of(control));
        when(controlAssignmentService.getAssignmentByControlId(1L)).thenReturn(assignmentDTO);
        when(controlPermissionService.resolve(eq(control), eq(sessionUser), eq(assignmentDTO)))
                .thenReturn(new ControlPermission(true, true,
                        java.util.Set.of(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED),
                        true, false, false, true, false, false, false));
        when(controlDetailsService.getDetailsByControlId(1L)).thenReturn(existingDetails);
        when(controlDetailsService.saveDetails(any(ControlDetailsDTO.class))).thenReturn(new ControlDetails());

        ControlDetailsDTO request = new ControlDetailsDTO();
        request.setControlId(1L);

        mockMvc.perform(post("/api/control-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isOk());

        verify(adminAuditService, never()).logActionWithChanges(
                any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void saveControlAssignment_doesNotTriggerImmediateDay0Notifications() throws Exception {
        User sessionUser = new User();
        sessionUser.setMail("soqm@kpmg.com");
        TestUsers.withRole(sessionUser, "SOQM_TEAM");
        sessionUser.setDisplayName("SoQM One");

        User creator = new User();
        creator.setMail("soqm@kpmg.com");

        Control control = new Control();
        control.setId(2L);
        control.setPerformanceStatus("IN_PROGRESS");
        control.setControlFrequency("Monthly");
        control.setCreatedBy(creator);

        ControlAssignmentDTO existingAssignment = new ControlAssignmentDTO();
        existingAssignment.setControlId(2L);
        existingAssignment.setFacilitator(List.of("fac@kpmg.com"));
        existingAssignment.setControlOperator(List.of("op@kpmg.com"));
        existingAssignment.setSoqmLead(List.of("soqm@kpmg.com"));
        existingAssignment.setProcessOwner(List.of("po@kpmg.com"));
        existingAssignment.setControlOperationDate(java.time.LocalDate.of(2026, 1, 15));

        ControlAssignmentDTO request = new ControlAssignmentDTO();
        request.setControlId(2L);
        request.setFacilitator(List.of("fac@kpmg.com"));
        request.setControlOperator(List.of("op@kpmg.com"));

        when(controlService.getControlById(2L)).thenReturn(Optional.of(control));
        when(controlAssignmentService.getAssignmentByControlId(2L)).thenReturn(existingAssignment);
        when(controlPermissionService.resolve(eq(control), eq(sessionUser), eq(existingAssignment)))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(),
                        true, true, false, false, false, true, false));
        when(controlAssignmentService.saveAssignment(any(), anyBoolean())).thenReturn(new ControlAssignment());

        mockMvc.perform(post("/api/control-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isOk());

        verify(MonthlyNotificationService, never()).maybeSendImmediateDay0(anyLong());
        verify(QuarterlyNotificationService, never()).maybeSendImmediateDay0(anyLong());
        verify(RecurringNotificationService, never()).maybeSendImmediateDay0(anyLong());
        verify(AdhocNotificationService, never()).maybeSendImmediateDay0(anyLong());
        verify(AnnualNotificationService, never()).maybeSendImmediateDay0(anyLong());
        verify(semiAnnualNotificationService, never()).maybeSendImmediateDay0(anyLong());
    }

    @Test
    void saveControlAssignment_whenRequiredRoleCleared_returns400() throws Exception {
        User sessionUser = new User();
        sessionUser.setMail("soqm@kpmg.com");
        TestUsers.withRole(sessionUser, "SOQM_TEAM");

        Control control = new Control();
        control.setId(5L);
        control.setPerformanceStatus("IN_PROGRESS");

        ControlAssignmentDTO existingAssignment = new ControlAssignmentDTO();
        existingAssignment.setControlId(5L);
        existingAssignment.setFacilitator(List.of("fac@kpmg.com"));
        existingAssignment.setControlOperator(List.of("op@kpmg.com"));
        existingAssignment.setSoqmLead(List.of("soqm@kpmg.com"));
        existingAssignment.setProcessOwner(List.of("po@kpmg.com"));
        existingAssignment.setControlOperationDate(java.time.LocalDate.of(2026, 1, 15));

        ControlAssignmentDTO request = new ControlAssignmentDTO();
        request.setControlId(5L);
        request.setProcessOwner(List.of());

        when(controlService.getControlById(5L)).thenReturn(Optional.of(control));
        when(controlAssignmentService.getAssignmentByControlId(5L)).thenReturn(existingAssignment);
        when(controlPermissionService.resolve(eq(control), eq(sessionUser), eq(existingAssignment)))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, true,
                        false, false, false, true, false));

        mockMvc.perform(post("/api/control-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("VALIDATION_ERROR: Process Owner is required"));

        verify(controlAssignmentService, never()).saveAssignment(any(), anyBoolean());
    }

    @Test
    void saveControlAssignment_withADate_andNoFrequency_returns400() throws Exception {
        assertAssignmentRefusedForFrequency(null,
                "VALIDATION_ERROR: Control Frequency is required to calculate the Control Operation Deadline");
    }

    @Test
    void saveControlAssignment_withADate_andAnUnknownFrequency_returns400() throws Exception {
        assertAssignmentRefusedForFrequency("Bi-weekly",
                "VALIDATION_ERROR: Control Frequency \"Bi-weekly\" is not one of Monthly, Quarterly, Ad-hoc,"
                        + " Recurring, Annual, Semi Annual, so the Control Operation Deadline cannot be calculated");
    }

    private void assertAssignmentRefusedForFrequency(String frequency, String expectedBody) throws Exception {
        User sessionUser = new User();
        sessionUser.setMail("soqm@kpmg.com");
        TestUsers.withRole(sessionUser, "SOQM_TEAM");

        Control control = new Control();
        control.setId(6L);
        control.setPerformanceStatus("IN_PROGRESS");
        control.setControlFrequency(frequency);

        ControlAssignmentDTO existingAssignment = new ControlAssignmentDTO();
        existingAssignment.setControlId(6L);
        existingAssignment.setFacilitator(List.of("fac@kpmg.com"));
        existingAssignment.setControlOperator(List.of("op@kpmg.com"));
        existingAssignment.setSoqmLead(List.of("soqm@kpmg.com"));
        existingAssignment.setProcessOwner(List.of("po@kpmg.com"));

        ControlAssignmentDTO request = new ControlAssignmentDTO();
        request.setControlId(6L);
        request.setControlOperationDate(java.time.LocalDate.of(2026, 11, 20));

        when(controlService.getControlById(6L)).thenReturn(Optional.of(control));
        when(controlAssignmentService.getAssignmentByControlId(6L)).thenReturn(existingAssignment);
        when(controlPermissionService.resolve(eq(control), eq(sessionUser), eq(existingAssignment)))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, true,
                        false, false, false, true, false));

        mockMvc.perform(post("/api/control-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(expectedBody));

        verify(controlAssignmentService, never()).saveAssignment(any(), anyBoolean());
    }

    // ------------------------------------------------------------------ a completed control changed in place

    /** SoQM Team on a completed control they change in place (AccessPolicy.editsAfterCompletion). */
    private static ControlPermission completedEdit() {
        return new ControlPermission(true, true, java.util.Set.of(), true, true,
                false, false, false, true, false, false, true);
    }

    private ControlAssignmentDTO completedAssignment(Long controlId, Control control, User sessionUser) {
        ControlAssignmentDTO existing = new ControlAssignmentDTO();
        existing.setControlId(controlId);
        existing.setFacilitator(List.of("fac@kpmg.com"));
        existing.setControlOperator(List.of("op@kpmg.com"));
        existing.setSoqmLead(List.of("soqm@kpmg.com"));
        existing.setProcessOwner(List.of("po@kpmg.com"));
        existing.setControlOperationDate(java.time.LocalDate.of(2026, 1, 15));
        existing.setControlOperationDeadline(java.time.LocalDate.of(2026, 1, 29));
        existing.setNextControlOperationDate(java.time.LocalDate.of(2026, 2, 15));
        when(controlService.getControlById(controlId)).thenReturn(Optional.of(control));
        when(controlAssignmentService.getAssignmentByControlId(controlId)).thenReturn(existing);
        when(controlPermissionService.resolve(eq(control), eq(sessionUser), eq(existing))).thenReturn(completedEdit());
        return existing;
    }

    @Test
    void completedControl_anotherOperationDate_isRefused_andNothingIsSaved() throws Exception {
        User sessionUser = new User();
        sessionUser.setMail("soqm@kpmg.com");
        TestUsers.withRole(sessionUser, "SOQM_TEAM");
        Control control = new Control();
        control.setId(7L);
        control.setPerformanceStatus("COMPLETED");
        control.setControlFrequency("Monthly");
        completedAssignment(7L, control, sessionUser);

        ControlAssignmentDTO request = new ControlAssignmentDTO();
        request.setControlId(7L);
        request.setControlOperationDate(java.time.LocalDate.of(2026, 3, 1));

        mockMvc.perform(post("/api/control-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isForbidden())
                .andExpect(content().string("VALIDATION_ERROR: Control Operation Date cannot be changed on a completed control"));

        verify(controlAssignmentService, never()).saveAssignment(any(), anyBoolean());
    }

    @Test
    void completedControl_peopleChange_keepsTheSchedule_whateverDatesAreSent() throws Exception {
        User sessionUser = new User();
        sessionUser.setMail("soqm@kpmg.com");
        TestUsers.withRole(sessionUser, "SOQM_TEAM");
        Control control = new Control();
        control.setId(8L);
        control.setPerformanceStatus("COMPLETED");
        // A frequency the schedule cannot be calculated from: no matter, nothing is calculated
        control.setControlFrequency("Bi-weekly");
        completedAssignment(8L, control, sessionUser);
        ControlAssignment saved = new ControlAssignment();
        saved.setControlOperationDeadline(java.time.LocalDate.of(2026, 1, 29));
        saved.setNextControlOperationDate(java.time.LocalDate.of(2026, 2, 15));
        when(controlAssignmentService.saveAssignment(any(), eq(true))).thenReturn(saved);

        ControlAssignmentDTO request = new ControlAssignmentDTO();
        request.setControlId(8L);
        request.setProcessOwner(List.of("po2@kpmg.com"));
        request.setControlOperationDate(java.time.LocalDate.of(2026, 1, 15));
        request.setControlOperationDeadline(java.time.LocalDate.of(2027, 1, 1));
        request.setNextControlOperationDate(java.time.LocalDate.of(2027, 2, 1));

        mockMvc.perform(post("/api/control-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<ControlAssignmentDTO> sent = org.mockito.ArgumentCaptor.forClass(ControlAssignmentDTO.class);
        verify(controlAssignmentService).saveAssignment(sent.capture(), eq(true));
        org.assertj.core.api.Assertions.assertThat(sent.getValue().getProcessOwner()).containsExactly("po2@kpmg.com");
        org.assertj.core.api.Assertions.assertThat(sent.getValue().getControlOperationDeadline()).isNull();
        org.assertj.core.api.Assertions.assertThat(sent.getValue().getNextControlOperationDate()).isNull();
    }

    @Test
    void saveControlDetails_participantWithTheStepsField_changesOnlyControlSteps() throws Exception {
        User sessionUser = new User();
        sessionUser.setMail("fac@kpmg.com");
        TestUsers.withRole(sessionUser, "FACILITATOR");
        sessionUser.setDisplayName("Facilitator");

        Control control = new Control();
        control.setId(3L);
        control.setPerformanceStatus("IN_PROGRESS");

        ControlAssignmentDTO assignmentDTO = new ControlAssignmentDTO();
        assignmentDTO.setFacilitator(List.of("fac@kpmg.com"));

        ControlDetailsDTO existingDetails = new ControlDetailsDTO();
        existingDetails.setControlId(3L);
        existingDetails.setProcessName("Original Process");
        existingDetails.setControlStepsPerformed("Old steps");

        ControlDetailsDTO request = new ControlDetailsDTO();
        request.setControlId(3L);
        request.setProcessName("Changed by the Facilitator");
        request.setControlStepsPerformed("Updated steps");

        when(controlService.getControlById(3L)).thenReturn(Optional.of(control));
        when(controlAssignmentService.getAssignmentByControlId(3L)).thenReturn(assignmentDTO);
        when(controlPermissionService.resolve(eq(control), eq(sessionUser), eq(assignmentDTO)))
                .thenReturn(new ControlPermission(true, true,
                        java.util.Set.of(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED),
                        true, false, false, true, false, false, false));
        when(controlDetailsService.getDetailsByControlId(3L)).thenReturn(existingDetails);
        when(controlDetailsService.saveDetails(any(ControlDetailsDTO.class))).thenReturn(new ControlDetails());

        mockMvc.perform(post("/api/control-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<ControlDetailsDTO> saved = org.mockito.ArgumentCaptor.forClass(ControlDetailsDTO.class);
        verify(controlDetailsService, times(1)).saveDetails(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getControlStepsPerformed()).isEqualTo("Updated steps");
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getProcessName()).isEqualTo("Original Process");
    }

    @Test
    void saveControlDetails_sharedViewerOfACompletedControl_isForbidden() throws Exception {
        User sessionUser = new User();
        sessionUser.setMail("shared-fac@kpmg.com");
        TestUsers.withRole(sessionUser, "FACILITATOR");
        sessionUser.setDisplayName("Shared Facilitator");

        Control control = new Control();
        control.setId(4L);
        control.setPerformanceStatus("COMPLETED");

        ControlAssignmentDTO assignmentDTO = new ControlAssignmentDTO();
        assignmentDTO.setControlSharedWith(List.of("shared-fac@kpmg.com"));

        ControlDetailsDTO request = new ControlDetailsDTO();
        request.setControlId(4L);
        request.setControlStepsPerformed("Steps changed after completion");

        when(controlService.getControlById(4L)).thenReturn(Optional.of(control));
        when(controlAssignmentService.getAssignmentByControlId(4L)).thenReturn(assignmentDTO);
        // What the policy gives a shared viewer: they see the control and change nothing
        when(controlPermissionService.resolve(eq(control), eq(sessionUser), eq(assignmentDTO)))
                .thenReturn(new ControlPermission(true, false, java.util.Set.of(),
                        true, false, true, false, false, false, false));

        mockMvc.perform(post("/api/control-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isForbidden());

        verify(controlDetailsService, never()).saveDetails(any(ControlDetailsDTO.class));
    }
}

