package com.kpmg.qtracker.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
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
import com.kpmg.qtracker.service.UserService;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
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

@WebMvcTest(controllers = ControlController.class)
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
    private com.kpmg.qtracker.service.ControlIdGeneratorService controlIdGeneratorService;

    @MockBean
    private StatusDisplayMapper statusDisplayMapper;

    private ControlDTO requestBody;

    @BeforeEach
    void setUp() {
        requestBody = new ControlDTO();
        requestBody.setControlId("CTRL-SEC-001");
        requestBody.setControlFrequency("Monthly");
        requestBody.setControlCategory("Manual");
        requestBody.setControlType("Preventive");
        requestBody.setComponent("HR");
    }

    @Test
    void createControl_whenRoleIsSoqmLead_returns2xx() throws Exception {
        User sessionUser = userWithRole("SOQM_LEAD");
        User dbUser = userWithRole("SOQM_LEAD");

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
        User sessionUser = userWithRole("SOQM_LEAD");
        User dbUser = userWithRole("SOQM_LEAD");

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
        User sessionUser = userWithRole("SOQM_LEAD");
        User dbUser = userWithRole("SOQM_LEAD");

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
    void createControl_whenUnauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/controls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isUnauthorized());

        verify(controlService, never()).createControl(any(Control.class));
    }

    @Test
    void updateControl_whenSoqmLeadAndCompleted_returns200() throws Exception {
        User sessionUser = userWithRole("SOQM_LEAD");
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
                        false, false, false, false, true, false));

        mockMvc.perform(put("/api/controls/200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest))
                        .sessionAttr("currentUser", sessionUser))
                .andExpect(status().isOk());

        verify(controlService, times(1)).updateControl(any(Control.class));
    }

    @Test
    void updateControl_whenControlStatusChanges_logsControlStatusDiff() throws Exception {
        User sessionUser = userWithRole("SOQM_LEAD");

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
                        false, false, false, false, true, false));

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
        User sessionUser = userWithRole("SOQM_LEAD");

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
                        false, false, false, false, true, false));

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
        user.setRole(role);
        user.setMail(role.toLowerCase() + "@kpmg.com");
        user.setDisplayName(role + " User");
        return user;
    }
}


