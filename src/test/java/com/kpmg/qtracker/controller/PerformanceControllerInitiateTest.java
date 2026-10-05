package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.WorkflowService;
import com.kpmg.qtracker.service.WorkflowTransitionGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PerformanceControllerInitiateTest {

    private static final long CONTROL_ID = 600L;

    @Mock
    private ControlService controlService;
    @Mock
    private ControlAssignmentService controlAssignmentService;
    @Mock
    private WorkflowService workflowService;
    @Mock
    private ControlPermissionService controlPermissionService;

    private MockMvc mockMvc;
    private User currentUser;
    private Control control;

    @BeforeEach
    void setUp() {
        PerformanceController controller = new PerformanceController(
                controlService,
                controlAssignmentService,
                workflowService,
                controlPermissionService,
                new WorkflowTransitionGuard()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        currentUser = new User();
        currentUser.setMail("actor@kpmg.kz");

        control = new Control();
        control.setId(CONTROL_ID);
        when(controlService.getControlById(CONTROL_ID)).thenReturn(Optional.of(control));
    }

    @Test
    void initiate_controlAlreadyInWorkflow_isConflict() throws Exception {
        control.setPerformanceStatus("REVIEW");
        givenPermission(true);

        mockMvc.perform(post("/api/performance/initiate")
                        .param("controlId", String.valueOf(CONTROL_ID))
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isConflict());

        verify(controlService, never()).save(any(Control.class));
        verify(workflowService, never()).initiateWorkflow(anyLong(), anyString(), any(User.class));
    }

    @Test
    void initiate_byParticipantWhoIsNeitherSoqmNorCreator_isForbidden() throws Exception {
        control.setPerformanceStatus("DRAFT");
        givenPermission(false);

        mockMvc.perform(post("/api/performance/initiate")
                        .param("controlId", String.valueOf(CONTROL_ID))
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isForbidden());

        verify(controlService, never()).save(any(Control.class));
        verify(workflowService, never()).initiateWorkflow(anyLong(), anyString(), any(User.class));
    }

    private void givenPermission(boolean soqm) {
        // A facilitator, or a SoQM user who can edit everything
        ControlPermission permission = new ControlPermission(true, true, Set.of(), true, soqm,
                false, !soqm, false, soqm, false);
        when(controlPermissionService.resolve(control, currentUser)).thenReturn(permission);
    }
}
