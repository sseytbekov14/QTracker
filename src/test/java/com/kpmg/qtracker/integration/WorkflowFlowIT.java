package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.NotificationRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkflowFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ControlRepository controlRepository;

    @Autowired
    private ControlAssignmentRepository assignmentRepository;

    @Autowired
    private ControlDetailsRepository controlDetailsRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private WorkflowHistoryRepository workflowHistoryRepository;

    private User facilitator;
    private User operator;
    private User soqmLead;
    private User processOwner;
    private Control control;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        facilitator = saveUser("FACILITATOR", "facilitator-" + suffix + "@example.test", "Facilitator " + suffix);
        operator = saveUser("CONTROL_OPERATOR", "operator-" + suffix + "@example.test", "Operator " + suffix);
        soqmLead = saveUser("SOQM_LEAD", "soqm-" + suffix + "@example.test", "SoQM " + suffix);
        processOwner = saveUser("PROCESS_OWNER", "owner-" + suffix + "@example.test", "Owner " + suffix);

        control = new Control();
        control.setControlId("CTRL-" + suffix);
        control.setControlFrequency("Monthly");
        control.setControlStatus("IN_PROGRESS");
        control = controlRepository.save(control);

        ControlAssignment assignment = new ControlAssignment();
        assignment.setControlId(control.getId());
        assignment.setFacilitator(facilitator.getMail());
        assignment.setControlOperator(operator.getMail());
        assignment.setSoqmLead(soqmLead.getMail());
        assignment.setProcessOwner(processOwner.getMail());
        assignmentRepository.save(assignment);

        ControlDetails details = new ControlDetails();
        details.setControlId(control.getId());
        details.setControlStepsPerformed("Steps performed");
        details.setSoqmHeadComments("SoQM comments");
        details.setProcessOwnerComments("Process owner comments");
        controlDetailsRepository.save(details);
    }

    @AfterEach
    void tearDown() {
        if (control != null && control.getId() != null) {
            List<Notification> notifications = notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId());
            notificationRepository.deleteAll(notifications);
            workflowHistoryRepository.deleteAll(
                    workflowHistoryRepository.findByControlIdOrderByCreatedAtDesc(control.getId())
            );
            assignmentRepository.findByControlId(control.getId())
                    .ifPresent(assignmentRepository::delete);
            controlRepository.deleteById(control.getId());
        }
        deleteUser(facilitator);
        deleteUser(operator);
        deleteUser(soqmLead);
        deleteUser(processOwner);
    }

    @Test
    void workflowEndToEnd_createsNotificationsAndTransitions() throws Exception {
        Long controlId = control.getId();
        Map<String, Integer> expectedCounts = new HashMap<>();

        mockMvc.perform(post("/api/workflow/submit-to-control-operator")
                        .param("controlId", String.valueOf(controlId))
                        .sessionAttr("currentUser", facilitator))
                .andExpect(status().isOk());
        assertControlStatus(controlId, "REVIEW");
        expectedCounts.put(operator.getMail(), 1);
        assertNotificationCounts(controlId, expectedCounts);
        assertWorkflowHistoryCount(controlId, 1);

        mockMvc.perform(post("/api/workflow/submit-to-soqm-lead")
                        .param("controlId", String.valueOf(controlId))
                        .sessionAttr("currentUser", operator))
                .andExpect(status().isOk());
        assertControlStatus(controlId, "SOQM_HEAD_REVIEW");
        expectedCounts.put(soqmLead.getMail(), 1);
        assertNotificationCounts(controlId, expectedCounts);
        assertWorkflowHistoryCount(controlId, 2);

        mockMvc.perform(post("/api/workflow/return-to-operator")
                        .param("controlId", String.valueOf(controlId))
                        .sessionAttr("currentUser", soqmLead))
                .andExpect(status().isOk());
        assertControlStatus(controlId, "REVIEW");
        expectedCounts.put(operator.getMail(), 2);
        assertNotificationCounts(controlId, expectedCounts);
        assertWorkflowHistoryCount(controlId, 3);

        mockMvc.perform(post("/api/workflow/submit-to-soqm-lead")
                        .param("controlId", String.valueOf(controlId))
                        .sessionAttr("currentUser", operator))
                .andExpect(status().isOk());
        assertControlStatus(controlId, "SOQM_HEAD_REVIEW");
        expectedCounts.put(soqmLead.getMail(), 2);
        assertNotificationCounts(controlId, expectedCounts);
        assertWorkflowHistoryCount(controlId, 4);

        mockMvc.perform(post("/api/workflow/submit-to-process-owner")
                        .param("controlId", String.valueOf(controlId))
                        .sessionAttr("currentUser", soqmLead))
                .andExpect(status().isOk());
        assertControlStatus(controlId, "PROCESS_OWNER_REVIEW");
        expectedCounts.put(processOwner.getMail(), 1);
        assertNotificationCounts(controlId, expectedCounts);
        assertWorkflowHistoryCount(controlId, 5);

        mockMvc.perform(post("/api/workflow/complete-control")
                        .param("controlId", String.valueOf(controlId))
                        .sessionAttr("currentUser", processOwner))
                .andExpect(status().isOk());
        assertControlStatus(controlId, "COMPLETED");
        expectedCounts.put(facilitator.getMail(), 1);
        expectedCounts.put(operator.getMail(), 3);
        expectedCounts.put(soqmLead.getMail(), 3);
        expectedCounts.put(processOwner.getMail(), 2);
        assertNotificationCounts(controlId, expectedCounts);
        assertWorkflowHistoryCount(controlId, 6);
    }

    @Test
    void submitToControlOperator_withWrongRole_returns403() throws Exception {
        mockMvc.perform(post("/api/workflow/submit-to-control-operator")
                        .param("controlId", String.valueOf(control.getId()))
                        .sessionAttr("currentUser", operator))
                .andExpect(status().isForbidden());
        assertNotificationCounts(control.getId(), Map.of());
    }

    @Test
    void submitToControlOperator_requiresControlStepsPerformed() throws Exception {
        updateDetails(control.getId(), details -> details.setControlStepsPerformed(""));

        mockMvc.perform(post("/api/workflow/submit-to-control-operator")
                        .param("controlId", String.valueOf(control.getId()))
                        .sessionAttr("currentUser", facilitator))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitToSoqmLead_requiresControlStepsPerformed() throws Exception {
        control.setPerformanceStatus("REVIEW");
        controlRepository.save(control);
        updateDetails(control.getId(), details -> details.setControlStepsPerformed(""));

        mockMvc.perform(post("/api/workflow/submit-to-soqm-lead")
                        .param("controlId", String.valueOf(control.getId()))
                        .sessionAttr("currentUser", operator))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitToProcessOwner_requiresSoqmHeadComments() throws Exception {
        control.setPerformanceStatus("SOQM_HEAD_REVIEW");
        controlRepository.save(control);
        updateDetails(control.getId(), details -> details.setSoqmHeadComments(""));

        mockMvc.perform(post("/api/workflow/submit-to-process-owner")
                        .param("controlId", String.valueOf(control.getId()))
                        .sessionAttr("currentUser", soqmLead))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completeControl_requiresProcessOwnerComments() throws Exception {
        control.setPerformanceStatus("PROCESS_OWNER_REVIEW");
        controlRepository.save(control);
        updateDetails(control.getId(), details -> details.setProcessOwnerComments(""));

        mockMvc.perform(post("/api/workflow/complete-control")
                        .param("controlId", String.valueOf(control.getId()))
                        .sessionAttr("currentUser", processOwner))
                .andExpect(status().isBadRequest());
    }

    private User saveUser(String role, String mail, String displayName) {
        User user = new User();
        user.setRole(role);
        user.setMail(mail);
        user.setDisplayName(displayName);
        user.setUsername(mail);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private void deleteUser(User user) {
        if (user == null || user.getId() == null) {
            return;
        }
        userRepository.findById(user.getId()).ifPresent(userRepository::delete);
    }

    private void assertControlStatus(Long controlId, String expectedStatus) {
        String status = controlRepository.findById(controlId)
                .map(Control::getControlStatus)
                .orElse(null);
        assertEquals(expectedStatus, status);
    }

    private void assertWorkflowHistoryCount(Long controlId, int expected) {
        int actual = workflowHistoryRepository.findByControlIdOrderByCreatedAtDesc(controlId).size();
        assertEquals(expected, actual);
    }

    private void assertNotificationCounts(Long controlId, Map<String, Integer> expected) {
        Map<String, Integer> actual = new HashMap<>();
        Map<Long, String> emailByUserId = new HashMap<>();
        for (User user : userRepository.findAll()) {
            emailByUserId.put(user.getId(), user.getMail());
        }
        for (Notification notification : notificationRepository.findByControlIdOrderByCreatedAtDesc(controlId)) {
            String email = emailByUserId.get(notification.getUserId());
            if (email != null) {
                actual.merge(email, 1, Integer::sum);
            }
        }
        assertEquals(expected, actual);
    }

    private void updateDetails(Long controlId, java.util.function.Consumer<ControlDetails> updater) {
        ControlDetails details = controlDetailsRepository.findByControlId(controlId)
                .orElseGet(() -> {
                    ControlDetails fresh = new ControlDetails();
                    fresh.setControlId(controlId);
                    return fresh;
                });
        updater.accept(details);
        controlDetailsRepository.save(details);
    }
}


