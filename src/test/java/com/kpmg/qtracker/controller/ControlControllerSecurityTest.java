package com.kpmg.qtracker.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.support.TestUsers;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.ControlAuditChangeService;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.ControlDetailsService;
import com.kpmg.qtracker.service.ControlDocumentsService;
import com.kpmg.qtracker.service.ControlHistoryService;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.IControlService;
import com.kpmg.qtracker.service.IPerformanceService;
import com.kpmg.qtracker.service.PermissionService;
import com.kpmg.qtracker.service.UserService;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(controllers = ControlController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ControlAuditChangeService.class)
class ControlControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private IControlService controlService;

    @MockBean
    private UserService userService;

    @MockBean
    private ControlAssignmentService controlAssignmentService;

    @MockBean
    private ControlDetailsService controlDetailsService;

    @MockBean
    private ControlDocumentsService controlDocumentsService;

    @MockBean
    private IPerformanceService performanceService;

    @MockBean
    private AdminAuditService adminAuditService;

    @MockBean
    private ControlHistoryService controlHistoryService;

    @MockBean
    private ControlPermissionService controlPermissionService;

    @MockBean
    private PermissionService permissionService;

    @MockBean
    private com.kpmg.qtracker.service.ControlIdGeneratorService controlIdGeneratorService;

    @MockBean
    private StatusDisplayMapper statusDisplayMapper;

    @MockBean
    private com.kpmg.qtracker.service.ControlRenameService controlRenameService;

    private ControlDTO requestBody;

    @BeforeEach
    void setUp() {
        requestBody = new ControlDTO();
        requestBody.setControlId("CTRL-SEC-001");
        requestBody.setControlFrequency("Monthly");
        requestBody.setControlCategory("Manual");
        requestBody.setControlType("Preventive");
        requestBody.setComponent("HR");
        requestBody.setOperatedBy("Network");
        requestBody.setPriority("High");
        requestBody.setNonAuditServicesApplicability("Applicable");
    }

    @Test
    void createControl_whenRoleIsSoqmLead_returns2xx() throws Exception {
        User sessionUser = userWithRole("SOQM_TEAM");
        User dbUser = userWithRole("SOQM_TEAM");

        Control created = new Control();
        created.setId(100L);
        created.setControlId(requestBody.getControlId());
        created.setControlStatus("DRAFT");
        created.setCreatedBy(dbUser);

        when(userService.getUserByEmail(sessionUser.getMail())).thenReturn(Optional.of(dbUser));
        when(controlService.createControl(any(Control.class))).thenReturn(created);

        mockMvc.perform(post("/api/controls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().is2xxSuccessful());

        verify(controlService, times(1)).createControl(any(Control.class));
    }

    @Test
    void createControl_whenAnnualFrequency_persistsAnnual() throws Exception {
        User sessionUser = userWithRole("SOQM_TEAM");
        User dbUser = userWithRole("SOQM_TEAM");

        Control created = new Control();
        created.setId(101L);
        created.setControlId(requestBody.getControlId());
        created.setControlStatus("DRAFT");
        created.setCreatedBy(dbUser);

        when(userService.getUserByEmail(sessionUser.getMail())).thenReturn(Optional.of(dbUser));
        when(controlService.createControl(any(Control.class))).thenReturn(created);

        requestBody.setControlFrequency("Annual");

        mockMvc.perform(post("/api/controls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().is2xxSuccessful());

        ArgumentCaptor<Control> captor = ArgumentCaptor.forClass(Control.class);
        verify(controlService, times(1)).createControl(captor.capture());
        assertEquals("Annual", captor.getValue().getControlFrequency());
    }

    @Test
    void createControl_whenSemiAnnualFrequency_persistsSemiAnnual() throws Exception {
        User sessionUser = userWithRole("SOQM_TEAM");
        User dbUser = userWithRole("SOQM_TEAM");

        Control created = new Control();
        created.setId(102L);
        created.setControlId(requestBody.getControlId());
        created.setControlStatus("DRAFT");
        created.setCreatedBy(dbUser);

        when(userService.getUserByEmail(sessionUser.getMail())).thenReturn(Optional.of(dbUser));
        when(controlService.createControl(any(Control.class))).thenReturn(created);

        requestBody.setControlFrequency("Semi Annual");

        mockMvc.perform(post("/api/controls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().is2xxSuccessful());

        ArgumentCaptor<Control> captor = ArgumentCaptor.forClass(Control.class);
        verify(controlService, times(1)).createControl(captor.capture());
        assertEquals("Semi Annual", captor.getValue().getControlFrequency());
    }

    @Test
    void createControl_whenRoleIsFacilitator_returns403() throws Exception {
        User sessionUser = userWithRole("FACILITATOR");

        mockMvc.perform(post("/api/controls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isForbidden());

        verify(controlService, never()).createControl(any(Control.class));
    }

    @Test
    void createControl_whenRoleIsControlOperator_returns403() throws Exception {
        User sessionUser = userWithRole("CONTROL_OPERATOR");

        mockMvc.perform(post("/api/controls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isForbidden());

        verify(controlService, never()).createControl(any(Control.class));
    }

    @Test
    void createControl_whenRoleIsProcessOwner_returns403() throws Exception {
        User sessionUser = userWithRole("PROCESS_OWNER");

        mockMvc.perform(post("/api/controls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isForbidden());

        verify(controlService, never()).createControl(any(Control.class));
    }

    @Test
    void createControl_whenAdminWithoutSoqmLevel_returns403() throws Exception {
        User sessionUser = userWithRole("ADMIN");
        sessionUser.setAdminAccess(true);

        mockMvc.perform(post("/api/controls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Only SoQM Team can create controls")));

        verify(controlService, never()).createControl(any(Control.class));
    }

    @Test
    void createControl_whenReadOnly_isRefusedByTheGeneralCheck() throws Exception {
        User sessionUser = userWithRole("READ_ONLY");

        mockMvc.perform(post("/api/controls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"code\":\"READ_ONLY\"")));

        verify(controlService, never()).createControl(any(Control.class));
    }

    @Test
    void createControl_whenUnauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/controls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isUnauthorized());

        verify(controlService, never()).createControl(any(Control.class));
    }

    @Test
    void createControl_whenRequiredFieldBlank_returns400() throws Exception {
        requestBody.setPriority("  ");

        mockMvc.perform(post("/api/controls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody))
                        .sessionAttr("currentUser", userWithRole("SOQM_TEAM")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Priority is required"));

        verify(controlService, never()).createControl(any(Control.class));
    }

    @Test
    void updateControl_whenRequiredFieldSentBlank_returns400() throws Exception {
        User sessionUser = userWithRole("SOQM_TEAM");
        Control existing = new Control();
        existing.setId(201L);
        existing.setControlId("CTRL-201");
        existing.setControlFrequency("Monthly");

        ControlDTO updateRequest = new ControlDTO();
        updateRequest.setControlFrequency("Monthly");
        updateRequest.setOperatedBy("");

        when(controlService.getControlById(201L)).thenReturn(Optional.of(existing));
        when(controlAssignmentService.getAssignmentByControlId(201L)).thenReturn(new ControlAssignmentDTO());
        when(controlPermissionService.resolve(eq(existing), eq(sessionUser), any(ControlAssignmentDTO.class)))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, true,
                        false, false, false, true, false));

        mockMvc.perform(put("/api/controls/201")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("VALIDATION_ERROR: Operated By is required"));

        verify(controlService, never()).updateControl(any(Control.class));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource({
            "Control Frequency, controlFrequency, Quarterly",
            "SoQM Year,         soqmYear,         1 OCT 2027 - 30 SEP 2028",
            "Control Status,    controlStatus,    SUPERSEDED"
    })
    void updateControl_completedControlChangedInPlace_refusesAFixedField(String label, String field, String value)
            throws Exception {
        User sessionUser = userWithRole("SOQM_TEAM");
        Control existing = completedForEditInPlace(sessionUser, 202L);

        mockMvc.perform(put("/api/controls/202")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"" + field + "\":\"" + value + "\",\"controlDescription\":\"Changed\"}")
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isForbidden())
                .andExpect(content().string("VALIDATION_ERROR: " + label + " cannot be changed on a completed control"));

        verify(controlService, never()).updateControl(any(Control.class));
        org.assertj.core.api.Assertions.assertThat(existing.getControlDescription()).isNull();
    }

    @Test
    void updateControl_completedControlChangedInPlace_theSameFixedValuesAreNotApplied() throws Exception {
        User sessionUser = userWithRole("SOQM_TEAM");
        Control existing = completedForEditInPlace(sessionUser, 203L);
        when(controlService.updateControl(any(Control.class))).thenAnswer(inv -> inv.getArgument(0));

        // The page sends the stored values back, the frequency in another spelling
        mockMvc.perform(put("/api/controls/203")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlFrequency\":\"monthly\",\"soqmYear\":\"1 OCT 2026 - 30 SEP 2027\","
                                + "\"controlStatus\":\"active\",\"controlDescription\":\"Changed\","
                                + "\"editReason\":\" Description was wrong \"}")
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(existing.getControlDescription()).isEqualTo("Changed");
        org.assertj.core.api.Assertions.assertThat(existing.getControlFrequency()).isEqualTo("Monthly");
        org.assertj.core.api.Assertions.assertThat(existing.getControlStatus()).isEqualTo("ACTIVE");
        verify(controlAssignmentService, never()).recalculateSchedule(any());
        // The audit entry: marked, the changed field with its values, and the reason
        verify(adminAuditService).logActionWithChanges(eq(sessionUser.getMail()), any(), eq("EDIT"), eq(existing),
                eq("Edit Control - Edited after completion"),
                eq("[\"control_description\",\"Reason\"]"),
                eq("{\"control_description\":null}"),
                eq("{\"control_description\":\"Changed\",\"Reason\":\"Description was wrong\"}"));
    }

    @Test
    void updateControl_completedControlChangedInPlace_withoutAReason_returns400_andSavesNothing() throws Exception {
        User sessionUser = userWithRole("SOQM_TEAM");
        Control existing = completedForEditInPlace(sessionUser, 204L);

        mockMvc.perform(put("/api/controls/204")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlDescription\":\"Changed\",\"editReason\":\"  \"}")
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("VALIDATION_ERROR: Give a reason for changing a completed control"));
        verify(controlService, never()).updateControl(any(Control.class));
        verify(adminAuditService, never()).logActionWithChanges(any(), any(), any(), any(), any(), any(), any(), any());

        // Nothing changed: nothing to explain, nothing saved
        Control unchanged = completedForEditInPlace(sessionUser, 205L);
        mockMvc.perform(put("/api/controls/205")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlFrequency\":\"Monthly\"}")
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isOk());
        verify(controlService, never()).updateControl(unchanged);
    }

    /** A completed control SoQM Team changes in place (AccessPolicy.editsAfterCompletion). */
    private Control completedForEditInPlace(User sessionUser, Long id) {
        Control existing = new Control();
        existing.setId(id);
        existing.setControlId("CTRL-" + id);
        existing.setControlFrequency("Monthly");
        existing.setSoqmYear("1 OCT 2026 - 30 SEP 2027");
        existing.setControlStatus("ACTIVE");
        existing.setPerformanceStatus("COMPLETED");
        when(controlService.getControlById(id)).thenReturn(Optional.of(existing));
        when(controlAssignmentService.getAssignmentByControlId(id)).thenReturn(new ControlAssignmentDTO());
        when(controlPermissionService.resolve(eq(existing), eq(sessionUser), any(ControlAssignmentDTO.class)))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, true,
                        false, false, false, true, false, false, true));
        return existing;
    }

    @Test
    void updateControl_whenSoqmLeadAndCompleted_returns200() throws Exception {
        User sessionUser = userWithRole("SOQM_TEAM");
        Control existing = new Control();
        existing.setId(200L);
        existing.setControlId("CTRL-200");
        existing.setControlFrequency("Monthly");
        existing.setControlStatus("COMPLETED");
        existing.setCreatedBy(sessionUser);

        Control updated = new Control();
        updated.setId(200L);
        updated.setControlId("CTRL-200");
        updated.setControlFrequency("Monthly");
        updated.setControlStatus("COMPLETED");

        ControlDTO updateRequest = new ControlDTO();
        updateRequest.setControlFrequency("Monthly");

        when(controlService.getControlById(200L)).thenReturn(Optional.of(existing));
        when(controlService.updateControl(any(Control.class))).thenReturn(updated);
        when(controlAssignmentService.getAssignmentByControlId(200L)).thenReturn(new ControlAssignmentDTO());
        when(controlPermissionService.resolve(eq(existing), eq(sessionUser), any(ControlAssignmentDTO.class)))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, true,
                        false, false, false, true, false));

        mockMvc.perform(put("/api/controls/200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isOk());

        verify(controlService, times(1)).updateControl(any(Control.class));
    }

    @Test
    void updateControl_whenControlStatusChanges_logsControlStatusDiff() throws Exception {
        User sessionUser = userWithRole("SOQM_TEAM");

        Control existing = new Control();
        existing.setId(210L);
        existing.setControlId("CTRL-210");
        existing.setControlFrequency("Monthly");
        existing.setControlStatus("Active");
        existing.setCreatedBy(sessionUser);

        Control updated = new Control();
        updated.setId(210L);
        updated.setControlId("CTRL-210");
        updated.setControlFrequency("Monthly");
        updated.setControlStatus("Inactive");
        updated.setCreatedBy(sessionUser);

        ControlDTO updateRequest = new ControlDTO();
        updateRequest.setControlStatus("Inactive");

        when(controlService.getControlById(210L)).thenReturn(Optional.of(existing));
        when(controlService.updateControl(any(Control.class))).thenReturn(updated);
        when(controlAssignmentService.getAssignmentByControlId(210L)).thenReturn(new ControlAssignmentDTO());
        when(controlPermissionService.resolve(eq(existing), eq(sessionUser), any(ControlAssignmentDTO.class)))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, true,
                        false, false, false, true, false));

        mockMvc.perform(put("/api/controls/210")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isOk());

        ArgumentCaptor<String> changedFieldsCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> previousValuesCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> newValuesCaptor = ArgumentCaptor.forClass(String.class);

        verify(adminAuditService).logActionWithChanges(
                eq(sessionUser.getMail()),
                eq(sessionUser.getDisplayName()),
                eq("EDIT"),
                eq(updated),
                eq("Edit Control"),
                changedFieldsCaptor.capture(),
                previousValuesCaptor.capture(),
                newValuesCaptor.capture()
        );

        java.util.List<String> changedFields = objectMapper.readValue(
                changedFieldsCaptor.getValue(),
                new TypeReference<java.util.List<String>>() {}
        );
        Map<String, String> previousValues = objectMapper.readValue(
                previousValuesCaptor.getValue(),
                new TypeReference<Map<String, String>>() {}
        );
        Map<String, String> newValues = objectMapper.readValue(
                newValuesCaptor.getValue(),
                new TypeReference<Map<String, String>>() {}
        );

        assertTrue(changedFields.contains("control_status"));
        assertEquals("Active", previousValues.get("control_status"));
        assertEquals("Inactive", newValues.get("control_status"));
    }

    @Test
    void updateControl_whenNormalFieldChanges_logsEntitySnapshotDiff() throws Exception {
        User sessionUser = userWithRole("SOQM_TEAM");

        Control existing = new Control();
        existing.setId(211L);
        existing.setControlId("CTRL-211");
        existing.setControlFrequency("Monthly");
        existing.setControlStatus("Active");
        existing.setCreatedBy(sessionUser);

        Control updated = new Control();
        updated.setId(211L);
        updated.setControlId("CTRL-211");
        updated.setControlFrequency("Quarterly");
        updated.setControlStatus("Active");
        updated.setCreatedBy(sessionUser);

        ControlDTO updateRequest = new ControlDTO();
        updateRequest.setControlFrequency("Quarterly");

        when(controlService.getControlById(211L)).thenReturn(Optional.of(existing));
        when(controlService.updateControl(any(Control.class))).thenReturn(updated);
        when(controlAssignmentService.getAssignmentByControlId(211L)).thenReturn(new ControlAssignmentDTO());
        when(controlPermissionService.resolve(eq(existing), eq(sessionUser), any(ControlAssignmentDTO.class)))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, true,
                        false, false, false, true, false));

        mockMvc.perform(put("/api/controls/211")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isOk());

        ArgumentCaptor<String> changedFieldsCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> previousValuesCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> newValuesCaptor = ArgumentCaptor.forClass(String.class);

        verify(adminAuditService).logActionWithChanges(
                eq(sessionUser.getMail()),
                eq(sessionUser.getDisplayName()),
                eq("EDIT"),
                eq(updated),
                eq("Edit Control"),
                changedFieldsCaptor.capture(),
                previousValuesCaptor.capture(),
                newValuesCaptor.capture()
        );

        java.util.List<String> changedFields = objectMapper.readValue(
                changedFieldsCaptor.getValue(),
                new TypeReference<java.util.List<String>>() {}
        );
        Map<String, String> previousValues = objectMapper.readValue(
                previousValuesCaptor.getValue(),
                new TypeReference<Map<String, String>>() {}
        );
        Map<String, String> newValues = objectMapper.readValue(
                newValuesCaptor.getValue(),
                new TypeReference<Map<String, String>>() {}
        );

        assertTrue(changedFields.contains("control_frequency"));
        assertEquals("Monthly", previousValues.get("control_frequency"));
        assertEquals("Quarterly", newValues.get("control_frequency"));
    }

    @Test
    void updateControl_whenNotResponsible_returns403() throws Exception {
        User sessionUser = userWithRole("CONTROL_OPERATOR");
        Control existing = new Control();
        existing.setId(201L);
        existing.setControlId("CTRL-201");
        existing.setControlFrequency("Monthly");
        existing.setControlStatus("SOQM_HEAD_REVIEW");

        ControlDTO updateRequest = new ControlDTO();
        updateRequest.setControlFrequency("Monthly");

        when(controlService.getControlById(201L)).thenReturn(Optional.of(existing));
        when(controlAssignmentService.getAssignmentByControlId(201L)).thenReturn(new ControlAssignmentDTO());
        when(controlPermissionService.resolve(eq(existing), eq(sessionUser), any(ControlAssignmentDTO.class)))
                .thenReturn(ControlPermission.denied());

        mockMvc.perform(put("/api/controls/201")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isForbidden());

        verify(controlService, never()).updateControl(any(Control.class));
    }

    @Test
    void updateControl_whenCompletedAndNonSharedUser_returns403() throws Exception {
        User sessionUser = userWithRole("FACILITATOR");
        Control existing = new Control();
        existing.setId(202L);
        existing.setControlId("CTRL-202");
        existing.setControlFrequency("Monthly");
        existing.setPerformanceStatus("COMPLETED");

        ControlDTO updateRequest = new ControlDTO();
        updateRequest.setControlFrequency("Monthly");

        when(controlService.getControlById(202L)).thenReturn(Optional.of(existing));
        when(controlAssignmentService.getAssignmentByControlId(202L)).thenReturn(new ControlAssignmentDTO());
        when(controlPermissionService.resolve(eq(existing), eq(sessionUser), any(ControlAssignmentDTO.class)))
                .thenReturn(ControlPermission.denied());

        mockMvc.perform(put("/api/controls/202")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isForbidden());

        verify(controlService, never()).updateControl(any(Control.class));
    }

    private User userWithRole(String role) {
        User user = new User();
        TestUsers.withRole(user, role);
        user.setMail(role.toLowerCase() + "@kpmg.com");
        user.setDisplayName(role + " User");
        return user;
    }
}


