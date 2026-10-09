package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.dto.ControlResponseDTO;
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
import com.kpmg.qtracker.repository.ReminderControlProjection;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.DeadlineOverdue;
import com.kpmg.qtracker.service.WorkflowMoveService;
import com.kpmg.qtracker.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Business decision 4: SoQM returns a completed control to any earlier status, with a comment; the values,
 * the deadline and the completion in the history stay; the people of the target step are notified; no overdue
 * notice goes out while it is returned; "Closed late" is worked out again from the next completion. Without a
 * return nobody changes a completed control, SoQM included (spec 9.5); renaming its ID stays SoQM's.
 */
@SpringBootTest(properties = "file.upload.dir=target/it-uploads-reopen")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReopenCompletedIT {

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

    private final List<User> users = new ArrayList<>();
    private final List<Control> controls = new ArrayList<>();
    private User facilitator;
    private User operator;
    private User soqm;
    private User owner;
    private User shared;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        facilitator = saveUser("FACILITATOR", "ro-fac-" + s + "@example.test");
        operator = saveUser("CONTROL_OPERATOR", "ro-op-" + s + "@example.test");
        soqm = saveUser("SOQM_TEAM", "ro-soqm-" + s + "@example.test");
        owner = saveUser("PROCESS_OWNER", "ro-po-" + s + "@example.test");
        shared = saveUser("FACILITATOR", "ro-shared-" + s + "@example.test");
        today = DeadlineOverdue.today(Instant.now());
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

    @ParameterizedTest(name = "back to {0}")
    @CsvSource({
            "IN_PROGRESS,          FACILITATOR,      RETURN_TO_FACILITATOR,   RETURN_TO_FACILITATOR",
            "REVIEW,               CONTROL_OPERATOR, RETURN_TO_OPERATOR,      RETURN_TO_OPERATOR",
            "SOQM_HEAD_REVIEW,     SOQM,             RETURN,                  RETURN_TO_SOQM_TEAM",
            "PROCESS_OWNER_REVIEW, PROCESS_OWNER,    RETURN,                  RETURN_TO_PROCESS_OWNER",
    })
    void soqmReturnsACompletedControl_keepsItsValuesAndDeadline_andNotifiesTheTargetStep(
            String target, String notified, WorkflowActionType historyType, String noticeType) throws Exception {
        Control control = completedControl(today.minusDays(3), today.minusDays(5));

        mockMvc.perform(as(soqm, post("/api/workflow/move"), control)
                        .param("targetStatus", target)
                        .param("comments", "Wrong sample, redo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlStatus").value(target));

        Control saved = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(saved.getPerformanceStatus()).isEqualTo(target);
        assertThat(saved.getReopenedAt()).isNotNull();
        assertThat(saved.getDeadline()).isEqualTo(today.minusDays(3));
        assertThat(assignmentRepository.findByControlId(control.getId()).orElseThrow().getControlOperationDeadline())
                .isEqualTo(today.minusDays(3));
        ControlDetails details = detailsRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(details.getControlStepsPerformed()).isEqualTo("Steps performed");
        assertThat(details.getProcessOwnerComments()).isEqualTo("Process owner comments");

        List<WorkflowHistory> history = historyRepository.findByControlIdOrderByCreatedAtDesc(control.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getFromStep()).isEqualTo("COMPLETED");
        assertThat(history.get(0).getToStep()).isEqualTo(target);
        assertThat(history.get(0).getActionType()).isEqualTo(historyType);
        assertThat(history.get(0).getComments()).isEqualTo("Wrong sample, redo");
        assertThat(history.get(0).isOnBehalf()).isFalse();
        // The completion stays in the history, with its date
        assertThat(history.get(1).getToStep()).isEqualTo("COMPLETED");
        assertThat(history.get(1).getCreatedAt().toLocalDate()).isEqualTo(today.minusDays(5));
        assertThat(auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId()))
                .anyMatch(entry -> WorkflowMoveService.AUDIT_ACTION.equals(entry.getActionType())
                        && entry.getActionDescription().contains("Completed -> "));

        User expected = switch (notified) {
            case "FACILITATOR" -> facilitator;
            case "CONTROL_OPERATOR" -> operator;
            case "SOQM" -> soqm;
            default -> owner;
        };
        assertThat(notices(control)).filteredOn(notice -> expected.getId().equals(notice.getUserId()))
                .extracting(Notification::getType).containsExactly(noticeType);
    }

    @Test
    void returnWithoutAComment_isRefused_andTheControlStaysCompleted() throws Exception {
        Control control = completedControl(today.plusDays(3), today.minusDays(1));

        mockMvc.perform(as(soqm, post("/api/workflow/move"), control).param("targetStatus", "REVIEW"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(WorkflowMoveService.RETURN_COMMENT_REQUIRED));

        Control saved = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(saved.getPerformanceStatus()).isEqualTo("COMPLETED");
        assertThat(saved.getReopenedAt()).isNull();
        assertThat(historyRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).hasSize(1);
        assertThat(notices(control)).isEmpty();
    }

    @Test
    void onlySoqmReturnsACompletedControl() throws Exception {
        Control control = completedControl(today.plusDays(3), today.minusDays(1));

        mockMvc.perform(as(owner, post("/api/workflow/move"), control)
                        .param("targetStatus", "PROCESS_OWNER_REVIEW").param("comments", "x"))
                .andExpect(status().isConflict());
        mockMvc.perform(as(shared, post("/api/workflow/move"), control)
                        .param("targetStatus", "SOQM_HEAD_REVIEW").param("comments", "x"))
                .andExpect(status().isForbidden());
        // The old shared resubmit is gone
        mockMvc.perform(as(shared, post("/api/workflow/shared-submit-to-soqm-lead"), control))
                .andExpect(status().isNotFound());
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getPerformanceStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void completedControl_isLockedForSoqmToo_butSoqmStillRenamesIt() throws Exception {
        Control control = completedControl(today.plusDays(3), today.minusDays(1));
        String locked = "VALIDATION_ERROR: " + AccessPolicy.LOCKED_MESSAGE;

        mockMvc.perform(as(soqm, put("/api/controls/{id}", control.getId()), null)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlDescription\":\"Changed after completion\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(locked));
        mockMvc.perform(as(soqm, post("/api/control-details"), null)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + control.getId() + ",\"controlStepsPerformed\":\"Changed\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(locked));
        mockMvc.perform(as(soqm, post("/api/control-assignment"), null)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + control.getId() + "}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(locked));
        mockMvc.perform(as(soqm, multipart("/api/attachments/upload/{id}", control.getId())
                        .file(new MockMultipartFile("attachmentDetails", "late.pdf", "application/pdf",
                                "%PDF-1.4 late".getBytes(StandardCharsets.UTF_8))), null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(AccessPolicy.LOCKED_MESSAGE));
        mockMvc.perform(as(owner, put("/api/controls/{id}", control.getId()), null)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processOwnerComments\":\"Changed\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString(AccessPolicy.LOCKED_MESSAGE)));

        // View Control reads the same rule (AccessPolicy.isLocked) from /api/permissions
        for (User user : List.of(soqm, owner, shared)) {
            mockMvc.perform(as(user, get("/api/permissions/{id}", control.getId()), null))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions.locked").value(true))
                    .andExpect(jsonPath("$.permissions.completedEdit").value(false));
        }

        Control unchanged = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(unchanged.getControlDescription()).isNull();
        assertThat(detailsRepository.findByControlId(control.getId()).orElseThrow().getControlStepsPerformed())
                .isEqualTo("Steps performed");

        String newId = control.getControlId() + "-R";
        mockMvc.perform(as(soqm, post("/api/controls/{id}/rename-id", control.getId()), null)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newControlId\":\"" + newId + "\"}"))
                .andExpect(status().isOk());
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getControlId()).isEqualTo(newId);
    }

    @Test
    void returnedControl_getsNoOverdueNotice_andClosedLateFollowsTheNextCompletion() throws Exception {
        // Completed on time (the day before its deadline, which has passed since)
        LocalDate deadline = today.minusDays(2);
        Control control = completedControl(deadline, deadline.minusDays(1));
        assertThat(controlsRow(control).isClosedLate()).isFalse();

        mockMvc.perform(as(soqm, post("/api/workflow/move"), control)
                        .param("targetStatus", "PROCESS_OWNER_REVIEW")
                        .param("comments", "Owner's comments do not match the evidence"))
                .andExpect(status().isOk());

        // Returned: past its deadline it shows as overdue on the pages, but no overdue notice is sent
        ControlResponseDTO returned = controlsRow(control);
        assertThat(returned.isOverdue()).isTrue();
        assertThat(returned.isClosedLate()).isFalse();
        assertThat(controlRepository.findMonthlyOverdueCandidates())
                .extracting(ReminderControlProjection::getControlId).doesNotContain(control.getId());
        assertThat(controlRepository.findAllForReminders())
                .extracting(ReminderControlProjection::getControlId).doesNotContain(control.getId());

        // Completed again by the Process Owner, after the deadline: Closed late now, no longer returned
        mockMvc.perform(as(owner, post("/api/workflow/complete-control"), control))
                .andExpect(status().isOk());
        Control completed = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(completed.getPerformanceStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getReopenedAt()).isNull();
        assertThat(historyRepository.findByControlIdOrderByCreatedAtDesc(control.getId()))
                .filteredOn(entry -> "COMPLETED".equals(entry.getToStep())).hasSize(2);
        assertThat(controlsRow(control).isClosedLate()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    /** A Monthly control completed on {@code completedOn}, with the four people, every field filled and a deadline. */
    private Control completedControl(LocalDate deadline, LocalDate completedOn) {
        Control control = new Control();
        control.setControlId("RC-" + UUID.randomUUID().toString().substring(0, 8));
        control.setControlFrequency("Monthly");
        control.setControlStatus("ACTIVE");
        control.setPerformanceStatus("COMPLETED");
        control.setDeadline(deadline);
        control.setCreatedBy(soqm);
        control.setCreatedAt(LocalDateTime.now().minusDays(30));
        control = controlRepository.save(control);
        controls.add(control);

        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseGet(ControlAssignment::new);
        assignment.setControlId(control.getId());
        assignment.setFacilitator(facilitator.getMail());
        assignment.setControlOperator(operator.getMail());
        assignment.setSoqmLead(soqm.getMail());
        assignment.setProcessOwner(owner.getMail());
        assignment.setControlSharedWith(shared.getMail());
        assignment.setControlOperationDate(deadline.minusDays(7));
        assignment.setControlOperationDeadline(deadline);
        assignmentRepository.save(assignment);

        ControlDetails details = detailsRepository.findByControlId(control.getId()).orElseGet(ControlDetails::new);
        details.setControlId(control.getId());
        details.setControlStepsPerformed("Steps performed");
        details.setControlOperatorReview("Operator review");
        details.setSoqmHeadComments("SoQM comments");
        details.setProcessOwnerComments("Process owner comments");
        detailsRepository.save(details);

        WorkflowHistory completion = new WorkflowHistory();
        completion.setControlId(control.getId());
        completion.setActionType(WorkflowActionType.APPROVE);
        completion.setFromStep("PROCESS_OWNER_REVIEW");
        completion.setToStep("COMPLETED");
        completion.setPerformedByEmail(owner.getMail());
        completion.setPerformedByName(owner.getDisplayName());
        completion.setCreatedAt(completedOn.atTime(12, 0));
        historyRepository.save(completion);
        return controlRepository.findById(control.getId()).orElseThrow();
    }

    /** The control's row on the Controls page (overdue, Closed late), as SoQM sees it. */
    @SuppressWarnings("unchecked")
    private ControlResponseDTO controlsRow(Control control) throws Exception {
        List<ControlResponseDTO> rows = (List<ControlResponseDTO>) mockMvc.perform(get("/controls")
                        .sessionAttr("currentUser", soqm)
                        .with(user(soqm.getMail()).roles(String.valueOf(soqm.getAccessLevel()))))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("controls");
        return rows.stream().filter(row -> control.getId().equals(row.getId())).findFirst().orElseThrow();
    }

    private MockHttpServletRequestBuilder as(User actor, MockHttpServletRequestBuilder request, Control control) {
        if (control != null) {
            request.param("controlId", String.valueOf(control.getId()));
        }
        return request.sessionAttr("currentUser", actor)
                .with(user(actor.getMail()).roles(String.valueOf(actor.getAccessLevel())))
                .with(csrf());
    }

    private List<Notification> notices(Control control) {
        return notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId());
    }

    private User saveUser(String role, String mail) {
        User user = new User();
        TestUsers.withRole(user, role);
        user.setMail(mail);
        user.setDisplayName(mail);
        user.setEnabled(true);
        user = userRepository.save(user);
        users.add(user);
        return user;
    }
}
