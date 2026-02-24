package com.kpmg.qtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlDetailsDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.AdhocDay0NotificationService;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.NotificationService;
import com.kpmg.qtracker.service.AnnualSemiDay0NotificationService;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.ControlDetailsService;
import com.kpmg.qtracker.service.ControlDocumentsService;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.MonthlyDay0NotificationService;
import com.kpmg.qtracker.service.QuarterlyDay0NotificationService;
import com.kpmg.qtracker.service.RecurringDay0NotificationService;
import com.kpmg.qtracker.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ControlTabsController.class)
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
    private MonthlyDay0NotificationService monthlyDay0NotificationService;
    @MockBean
    private QuarterlyDay0NotificationService quarterlyDay0NotificationService;
    @MockBean
    private RecurringDay0NotificationService recurringDay0NotificationService;
    @MockBean
    private AdhocDay0NotificationService adhocDay0NotificationService;
    @MockBean
    private AnnualSemiDay0NotificationService annualSemiDay0NotificationService;
    @MockBean
    private NotificationService notificationService;

    @Test
    void saveControlDetails_whenNoChanges_doesNotLogAudit() throws Exception {
        User sessionUser = new User();
        sessionUser.setMail("fac@kpmg.com");
        sessionUser.setRole("FACILITATOR");
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
}
