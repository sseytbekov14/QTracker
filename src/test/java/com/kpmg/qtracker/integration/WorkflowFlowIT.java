package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.enums.WorkflowActionType;
import com.kpmg.qtracker.support.TestUsers;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
    private final List<User> extraUsers = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        facilitator = saveUser("FACILITATOR", "facilitator-" + suffix + "@example.test", "Facilitator " + suffix);
        operator = saveUser("CONTROL_OPERATOR", "operator-" + suffix + "@example.test", "Operator " + suffix);
        soqmLead = saveUser("SOQM_TEAM", "soqm-" + suffix + "@example.test", "SoQM " + suffix);
        processOwner = saveUser("PROCESS_OWNER", "owner-" + suffix + "@example.test", "Owner " + suffix);

        control = new Control();
        control.setControlId("CTRL-" + suffix);
        control.setControlFrequency("Monthly");
        control.setControlStatus("IN_PROGRESS");
        control.setPerformanceStatus("IN_PROGRESS");
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
        // Facilitator and Control Operator are different people: Submit to SoQM needs the Operator's field too
        details.setControlOperatorReview("Operator review");
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
        extraUsers.forEach(this::deleteUser);
        extraUsers.clear();
    }

    @Test
    void workflowEndToEnd_createsNotificationsAndTransitions() throws Exception {
        Long controlId = control.getId();
        Map<String, Integer> expectedCounts = new HashMap<>();

        mockMvc.perform(workflowPost("/api/workflow/submit-to-control-operator", controlId, facilitator))
                .andExpect(status().isOk());
        assertPerformanceStatus(controlId, "REVIEW");
        expectedCounts.put(operator.getMail(), 1);
        assertNotificationCounts(controlId, expectedCounts);
        assertWorkflowHistoryCount(controlId, 1);

        mockMvc.perform(workflowPost("/api/workflow/submit-to-soqm-lead", controlId, operator))
                .andExpect(status().isOk());
        assertPerformanceStatus(controlId, "SOQM_HEAD_REVIEW");
        expectedCounts.put(soqmLead.getMail(), 1);
        assertNotificationCounts(controlId, expectedCounts);
        assertWorkflowHistoryCount(controlId, 2);

        mockMvc.perform(workflowPost("/api/workflow/return-to-operator", controlId, soqmLead)
                        .param("comments", "SoQM: steps need evidence"))
                .andExpect(status().isOk());
        assertPerformanceStatus(controlId, "REVIEW");
        expectedCounts.put(operator.getMail(), 2);
        assertNotificationCounts(controlId, expectedCounts);
        assertWorkflowHistoryCount(controlId, 3);

        mockMvc.perform(workflowPost("/api/workflow/submit-to-soqm-lead", controlId, operator))
                .andExpect(status().isOk());
        assertPerformanceStatus(controlId, "SOQM_HEAD_REVIEW");
        expectedCounts.put(soqmLead.getMail(), 2);
        assertNotificationCounts(controlId, expectedCounts);
        assertWorkflowHistoryCount(controlId, 4);

        mockMvc.perform(workflowPost("/api/workflow/submit-to-process-owner", controlId, soqmLead))
                .andExpect(status().isOk());
        assertPerformanceStatus(controlId, "PROCESS_OWNER_REVIEW");
        expectedCounts.put(processOwner.getMail(), 1);
        assertNotificationCounts(controlId, expectedCounts);
        assertWorkflowHistoryCount(controlId, 5);

        // The Process Owner sends it back to the Control Operator (spec 9.4), with the reason
        mockMvc.perform(workflowPost("/api/workflow/return-to-operator", controlId, processOwner)
                        .param("comments", "Owner: the sample is too small"))
                .andExpect(status().isOk());
        assertPerformanceStatus(controlId, "REVIEW");
        expectedCounts.put(operator.getMail(), 3);
        assertNotificationCounts(controlId, expectedCounts);
        assertWorkflowHistoryCount(controlId, 6);
        WorkflowHistory ownerReturn = workflowHistoryRepository.findByControlIdOrderByCreatedAtDesc(controlId).stream()
                .filter(h -> "PROCESS_OWNER_REVIEW".equals(h.getFromStep()))
                .findFirst().orElseThrow();
        assertEquals(WorkflowActionType.RETURN_TO_OPERATOR, ownerReturn.getActionType());
        assertEquals("REVIEW", ownerReturn.getToStep());
        assertEquals("Owner: the sample is too small", ownerReturn.getComments());
        assertEquals("Owner: the sample is too small",
                controlRepository.findById(controlId).orElseThrow().getReturnToOperatorComment());
        assertEquals(1L, notificationRepository.findByControlIdOrderByCreatedAtDesc(controlId).stream()
                .filter(n -> operator.getId().equals(n.getUserId()))
                .filter(n -> "RETURN_TO_OPERATOR".equals(n.getType()))
                .filter(n -> n.getMessage() != null && n.getMessage().contains("Owner: the sample is too small"))
                .count());

        mockMvc.perform(workflowPost("/api/workflow/submit-to-soqm-lead", controlId, operator))
                .andExpect(status().isOk());
        expectedCounts.put(soqmLead.getMail(), 3);
        mockMvc.perform(workflowPost("/api/workflow/submit-to-process-owner", controlId, soqmLead))
                .andExpect(status().isOk());
        expectedCounts.put(processOwner.getMail(), 2);
        assertNotificationCounts(controlId, expectedCounts);
        assertWorkflowHistoryCount(controlId, 8);

        mockMvc.perform(workflowPost("/api/workflow/complete-control", controlId, processOwner))
                .andExpect(status().isOk());
        assertPerformanceStatus(controlId, "COMPLETED");
        expectedCounts.put(facilitator.getMail(), 1);
        expectedCounts.put(operator.getMail(), 4);
        expectedCounts.put(soqmLead.getMail(), 4);
        // COMPLETED_ALL goes to facilitator, operator and SoQM lead only; the process owner keeps 2
        assertNotificationCounts(controlId, expectedCounts);
        assertWorkflowHistoryCount(controlId, 9);
    }

    /**
     * One person is Facilitator, Control Operator and Process Owner (the Operator field spells the address in
     * capitals): every move of the control to one of their steps notifies them once, the returns they make
     * themselves included.
     */
    @Test
    void personWithSeveralFields_isNotifiedOnEveryMoveToTheirStep_onceEachTime() throws Exception {
        User both = saveUser("PROCESS_OWNER", "both-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test",
                "Fac Op Owner");
        extraUsers.add(both);
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setFacilitator(both.getMail());
        assignment.setControlOperator(both.getMail().toUpperCase());
        assignment.setProcessOwner(both.getMail());
        assignmentRepository.save(assignment);
        Long controlId = control.getId();

        mockMvc.perform(workflowPost("/api/workflow/submit-to-control-operator", controlId, both)).andExpect(status().isOk());
        assertNotices(controlId, both, 1);
        mockMvc.perform(workflowPost("/api/workflow/return-to-facilitator", controlId, both)
                .param("comments", "Back to myself as Facilitator")).andExpect(status().isOk());
        assertPerformanceStatus(controlId, "IN_PROGRESS");
        assertNotices(controlId, both, 2);
        assertEquals(1L, noticesOfType(controlId, both, "RETURN_TO_FACILITATOR"));

        mockMvc.perform(workflowPost("/api/workflow/submit-to-control-operator", controlId, both)).andExpect(status().isOk());
        assertNotices(controlId, both, 3);
        mockMvc.perform(workflowPost("/api/workflow/submit-to-soqm-lead", controlId, both)).andExpect(status().isOk());
        assertNotices(controlId, both, 3);
        mockMvc.perform(workflowPost("/api/workflow/return-to-operator", controlId, soqmLead)
                .param("comments", "SoQM: more evidence")).andExpect(status().isOk());
        assertNotices(controlId, both, 4);

        mockMvc.perform(workflowPost("/api/workflow/submit-to-soqm-lead", controlId, both)).andExpect(status().isOk());
        mockMvc.perform(workflowPost("/api/workflow/submit-to-process-owner", controlId, soqmLead)).andExpect(status().isOk());
        assertNotices(controlId, both, 5);
        mockMvc.perform(workflowPost("/api/workflow/return-to-operator", controlId, both)
                .param("comments", "Owner: back to myself as Operator")).andExpect(status().isOk());
        assertPerformanceStatus(controlId, "REVIEW");
        assertNotices(controlId, both, 6);
        assertEquals(2L, noticesOfType(controlId, both, "RETURN_TO_OPERATOR"));

        mockMvc.perform(workflowPost("/api/workflow/submit-to-soqm-lead", controlId, both)).andExpect(status().isOk());
        mockMvc.perform(workflowPost("/api/workflow/submit-to-process-owner", controlId, soqmLead)).andExpect(status().isOk());
        assertNotices(controlId, both, 7);
        // Completed goes to the Facilitator and the Operator: the same person, one notice
        mockMvc.perform(workflowPost("/api/workflow/complete-control", controlId, both)).andExpect(status().isOk());
        assertPerformanceStatus(controlId, "COMPLETED");
        assertNotices(controlId, both, 8);
    }

    /** The same through POST /api/workflow/perform-action (returns to the Facilitator and by the Process Owner). */
    @Test
    void personWithSeveralFields_isNotifiedOfTheirOwnReturns_throughPerformAction() throws Exception {
        User both = saveUser("PROCESS_OWNER", "both-pa-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test",
                "Fac Op Owner PA");
        extraUsers.add(both);
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setFacilitator(both.getMail());
        assignment.setControlOperator(both.getMail());
        assignment.setProcessOwner(both.getMail());
        assignmentRepository.save(assignment);
        Long controlId = control.getId();

        performAction(controlId, both, "SUBMIT_TO_CONTROL_OPERATOR", null);
        assertNotices(controlId, both, 1);
        performAction(controlId, both, "RETURN_TO_FACILITATOR", "Back to myself");
        assertPerformanceStatus(controlId, "IN_PROGRESS");
        assertNotices(controlId, both, 2);
        assertEquals(1L, noticesOfType(controlId, both, "RETURN_TO_FACILITATOR"));

        control = controlRepository.findById(controlId).orElseThrow();
        control.setPerformanceStatus("PROCESS_OWNER_REVIEW");
        controlRepository.save(control);
        performAction(controlId, both, "SEND_FOR_REVISION", "Owner wants changes");
        assertPerformanceStatus(controlId, "REVIEW");
        assertNotices(controlId, both, 3);
        assertEquals(1L, noticesOfType(controlId, both, "RETURN_TO_OPERATOR"));
    }

    @Test
    void returnsWithoutAComment_areRefused_andChangeNothing() throws Exception {
        Long controlId = control.getId();
        mockMvc.perform(workflowPost("/api/workflow/submit-to-control-operator", controlId, facilitator))
                .andExpect(status().isOk());

        mockMvc.perform(workflowPost("/api/workflow/return-to-facilitator", controlId, operator))
                .andExpect(status().isBadRequest());
        mockMvc.perform(workflowPost("/api/workflow/return-to-facilitator", controlId, operator)
                        .param("comments", "  "))
                .andExpect(status().isBadRequest());

        assertPerformanceStatus(controlId, "REVIEW");
        assertWorkflowHistoryCount(controlId, 1);
    }

    @Test
    void returnedAgainWithinMinutes_facilitatorIsNotifiedEachTime() throws Exception {
        Long controlId = control.getId();

        for (int round = 1; round <= 2; round++) {
            mockMvc.perform(workflowPost("/api/workflow/submit-to-control-operator", controlId, facilitator))
                    .andExpect(status().isOk());
            mockMvc.perform(workflowPost("/api/workflow/return-to-facilitator", controlId, operator)
                            .param("comments", "Fix round " + round))
                    .andExpect(status().isOk());
            assertPerformanceStatus(controlId, "IN_PROGRESS");
        }

        long returnNotices = notificationRepository.findByControlIdOrderByCreatedAtDesc(controlId).stream()
                .filter(notification -> facilitator.getId().equals(notification.getUserId()))
                .filter(notification -> "RETURN_TO_FACILITATOR".equals(notification.getType()))
                .count();
        assertEquals(2L, returnNotices);
    }

    @Test
    void submitToSoqm_notifiesEverySoqmLead_whenTheListUsesSemicolons() throws Exception {
        User secondSoqm = saveUser("SOQM_TEAM", "soqm2-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test", "Second SoQM");
        extraUsers.add(secondSoqm);
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setSoqmLead(soqmLead.getMail() + "; " + secondSoqm.getMail());
        assignmentRepository.save(assignment);
        control.setPerformanceStatus("REVIEW");
        controlRepository.save(control);

        mockMvc.perform(workflowPost("/api/workflow/submit-to-soqm-lead", control.getId(), operator))
                .andExpect(status().isOk());

        assertNotificationCounts(control.getId(), Map.of(soqmLead.getMail(), 1, secondSoqm.getMail(), 1));
    }

    @Test
    void submitToSoqm_notifiesTheSoqmLead_whenAssignedInAnotherCase() throws Exception {
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setSoqmLead(soqmLead.getMail().toUpperCase());
        assignmentRepository.save(assignment);
        control.setPerformanceStatus("REVIEW");
        controlRepository.save(control);

        mockMvc.perform(workflowPost("/api/workflow/submit-to-soqm-lead", control.getId(), operator))
                .andExpect(status().isOk());

        assertNotificationCounts(control.getId(), Map.of(soqmLead.getMail(), 1));
    }

    @Test
    void submitToControlOperator_withWrongRole_returns403() throws Exception {
        mockMvc.perform(workflowPost("/api/workflow/submit-to-control-operator", control.getId(), operator))
                .andExpect(status().isForbidden());
        assertNotificationCounts(control.getId(), Map.of());
    }

    @Test
    void submitToControlOperator_requiresControlStepsPerformed() throws Exception {
        updateDetails(control.getId(), details -> details.setControlStepsPerformed(""));

        mockMvc.perform(workflowPost("/api/workflow/submit-to-control-operator", control.getId(), facilitator))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitToSoqmLead_requiresControlStepsPerformed() throws Exception {
        control.setPerformanceStatus("REVIEW");
        controlRepository.save(control);
        updateDetails(control.getId(), details -> details.setControlStepsPerformed(""));

        mockMvc.perform(workflowPost("/api/workflow/submit-to-soqm-lead", control.getId(), operator))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitToProcessOwner_requiresSoqmHeadComments() throws Exception {
        control.setPerformanceStatus("SOQM_HEAD_REVIEW");
        controlRepository.save(control);
        updateDetails(control.getId(), details -> details.setSoqmHeadComments(""));

        mockMvc.perform(workflowPost("/api/workflow/submit-to-process-owner", control.getId(), soqmLead))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completeControl_requiresProcessOwnerComments() throws Exception {
        control.setPerformanceStatus("PROCESS_OWNER_REVIEW");
        controlRepository.save(control);
        updateDetails(control.getId(), details -> details.setProcessOwnerComments(""));

        mockMvc.perform(workflowPost("/api/workflow/complete-control", control.getId(), processOwner))
                .andExpect(status().isBadRequest());
    }

    /** Workflow POST that passes the security filter chain: authenticated principal, CSRF token, app session user. */
    private MockHttpServletRequestBuilder workflowPost(String path, Long controlId, User actor) {
        return post(path)
                .param("controlId", String.valueOf(controlId))
                .sessionAttr("currentUser", actor)
                .with(user(actor.getMail()).roles(actor.getRole()))
                .with(csrf());
    }

    private void performAction(Long controlId, User actor, String action, String comment) throws Exception {
        String body = "{\"controlId\":" + controlId + ",\"action\":\"" + action + "\""
                + (comment != null ? ",\"comment\":\"" + comment + "\"" : "") + "}";
        mockMvc.perform(post("/api/workflow/perform-action")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body)
                        .sessionAttr("currentUser", actor)
                        .with(user(actor.getMail()).roles(actor.getRole()))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    private void assertNotices(Long controlId, User person, long expected) {
        assertEquals(expected, notificationRepository.findByControlIdOrderByCreatedAtDesc(controlId).stream()
                .filter(notification -> person.getId().equals(notification.getUserId()))
                .count());
    }

    private long noticesOfType(Long controlId, User person, String type) {
        return notificationRepository.findByControlIdOrderByCreatedAtDesc(controlId).stream()
                .filter(notification -> person.getId().equals(notification.getUserId()))
                .filter(notification -> type.equals(notification.getType()))
                .count();
    }

    private User saveUser(String role, String mail, String displayName) {
        User user = new User();
        TestUsers.withRole(user, role);
        user.setMail(mail);
        user.setDisplayName(displayName);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private void deleteUser(User user) {
        if (user == null || user.getId() == null) {
            return;
        }
        userRepository.findById(user.getId()).ifPresent(userRepository::delete);
    }

    private void assertPerformanceStatus(Long controlId, String expectedStatus) {
        String status = controlRepository.findById(controlId)
                .map(Control::getPerformanceStatus)
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


