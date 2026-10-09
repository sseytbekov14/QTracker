package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.enums.WorkflowActionType;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.NotificationRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.service.NotificationService;
import com.kpmg.qtracker.service.WorkflowMoveService;
import com.kpmg.qtracker.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Business decision 3: SoQM moves a control on or back for any role. History and audit name the SoQM user,
 * the role they acted for, the people assigned to that step and "on behalf"; a return and every step made for
 * someone else need a comment; the assigned people get an in-app copy besides the usual notification.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SoqmOnBehalfIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ControlRepository controlRepository;
    @Autowired
    private ControlAssignmentRepository assignmentRepository;
    @Autowired
    private ControlDetailsRepository detailsRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private WorkflowHistoryRepository historyRepository;
    @Autowired
    private AdminAuditLogRepository auditRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<User> users = new ArrayList<>();
    private final List<Control> controls = new ArrayList<>();
    private User facilitator;
    private User operator;
    private User soqmLead;
    private User owner;
    private User otherSoqm;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        facilitator = saveUser("FACILITATOR", "ob-fac-" + s + "@example.test", "Fac " + s);
        operator = saveUser("CONTROL_OPERATOR", "ob-op-" + s + "@example.test", "Op " + s);
        soqmLead = saveUser("SOQM_TEAM", "ob-soqm-" + s + "@example.test", "SoQM Lead " + s);
        owner = saveUser("PROCESS_OWNER", "ob-po-" + s + "@example.test", "Owner " + s);
        // Any SoQM user acts, assigned to the control or not
        otherSoqm = saveUser("SOQM_TEAM", "ob-soqm2-" + s + "@example.test", "SoQM Other " + s);
    }

    @AfterEach
    void tearDown() {
        for (Control control : controls) {
            notificationRepository.deleteAll(notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId()));
            historyRepository.deleteAll(historyRepository.findByControlIdOrderByCreatedAtDesc(control.getId()));
            auditRepository.deleteAll(auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId()));
            assignmentRepository.findByControlId(control.getId()).ifPresent(assignmentRepository::delete);
            controlRepository.deleteById(control.getId());
        }
        users.forEach(user -> userRepository.findById(user.getId()).ifPresent(userRepository::delete));
    }

    @Test
    void soqmSubmitsForTheFacilitator_recordsOnBehalf_auditsIt_andCopiesTheFacilitator() throws Exception {
        Control control = control("IN_PROGRESS");

        mockMvc.perform(workflowPost("/api/workflow/submit-to-control-operator", control, otherSoqm)
                        .param("comments", "Facilitator on leave, evidence checked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onBehalf").value(true));

        assertThat(statusOf(control)).isEqualTo("REVIEW");
        WorkflowHistory history = lastHistory(control);
        assertThat(history.getActionType()).isEqualTo(WorkflowActionType.SUBMIT_TO_OPERATOR);
        assertThat(history.getPerformedByEmail()).isEqualTo(otherSoqm.getMail());
        assertThat(history.getActedAs()).isEqualTo("Facilitator");
        assertThat(history.isOnBehalf()).isTrue();
        assertThat(history.getAssignedPerformer()).isEqualTo(facilitator.getMail());
        assertThat(history.getComments()).isEqualTo("Facilitator on leave, evidence checked");

        AdminAuditLog audit = moveAudit(control);
        assertThat(audit.getAdminEmail()).isEqualTo(otherSoqm.getMail());
        assertThat(audit.getActionDescription()).contains("In Progress -> Review", "on behalf of the Facilitator");
        JsonNode previous = objectMapper.readTree(audit.getPreviousValues());
        JsonNode next = objectMapper.readTree(audit.getNewValues());
        assertThat(previous.get("Performance Status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(next.get("Performance Status").asText()).isEqualTo("REVIEW");
        assertThat(next.get("Acted as").asText()).isEqualTo("Facilitator");
        assertThat(next.get("On behalf").asBoolean()).isTrue();
        assertThat(next.get("Assigned").asText()).isEqualTo(facilitator.getMail());

        // The usual notice to the Control Operator, and an in-app copy to the Facilitator
        assertThat(notices(control, operator)).isNotEmpty();
        assertThat(noticesOfType(control, facilitator, NotificationService.TYPE_ON_BEHALF)).hasSize(1);
        assertThat(noticesOfType(control, facilitator, NotificationService.TYPE_ON_BEHALF).get(0).getMessage())
                .contains("Facilitator", "Facilitator on leave, evidence checked");
        assertThat(noticesOfType(control, operator, NotificationService.TYPE_ON_BEHALF)).isEmpty();
    }

    @Test
    void soqmActingForAnotherRole_withoutAComment_isRefused_andChangesNothing() throws Exception {
        Control control = control("PROCESS_OWNER_REVIEW");

        mockMvc.perform(workflowPost("/api/workflow/complete-control", control, soqmLead))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("A comment is required when SoQM acts for the Process Owner"));
        mockMvc.perform(workflowPost("/api/workflow/move", control, soqmLead)
                        .param("targetStatus", "IN_PROGRESS")
                        .param("comments", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(WorkflowMoveService.RETURN_COMMENT_REQUIRED));

        assertThat(statusOf(control)).isEqualTo("PROCESS_OWNER_REVIEW");
        assertThat(historyRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).isEmpty();
        assertThat(auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).isEmpty();
        assertThat(notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).isEmpty();
    }

    @Test
    void soqmReturnsFromProcessOwnerReviewStraightToInProgress_forTheProcessOwner() throws Exception {
        Control control = control("PROCESS_OWNER_REVIEW");

        mockMvc.perform(workflowPost("/api/workflow/move", control, soqmLead)
                        .param("targetStatus", "IN_PROGRESS")
                        .param("comments", "The March evidence is missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlStatus").value("IN_PROGRESS"));

        Control saved = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(saved.getPerformanceStatus()).isEqualTo("IN_PROGRESS");
        assertThat(saved.getReturnToFacilitatorComment()).isEqualTo("The March evidence is missing");
        WorkflowHistory history = lastHistory(control);
        assertThat(history.getActionType()).isEqualTo(WorkflowActionType.RETURN_TO_FACILITATOR);
        assertThat(history.getFromStep()).isEqualTo("PROCESS_OWNER_REVIEW");
        assertThat(history.getToStep()).isEqualTo("IN_PROGRESS");
        assertThat(history.getActedAs()).isEqualTo("Process Owner");
        assertThat(history.isOnBehalf()).isTrue();
        assertThat(history.getAssignedPerformer()).isEqualTo(owner.getMail());

        // The Facilitator gets the return, the Process Owner the copy
        assertThat(noticesOfType(control, facilitator, "RETURN_TO_FACILITATOR")).hasSize(1);
        assertThat(noticesOfType(control, owner, NotificationService.TYPE_ON_BEHALF)).hasSize(1);
        assertThat(notices(control, operator)).isEmpty();
    }

    @Test
    void soqmsOwnReturn_isNotOnBehalfOfAnyone() throws Exception {
        Control control = control("SOQM_HEAD_REVIEW");

        mockMvc.perform(workflowPost("/api/workflow/move", control, soqmLead)
                        .param("targetStatus", "IN_PROGRESS")
                        .param("comments", "Start over"))
                .andExpect(status().isOk());

        WorkflowHistory history = lastHistory(control);
        assertThat(history.getActedAs()).isEqualTo("SoQM Team");
        assertThat(history.isOnBehalf()).isFalse();
        assertThat(notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId()))
                .noneMatch(notice -> NotificationService.TYPE_ON_BEHALF.equals(notice.getType()));
        assertThat(noticesOfType(control, facilitator, "RETURN_TO_FACILITATOR")).hasSize(1);
    }

    @Test
    void changelog_namesTheSoqmUser_theRoleTheyActedFor_andTheAssignedPeople() throws Exception {
        Control control = control("REVIEW");
        mockMvc.perform(workflowPost("/api/workflow/return-to-facilitator", control, soqmLead)
                        .param("comments", "Redo the sample"))
                .andExpect(status().isOk());

        String json = mockMvc.perform(get("/api/controls/{id}/changelog", control.getId())
                        .sessionAttr("currentUser", soqmLead)
                        .with(user(soqmLead.getMail()).roles(soqmLead.getRole())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode entries = objectMapper.readTree(json);
        JsonNode move = null;
        for (JsonNode entry : entries) {
            assertThat(entry.path("eventName").asText()).as("the audit entry of a move is not shown twice")
                    .isNotEqualTo("Edit Control");
            if ("REVIEW".equals(entry.path("fromStep").asText())) {
                move = entry;
            }
        }
        assertThat(move).isNotNull();
        assertThat(move.path("toStep").asText()).isEqualTo("IN_PROGRESS");
        assertThat(move.path("actorEmail").asText()).isEqualTo(soqmLead.getMail());
        assertThat(move.path("onBehalf").asBoolean()).isTrue();
        assertThat(move.path("actedAs").asText()).isEqualTo("Control Operator");
        assertThat(move.path("assignedPerformer").asText()).isEqualTo(operator.getDisplayName() + " (" + operator.getMail() + ")");
        assertThat(move.path("eventDetails").asText()).isEqualTo("Redo the sample");
    }

    @Test
    void adminWithoutSoqmLevel_andReadOnlyUsers_moveNothing() throws Exception {
        Control control = control("IN_PROGRESS");
        User admin = saveUser("ADMIN", "ob-admin-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test", "Admin");
        admin.setAdminAccess(true);
        userRepository.save(admin);
        User readOnly = saveUser("READ_ONLY", "ob-ro-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test", "RO");
        readOnly.setAccessScope(com.kpmg.qtracker.enums.AccessScope.ALL);
        userRepository.save(readOnly);

        mockMvc.perform(workflowPost("/api/workflow/move", control, admin)
                        .param("targetStatus", "REVIEW").param("comments", "x"))
                .andExpect(status().isForbidden());
        mockMvc.perform(workflowPost("/api/workflow/submit-to-control-operator", control, admin)
                        .param("comments", "x"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("Only the assigned Facilitator or SoQM")));
        mockMvc.perform(workflowPost("/api/workflow/move", control, readOnly)
                        .param("targetStatus", "REVIEW").param("comments", "x"))
                .andExpect(status().isForbidden());
        assertThat(statusOf(control)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void soqmFillsTheParticipantsFields_andTheAuditNamesSoqmAsTheAuthor() throws Exception {
        Control control = control("PROCESS_OWNER_REVIEW");

        mockMvc.perform(put("/api/controls/{id}", control.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processOwnerComments\":\"Agreed with the owner by phone\"}")
                        .sessionAttr("currentUser", soqmLead)
                        .with(user(soqmLead.getMail()).roles(soqmLead.getRole()))
                        .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/control-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + control.getId() + ",\"controlOperatorReview\":\"Reviewed for the Operator\"}")
                        .sessionAttr("currentUser", soqmLead)
                        .with(user(soqmLead.getMail()).roles(soqmLead.getRole()))
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(controlRepository.findById(control.getId()).orElseThrow().getProcessOwnerComments())
                .isEqualTo("Agreed with the owner by phone");
        assertThat(detailsRepository.findByControlId(control.getId()).orElseThrow().getControlOperatorReview())
                .isEqualTo("Reviewed for the Operator");
        List<AdminAuditLog> edits = auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId());
        assertThat(edits).isNotEmpty().allMatch(entry -> soqmLead.getMail().equals(entry.getAdminEmail()));
        assertThat(edits).anyMatch(entry -> entry.getNewValues() != null
                && entry.getNewValues().contains("Agreed with the owner by phone"));
        assertThat(edits).anyMatch(entry -> entry.getNewValues() != null
                && entry.getNewValues().contains("Reviewed for the Operator"));
    }

    // ------------------------------------------------------------------ helpers

    /** A control in the status, with the four people (Facilitator and Operator differ) and every field filled. */
    private Control control(String status) {
        Control control = new Control();
        control.setControlId("OB-" + UUID.randomUUID().toString().substring(0, 8));
        control.setControlFrequency("Monthly");
        control.setControlStatus("ACTIVE");
        control.setPerformanceStatus(status);
        control = controlRepository.save(control);
        controls.add(control);

        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseGet(ControlAssignment::new);
        assignment.setControlId(control.getId());
        assignment.setFacilitator(facilitator.getMail());
        assignment.setControlOperator(operator.getMail());
        assignment.setSoqmLead(soqmLead.getMail());
        assignment.setProcessOwner(owner.getMail());
        assignmentRepository.save(assignment);

        ControlDetails details = detailsRepository.findByControlId(control.getId()).orElseGet(ControlDetails::new);
        details.setControlId(control.getId());
        details.setControlStepsPerformed("Steps performed");
        details.setControlOperatorReview("Operator review");
        details.setSoqmHeadComments("SoQM comments");
        details.setProcessOwnerComments("Process owner comments");
        detailsRepository.save(details);
        return control;
    }

    private MockHttpServletRequestBuilder workflowPost(String path, Control control, User actor) {
        return post(path)
                .param("controlId", String.valueOf(control.getId()))
                .sessionAttr("currentUser", actor)
                .with(user(actor.getMail()).roles(String.valueOf(actor.getAccessLevel())))
                .with(csrf());
    }

    private String statusOf(Control control) {
        return controlRepository.findById(control.getId()).map(Control::getPerformanceStatus).orElse(null);
    }

    private WorkflowHistory lastHistory(Control control) {
        List<WorkflowHistory> history = historyRepository.findByControlIdOrderByCreatedAtDesc(control.getId());
        assertThat(history).hasSize(1);
        return history.get(0);
    }

    private AdminAuditLog moveAudit(Control control) {
        List<AdminAuditLog> audit = auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId()).stream()
                .filter(entry -> WorkflowMoveService.AUDIT_ACTION.equals(entry.getActionType()))
                .toList();
        assertThat(audit).hasSize(1);
        return audit.get(0);
    }

    private List<Notification> notices(Control control, User person) {
        return notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId()).stream()
                .filter(notice -> person.getId().equals(notice.getUserId()))
                .toList();
    }

    private List<Notification> noticesOfType(Control control, User person, String type) {
        return notices(control, person).stream().filter(notice -> type.equals(notice.getType())).toList();
    }

    private User saveUser(String role, String mail, String displayName) {
        User user = new User();
        TestUsers.withRole(user, role);
        user.setMail(mail);
        user.setDisplayName(displayName);
        user.setEnabled(true);
        user = userRepository.save(user);
        users.add(user);
        return user;
    }
}
