package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.NotificationRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Business decision of 2026-11-04: a SoQM Admin Team member may be put in every field of a control - Facilitator,
 * Control Operator, SoQM Team / Delegate and Process Owner - and does a step of a field they are in as that
 * participant: no comment needed (a return still needs one), the history names them in that role and not "on
 * behalf". A step of a role they are not in stays one made on behalf of it, with its comment, as before.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SoqmInEveryRoleIT {

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
    private User soqm;
    private User otherSoqm;
    private User facilitator;
    private User operator;
    private User owner;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        soqm = saveUser("SOQM_TEAM", "er-soqm-" + s + "@example.test", "Alieva, Dana");
        otherSoqm = saveUser("SOQM_TEAM", "er-soqm2-" + s + "@example.test", "Other SoQM " + s);
        facilitator = saveUser("FACILITATOR", "er-fac-" + s + "@example.test", "Fac " + s);
        operator = saveUser("CONTROL_OPERATOR", "er-op-" + s + "@example.test", "Op " + s);
        owner = saveUser("PROCESS_OWNER", "er-po-" + s + "@example.test", "Owner " + s);
    }

    @AfterEach
    void tearDown() {
        for (Control control : controls) {
            notificationRepository.deleteAll(notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId()));
            historyRepository.deleteAll(historyRepository.findByControlIdOrderByCreatedAtDesc(control.getId()));
            auditRepository.deleteAll(auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId()));
            detailsRepository.findByControlId(control.getId()).ifPresent(detailsRepository::delete);
            assignmentRepository.findByControlId(control.getId()).ifPresent(assignmentRepository::delete);
            controlRepository.findById(control.getId()).ifPresent(controlRepository::delete);
        }
        users.forEach(u -> userRepository.findById(u.getId()).ifPresent(userRepository::delete));
    }

    // ------------------------------------------------------------------ assignment

    @Test
    void soqmAdminTeam_canBeSavedInEveryField_ofAControl() throws Exception {
        Control control = control("IN_PROGRESS", facilitator, operator, soqm, owner);

        mockMvc.perform(post("/api/control-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + control.getId()
                                + ",\"facilitator\":[\"" + soqm.getMail() + "\"]"
                                + ",\"controlOperator\":[\"" + soqm.getMail() + "\"]"
                                + ",\"soqmLead\":[\"" + soqm.getMail() + "\"]"
                                + ",\"processOwner\":[\"" + soqm.getMail() + "\"]}")
                        .sessionAttr("currentUser", otherSoqm)
                        .with(user(otherSoqm.getMail()).roles("SOQM"))
                        .with(csrf()))
                .andExpect(status().isOk());

        ControlAssignment saved = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(saved.getFacilitator()).isEqualTo(soqm.getMail());
        assertThat(saved.getControlOperator()).isEqualTo(soqm.getMail());
        assertThat(saved.getSoqmLead()).isEqualTo(soqm.getMail());
        assertThat(saved.getProcessOwner()).isEqualTo(soqm.getMail());
    }

    @Test
    void aSoqmUserInEveryRole_isNoProblemForCreateControl() throws Exception {
        Control control = control("IN_PROGRESS", soqm, soqm, soqm, soqm);

        MvcResult preview = mockMvc.perform(get("/api/controls/{id}/next-cycle", control.getId())
                        .sessionAttr("currentUser", otherSoqm)
                        .with(user(otherSoqm.getMail()).roles("SOQM")))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode plan = objectMapper.readTree(preview.getResponse().getContentAsString());
        assertThat(plan.get("problems")).as(plan.toString()).isEmpty();
    }

    // ------------------------------------------------------------------ the whole workflow by one SoQM user

    @Test
    void aSoqmUserInEveryRole_takesTheControlThroughEveryStep_asEachParticipant_withoutComments() throws Exception {
        Control control = control("IN_PROGRESS", soqm, soqm, soqm, soqm);

        // Their own steps: no comment, not on behalf
        mockMvc.perform(workflowPost("/api/workflow/submit-to-control-operator", control, soqm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onBehalf").value(false));
        assertThat(statusOf(control)).isEqualTo("REVIEW");
        move(control, soqm, "SOQM_HEAD_REVIEW", null).andExpect(status().isOk());
        move(control, soqm, "PROCESS_OWNER_REVIEW", null).andExpect(status().isOk());
        mockMvc.perform(workflowPost("/api/workflow/complete-control", control, soqm))
                .andExpect(status().isOk());
        assertThat(statusOf(control)).isEqualTo("COMPLETED");

        // The history names them in each role, never on behalf (no comment was asked: the buttons' own texts only)
        List<WorkflowHistory> history = historyRepository.findByControlIdOrderByCreatedAtDesc(control.getId()).stream()
                .sorted(Comparator.comparing(WorkflowHistory::getId)).toList();
        assertThat(history).extracting(WorkflowHistory::getActedAs)
                .containsExactly("Facilitator", "Control Operator", "SoQM Admin Team", "Process Owner");
        assertThat(history).allSatisfy(entry -> {
            assertThat(entry.getPerformedByEmail()).isEqualTo(soqm.getMail());
            assertThat(entry.isOnBehalf()).as(entry.getActedAs()).isFalse();
        });

        // They get the notifications of the roles they hold, as any participant would
        assertThat(notices(control, soqm)).extracting(Notification::getTitle)
                .contains("Control Operator Review", "SoQM Admin Team Review", "Process Owner Review",
                        "SoQM control completed");
        assertThat(notices(control, soqm)).noneMatch(notice -> "ON_BEHALF".equals(notice.getType()));
    }

    @Test
    void theirOwnReturn_needsAComment_asEveryReturn_andIsNotOnBehalf() throws Exception {
        Control control = control("REVIEW", facilitator, soqm, otherSoqm, owner);

        move(control, soqm, "IN_PROGRESS", "  ").andExpect(status().isBadRequest());
        assertThat(statusOf(control)).isEqualTo("REVIEW");

        move(control, soqm, "IN_PROGRESS", "Redo the sample").andExpect(status().isOk());
        WorkflowHistory history = lastHistory(control);
        assertThat(history.getActedAs()).isEqualTo("Control Operator");
        assertThat(history.isOnBehalf()).isFalse();
        assertThat(history.getComments()).isEqualTo("Redo the sample");
    }

    // ------------------------------------------------------------------ a role they are not in: on behalf, as before

    @Test
    void aStepOfARoleTheyAreNotIn_staysOnBehalf_andNeedsItsComment() throws Exception {
        // In the Facilitator field only; the control is at the Control Operator's step
        Control control = control("REVIEW", soqm, operator, otherSoqm, owner);

        move(control, soqm, "SOQM_HEAD_REVIEW", null)
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "A comment is required when SoQM Admin Team acts for the Control Operator")));
        assertThat(statusOf(control)).isEqualTo("REVIEW");

        move(control, soqm, "SOQM_HEAD_REVIEW", "Operator on leave").andExpect(status().isOk());
        WorkflowHistory history = lastHistory(control);
        assertThat(history.getActedAs()).isEqualTo("Control Operator");
        assertThat(history.isOnBehalf()).isTrue();
        assertThat(history.getAssignedPerformer()).isEqualTo(operator.getMail());
    }

    @Test
    void aSoqmUserNotInTheField_stillActsOnBehalf_ofTheSoqmUserWhoIs() throws Exception {
        Control control = control("IN_PROGRESS", soqm, operator, soqm, owner);

        mockMvc.perform(workflowPost("/api/workflow/submit-to-control-operator", control, otherSoqm))
                .andExpect(status().isBadRequest());
        mockMvc.perform(workflowPost("/api/workflow/submit-to-control-operator", control, otherSoqm)
                        .param("comments", "Dana is away"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onBehalf").value(true));
        WorkflowHistory history = lastHistory(control);
        assertThat(history.getPerformedByEmail()).isEqualTo(otherSoqm.getMail());
        assertThat(history.isOnBehalf()).isTrue();
        assertThat(history.getAssignedPerformer()).isEqualTo(soqm.getMail());
    }

    @Test
    void aDisabledSoqmUser_inTheField_doesNoStep() throws Exception {
        Control control = control("IN_PROGRESS", soqm, operator, otherSoqm, owner);
        soqm.setEnabled(false);
        soqm = userRepository.save(soqm);

        int code = mockMvc.perform(workflowPost("/api/workflow/submit-to-control-operator", control, soqm))
                .andReturn().getResponse().getStatus();
        assertThat(code).isGreaterThanOrEqualTo(400);
        assertThat(statusOf(control)).isEqualTo("IN_PROGRESS");
    }

    // ------------------------------------------------------------------ the page

    @Test
    void thePage_showsTheirParticipantStep_andKeepsTheSoqmMoves() throws Exception {
        Control control = control("IN_PROGRESS", soqm, operator, otherSoqm, owner);

        String page = mockMvc.perform(get("/view-control/{id}", control.getId())
                        .sessionAttr("currentUser", soqm)
                        .with(user(soqm.getMail()).roles("SOQM")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("id=\"isFacilitator\" value=\"true\"", "id=\"isControlOperator\" value=\"false\"",
                "id=\"isProcessOwner\" value=\"false\"", "id=\"soqmMoveBtn\"");
    }

    // ------------------------------------------------------------------ helpers

    /** A Monthly control in the status, with these people and every field a step needs filled. */
    private Control control(String status, User fac, User op, User soqmLead, User po) {
        Control control = new Control();
        control.setControlId("ER-" + UUID.randomUUID().toString().substring(0, 8) + "/FY27/Central/OCT");
        control.setControlFrequency("Monthly");
        control.setControlStatus("ACTIVE");
        control.setPerformanceStatus(status);
        control = controlRepository.save(control);
        controls.add(control);

        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseGet(ControlAssignment::new);
        assignment.setControlId(control.getId());
        assignment.setFacilitator(fac.getMail());
        assignment.setControlOperator(op.getMail());
        assignment.setSoqmLead(soqmLead.getMail());
        assignment.setProcessOwner(po.getMail());
        assignment.setControlOperationDate(LocalDate.of(2026, 10, 5));
        assignment.setControlOperationDeadline(LocalDate.of(2026, 10, 19));
        assignment.setNextControlOperationDate(LocalDate.of(2026, 11, 5));
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

    private org.springframework.test.web.servlet.ResultActions move(Control control, User actor, String target,
                                                                     String comment) throws Exception {
        MockHttpServletRequestBuilder request = workflowPost("/api/workflow/move", control, actor)
                .param("targetStatus", target);
        if (comment != null) {
            request.param("comments", comment);
        }
        return mockMvc.perform(request);
    }

    private String statusOf(Control control) {
        return controlRepository.findById(control.getId()).map(Control::getPerformanceStatus).orElse(null);
    }

    private WorkflowHistory lastHistory(Control control) {
        List<WorkflowHistory> history = historyRepository.findByControlIdOrderByCreatedAtDesc(control.getId());
        assertThat(history).hasSize(1);
        return history.get(0);
    }

    private List<Notification> notices(Control control, User person) {
        return notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId()).stream()
                .filter(notice -> person.getId().equals(notice.getUserId()))
                .toList();
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
