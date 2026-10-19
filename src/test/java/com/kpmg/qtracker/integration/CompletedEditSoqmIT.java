package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.ControlResponseDTO;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlAttachment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.enums.WorkflowActionType;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlAttachmentRepository;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.NotificationRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.CompletedEdit;
import com.kpmg.qtracker.service.ControlScheduleCalculator;
import com.kpmg.qtracker.service.DeadlineOverdue;
import com.kpmg.qtracker.service.FileStorageService;
import com.kpmg.qtracker.service.SoqmYear;
import com.kpmg.qtracker.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Business decisions of 2026-10-09 and 2026-10-14: SoQM Admin Team changes a completed control in place, without
 * returning it - every field of its tabs (Control Status, SoQM Year, the Control Operation Date and the frequency
 * included), Control Operator's Program, Control Steps Performed and Results, the attachments (a delete only hides the
 * file) and the people, Shared With included - with a reason each time, telling nobody. The deadline and the next
 * date are calculated, never sent; "Closed late" follows the deadline. The workflow status, the completion and
 * reopened_at stay as they were; the Changelog and the audit trail show the author, the values before and after, the
 * reason and "Edited after completion". Everyone else is refused (ReopenCompletedIT, RoleMatrixIT).
 */
@SpringBootTest(properties = "file.upload.dir=target/it-uploads-completed-edit")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompletedEditSoqmIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ControlRepository controlRepository;
    @Autowired
    private ControlAssignmentRepository assignmentRepository;
    @Autowired
    private ControlDetailsRepository detailsRepository;
    @Autowired
    private ControlAttachmentRepository attachmentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private WorkflowHistoryRepository historyRepository;
    @Autowired
    private AdminAuditLogRepository auditRepository;
    @Autowired
    private FileStorageService fileStorageService;

    private final List<User> users = new ArrayList<>();
    private final List<Control> controls = new ArrayList<>();
    private User facilitator;
    private User operator;
    private User soqm;
    private User owner;
    private User newOwner;
    private User shared;
    private User newShared;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        facilitator = saveUser("FACILITATOR", "ce-fac-" + s + "@example.test");
        operator = saveUser("CONTROL_OPERATOR", "ce-op-" + s + "@example.test");
        soqm = saveUser("SOQM_TEAM", "ce-soqm-" + s + "@example.test");
        owner = saveUser("PROCESS_OWNER", "ce-po-" + s + "@example.test");
        newOwner = saveUser("PROCESS_OWNER", "ce-po2-" + s + "@example.test");
        shared = saveUser("FACILITATOR", "ce-shared-" + s + "@example.test");
        newShared = saveUser("FACILITATOR", "ce-shared2-" + s + "@example.test");
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

    @Test
    void soqmChangesEveryTabInPlace_withAReason_andTheCompletionStaysAsItWas() throws Exception {
        // Completed the day after its deadline: "Closed late", never Overdue
        Control control = completedControl(today.minusDays(2), today.minusDays(1));
        ControlAssignment scheduleBefore = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        com.kpmg.qtracker.service.ComponentControlsList.Row rowBefore = controlsRow(control);
        assertThat(rowBefore.closedLate()).isTrue();
        assertThat(rowBefore.overdue()).isFalse();

        ok(as(soqm, put("/api/controls/{id}", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlDescription\":\"Corrected description\",\"prp\":\"PRP 2\","
                        + "\"controlFrequency\":\"Monthly\",\"soqmYear\":\"" + control.getSoqmYear() + "\","
                        + "\"controlStatus\":\"ACTIVE\",\"editReason\":\"Description was out of date\"}"));
        ok(as(soqm, post("/api/control-details"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"controlStepsPerformed\":\"Corrected steps\","
                        + "\"controlOperatorReview\":\"Final program\",\"soqmHeadComments\":\"SoQM comments 2\","
                        + "\"processOwnerComments\":\"Owner comments 2\",\"processActivities\":\"Activities\","
                        + "\"editReason\":\"Steps were mistyped\"}"));
        // The page sends the people and the stored Operation Date; any deadline or next date sent is ignored
        ok(as(soqm, post("/api/control-assignment"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId()
                        + ",\"processOwner\":[\"" + newOwner.getMail() + "\"]"
                        + ",\"controlSharedWith\":[\"" + shared.getMail() + "\",\"" + newShared.getMail() + "\"]"
                        + ",\"controlOperationDate\":\"" + scheduleBefore.getControlOperationDate() + "\""
                        + ",\"controlOperationDeadline\":\"" + today.plusYears(1) + "\""
                        + ",\"nextControlOperationDate\":\"" + today.plusYears(2) + "\""
                        + ",\"editReason\":\"The Process Owner changed\"}"));

        // The changes are there
        Control saved = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(saved.getControlDescription()).isEqualTo("Corrected description");
        assertThat(saved.getPrp()).isEqualTo("PRP 2");
        ControlDetails details = detailsRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(details.getControlStepsPerformed()).isEqualTo("Corrected steps");
        assertThat(details.getControlOperatorReview()).isEqualTo("Final program");
        assertThat(details.getSoqmHeadComments()).isEqualTo("SoQM comments 2");
        assertThat(details.getProcessOwnerComments()).isEqualTo("Owner comments 2");
        assertThat(details.getProcessActivities()).isEqualTo("Activities");
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(assignment.getProcessOwner()).isEqualTo(newOwner.getMail());
        assertThat(assignment.getControlSharedWith()).contains(shared.getMail()).contains(newShared.getMail());

        // ... and nothing of the completion moved
        assertThat(saved.getPerformanceStatus()).isEqualTo("COMPLETED");
        assertThat(saved.getReopenedAt()).isNull();
        assertThat(saved.getDeadline()).isEqualTo(today.minusDays(2));
        assertThat(saved.getControlFrequency()).isEqualTo("Monthly");
        assertThat(saved.getSoqmYear()).isEqualTo(control.getSoqmYear());
        assertThat(saved.getControlStatus()).isEqualTo("ACTIVE");
        assertThat(assignment.getControlOperationDate()).isEqualTo(scheduleBefore.getControlOperationDate());
        assertThat(assignment.getControlOperationDeadline()).isEqualTo(scheduleBefore.getControlOperationDeadline());
        // The next date drives the auto-creation of the next cycle
        assertThat(assignment.getNextControlOperationDate()).isEqualTo(scheduleBefore.getNextControlOperationDate());
        List<WorkflowHistory> history = historyRepository.findByControlIdOrderByCreatedAtDesc(control.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getToStep()).isEqualTo("COMPLETED");
        assertThat(history.get(0).getCreatedAt().toLocalDate()).isEqualTo(today.minusDays(1));
        com.kpmg.qtracker.service.ComponentControlsList.Row rowAfter = controlsRow(control);
        assertThat(rowAfter.closedLate()).isTrue();
        assertThat(rowAfter.overdue()).isFalse();
        assertThat(rowAfter.status()).isEqualTo("COMPLETED");
        // Nobody is told of the change, not even the person newly in Shared With (decision of 2026-10-14)
        assertThat(notices(control)).isEmpty();

        // The Changelog: author, the values before and after, the reason and the mark, for every save
        JsonNode changelog = json(as(soqm, get("/api/controls/{id}/changelog", control.getId())));
        List<JsonNode> marked = new ArrayList<>();
        changelog.forEach(entry -> {
            if (entry.path("editedAfterCompletion").asBoolean()) {
                marked.add(entry);
            }
        });
        assertThat(marked).extracting(entry -> entry.path("reason").asText()).containsExactlyInAnyOrder(
                "Description was out of date", "Steps were mistyped", "The Process Owner changed");
        assertThat(marked).allMatch(entry -> soqm.getMail().equals(entry.path("actorEmail").asText()));
        assertThat(marked).allMatch(entry -> fieldNames(entry).stream().noneMatch(CompletedEdit.REASON_FIELD::equals));
        JsonNode steps = marked.stream().filter(entry -> "Steps were mistyped".equals(entry.path("reason").asText()))
                .findFirst().orElseThrow();
        JsonNode stepsChange = change(steps, "Control Steps Performed and Results");
        assertThat(stepsChange.path("oldValue").asText()).isEqualTo("Steps performed");
        assertThat(stepsChange.path("newValue").asText()).isEqualTo("Corrected steps");
        JsonNode people = marked.stream().filter(entry -> "The Process Owner changed".equals(entry.path("reason").asText()))
                .findFirst().orElseThrow();
        assertThat(fieldNames(people)).contains("Process Owner", "Control Shared With")
                .doesNotContain("Control Operation Date", "Control Operation Deadline", "Next Control Operation Date");

        // The audit trail: the same entries, marked, the reason as a field
        List<AdminAuditLog> audit = auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId());
        assertThat(audit).hasSize(3).allMatch(entry -> CompletedEdit.isMarked(entry.getActionDescription())
                && soqm.getMail().equals(entry.getAdminEmail())
                && entry.getChangedFields().contains("\"Reason\"")
                && entry.getPreviousValues() != null && entry.getNewValues().contains("\"Reason\":"));
    }

    /**
     * Business decision of 2026-10-14: another Control Operation Date or frequency recalculates the deadline and the
     * next date by the usual rules, and "Closed late" with them; the control stays completed, no control is created,
     * nobody is notified. A deadline or next date sent is ignored.
     */
    @Test
    void theOperationDateAndTheFrequency_change_theScheduleIsRecalculated_closedLateFollows_nothingElseHappens()
            throws Exception {
        // Completed yesterday, three days before its deadline: not Closed late
        Control control = completedControl(today.plusDays(3), today.minusDays(1));
        assertThat(controlsRow(control).closedLate()).isFalse();
        long controlsBefore = controlRepository.count();
        LocalDate earlier = today.minusDays(40);
        LocalDate earlierDeadline = ControlScheduleCalculator.DEADLINE.from(earlier);

        // Without a reason: refused, nothing changes
        expect(as(soqm, post("/api/control-assignment"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"controlOperationDate\":\"" + earlier + "\"}"),
                400, CompletedEdit.REASON_REQUIRED);
        expect(as(soqm, put("/api/controls/{id}", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlFrequency\":\"Quarterly\"}"), 400, CompletedEdit.REASON_REQUIRED);
        assertThat(assignmentRepository.findByControlId(control.getId()).orElseThrow().getControlOperationDate())
                .isEqualTo(today.plusDays(3).minusDays(14));

        // An earlier Control Operation Date: its deadline is now before the completion, so Closed late
        ok(as(soqm, post("/api/control-assignment"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"controlOperationDate\":\"" + earlier + "\""
                        + ",\"controlOperationDeadline\":\"" + today.plusYears(1) + "\""
                        + ",\"nextControlOperationDate\":\"" + today.plusYears(2) + "\""
                        + ",\"editReason\":\"Operated earlier\"}"));
        ControlAssignment afterDate = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(afterDate.getControlOperationDate()).isEqualTo(earlier);
        assertThat(afterDate.getControlOperationDeadline()).isEqualTo(earlierDeadline);
        assertThat(afterDate.getNextControlOperationDate()).isEqualTo(earlier.plusMonths(1));
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getDeadline()).isEqualTo(earlierDeadline);
        com.kpmg.qtracker.service.ComponentControlsList.Row late = controlsRow(control);
        assertThat(late.closedLate()).isTrue();
        assertThat(late.overdue()).isFalse();
        assertThat(late.status()).isEqualTo("COMPLETED");

        // Quarterly: the next date moves three months on, the deadline stays (the same rule for every frequency)
        ok(as(soqm, put("/api/controls/{id}", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlFrequency\":\"Quarterly\",\"editReason\":\"Operated quarterly\"}"));
        ControlAssignment afterFrequency = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(afterFrequency.getControlOperationDeadline()).isEqualTo(earlierDeadline);
        assertThat(afterFrequency.getNextControlOperationDate()).isEqualTo(earlier.plusMonths(3));
        assertThat(AccessPolicy.frequencyOffersCreateControl("Quarterly")).isTrue();

        // Ad-hoc: no next date, and no Create Control button any more
        ok(as(soqm, put("/api/controls/{id}", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlFrequency\":\"Ad-hoc\",\"editReason\":\"One-off control\"}"));
        Control adHoc = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(assignmentRepository.findByControlId(control.getId()).orElseThrow().getNextControlOperationDate())
                .isNull();
        assertThat(AccessPolicy.frequencyOffersCreateControl(adHoc.getControlFrequency())).isFalse();

        // A later date again: the deadline after the completion, no longer Closed late
        ok(as(soqm, post("/api/control-assignment"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"controlOperationDate\":\"" + today.minusDays(2) + "\""
                        + ",\"editReason\":\"Back to the real day\"}"));
        assertThat(controlsRow(control).closedLate()).isFalse();

        // Still completed, the completion untouched; no control made, nobody told
        Control saved = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(saved.getPerformanceStatus()).isEqualTo("COMPLETED");
        assertThat(saved.getReopenedAt()).isNull();
        assertThat(historyRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).hasSize(1);
        assertThat(controlRepository.count()).isEqualTo(controlsBefore);
        assertThat(notices(control)).isEmpty();

        // The audit: every save marked, with the reason and the schedule before and after
        List<AdminAuditLog> audit = auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId());
        assertThat(audit).hasSize(4).allMatch(entry -> CompletedEdit.isMarked(entry.getActionDescription()));
        AdminAuditLog dateEntry = audit.stream().filter(entry -> entry.getNewValues().contains("Operated earlier"))
                .findFirst().orElseThrow();
        assertThat(dateEntry.getChangedFields()).contains("Control Operation Date", "Control Operation Deadline",
                "Next Control Operation Date");
        assertThat(dateEntry.getPreviousValues()).contains(today.plusDays(3).minusDays(14).toString(),
                today.plusDays(3).toString());
        assertThat(dateEntry.getNewValues()).contains(earlier.toString(), earlierDeadline.toString(),
                earlier.plusMonths(1).toString()).doesNotContain(today.plusYears(1).toString());
        AdminAuditLog frequencyEntry = audit.stream().filter(entry -> entry.getNewValues().contains("Operated quarterly"))
                .findFirst().orElseThrow();
        assertThat(frequencyEntry.getChangedFields()).contains("control_frequency", "Next Control Operation Date")
                .doesNotContain("\"deadline\"");
        assertThat(frequencyEntry.getPreviousValues()).contains("Monthly", earlier.plusMonths(1).toString());
        assertThat(frequencyEntry.getNewValues()).contains("Quarterly", earlier.plusMonths(3).toString());
        // The Changelog shows them marked, with the reasons
        JsonNode changelog = json(as(soqm, get("/api/controls/{id}/changelog", control.getId())));
        List<String> reasons = new ArrayList<>();
        changelog.forEach(entry -> {
            if (entry.path("editedAfterCompletion").asBoolean()) {
                reasons.add(entry.path("reason").asText());
            }
        });
        assertThat(reasons).containsExactlyInAnyOrder("Operated earlier", "Operated quarterly", "One-off control",
                "Back to the real day");

        // Rename ID works as before
        ok(as(soqm, post("/api/controls/{id}/rename-id", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newControlId\":\"" + control.getControlId() + "-R\"}"));
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getControlId())
                .isEqualTo(control.getControlId() + "-R");
    }

    /** Everyone but SoQM Admin Team: 403 for every field of a completed control, the schedule included. */
    @Test
    void everyoneElse_isRefusedEveryField_403() throws Exception {
        Control control = completedControl(today.plusDays(3), today.minusDays(1));
        User readOnly = userRepository.save(TestUsers.user("ce-ro2-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test", com.kpmg.qtracker.enums.AccessLevel.READ_ONLY, com.kpmg.qtracker.enums.AccessScope.ALL, false));
        User allEdit = userRepository.save(TestUsers.user("ce-all-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test", com.kpmg.qtracker.enums.AccessLevel.PARTICIPANT, com.kpmg.qtracker.enums.AccessScope.ALL, false));
        users.add(readOnly);
        users.add(allEdit);
        List<MockHttpServletRequestBuilder> writes = List.of(
                put("/api/controls/{id}", control.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlFrequency\":\"Quarterly\",\"editReason\":\"Why\"}"),
                put("/api/controls/{id}", control.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlDescription\":\"Changed\",\"editReason\":\"Why\"}"),
                post("/api/control-assignment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + control.getId() + ",\"controlOperationDate\":\""
                                + today.minusDays(20) + "\",\"editReason\":\"Why\"}"),
                post("/api/control-assignment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + control.getId() + ",\"controlSharedWith\":[\""
                                + newShared.getMail() + "\"],\"editReason\":\"Why\"}"),
                post("/api/control-details").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + control.getId() + ",\"controlStepsPerformed\":\"Changed\","
                                + "\"editReason\":\"Why\"}"),
                multipart("/api/attachments/upload/{id}", control.getId())
                        .file(new MockMultipartFile("attachmentDetails", "late.pdf", "application/pdf",
                                "%PDF-1.4 late".getBytes(StandardCharsets.UTF_8))).param("editReason", "Why"));
        for (User other : List.of(facilitator, operator, owner, shared, readOnly, allEdit)) {
            for (MockHttpServletRequestBuilder write : writes) {
                MvcResult result = perform(as(other, write));
                assertThat(result.getResponse().getStatus()).as(other.getMail() + " " + result.getRequest().getRequestURI()
                        + " " + result.getResponse().getContentAsString()).isEqualTo(403);
            }
        }
        ControlAssignment after = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(after.getControlOperationDate()).isEqualTo(today.plusDays(3).minusDays(14));
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getControlFrequency()).isEqualTo("Monthly");
        assertThat(auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).isEmpty();
    }

    /**
     * Business decision of 2026-10-13: SoQM Admin Team changes the Control Status and the SoQM Year of a completed
     * control with a reason, values from the lists only; the workflow status, the schedule and the cycles stay.
     */
    @Test
    void theControlStatusAndTheSoqmYear_changeWithAReason_auditedBeforeAndAfter_theScheduleStays() throws Exception {
        Control control = completedControl(today.plusDays(3), today.minusDays(1));
        ControlAssignment before = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        String otherYear = SoqmYear.options(today).get(0);
        long controlsBefore = controlRepository.count();

        // Without a reason, a value not in a list: refused, nothing changes
        expect(as(soqm, put("/api/controls/{id}", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlStatus\":\"SUPERSEDED\"}"), 400, CompletedEdit.REASON_REQUIRED);
        assertThat(perform(as(soqm, put("/api/controls/{id}", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlStatus\":\"ARCHIVED\",\"editReason\":\"Why\"}")).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(perform(as(soqm, put("/api/controls/{id}", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"soqmYear\":\"1 OCT 2001 - 30 SEP 2002\",\"editReason\":\"Why\"}")).getResponse().getStatus())
                .isEqualTo(400);
        // Everyone else: 403, as on every completed control
        for (User other : List.of(owner, operator, facilitator, shared)) {
            assertThat(perform(as(other, put("/api/controls/{id}", control.getId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"controlStatus\":\"DELETED\",\"editReason\":\"Why\"}")).getResponse().getStatus())
                    .as(other.getMail()).isEqualTo(403);
        }
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getControlStatus()).isEqualTo("ACTIVE");

        ok(as(soqm, put("/api/controls/{id}", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlStatus\":\"SUPERSEDED\",\"soqmYear\":\"" + otherYear + "\","
                        + "\"editReason\":\"Replaced by the FY28 control\"}"));

        Control saved = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(saved.getControlStatus()).isEqualTo("SUPERSEDED");
        assertThat(saved.getSoqmYear()).isEqualTo(otherYear);
        assertThat(saved.getPerformanceStatus()).isEqualTo("COMPLETED");
        assertThat(saved.getDeadline()).isEqualTo(control.getDeadline());
        ControlAssignment after = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(after.getControlOperationDate()).isEqualTo(before.getControlOperationDate());
        assertThat(after.getControlOperationDeadline()).isEqualTo(before.getControlOperationDeadline());
        assertThat(after.getNextControlOperationDate()).isEqualTo(before.getNextControlOperationDate());
        assertThat(controlRepository.count()).isEqualTo(controlsBefore);

        // The audit trail: who, the values before and after, the reason, marked
        AdminAuditLog entry = auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId()).get(0);
        assertThat(entry.getActionDescription()).isEqualTo("Edit Control - Edited after completion");
        assertThat(entry.getPreviousValues()).contains("\"control_status\":\"ACTIVE\"", control.getSoqmYear());
        assertThat(entry.getNewValues()).contains("\"control_status\":\"SUPERSEDED\"", otherYear,
                "Replaced by the FY28 control");
        // The Changelog shows it marked, with the reason
        JsonNode changelog = json(as(soqm, get("/api/controls/{id}/changelog", control.getId())));
        JsonNode edit = null;
        for (JsonNode e : changelog) {
            if (e.path("editedAfterCompletion").asBoolean()) {
                edit = e;
                break;
            }
        }
        assertThat(edit).as(changelog.toString()).isNotNull();
        assertThat(edit.toString()).contains("Replaced by the FY28 control", "SUPERSEDED");
    }

    @Test
    void withoutAReason_everyChangeIsRefusedWith400_andNothingChanges() throws Exception {
        Control control = completedControl(today.plusDays(3), today.minusDays(1));
        String fileName = control.getAttachmentDetailsPath();
        String json400 = CompletedEdit.REASON_REQUIRED;

        expect(as(soqm, put("/api/controls/{id}", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlDescription\":\"Changed\",\"editReason\":\"   \"}"), 400, json400);
        expect(as(soqm, post("/api/control-details"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"controlStepsPerformed\":\"Changed\"}"), 400, json400);
        expect(as(soqm, post("/api/control-assignment"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"processOwner\":[\"" + newOwner.getMail() + "\"]}"),
                400, json400);
        expect(as(soqm, multipart("/api/attachments/upload/{id}", control.getId())
                .file(new MockMultipartFile("attachmentDetails", "late.pdf", "application/pdf",
                        "%PDF-1.4 late".getBytes(StandardCharsets.UTF_8)))), 400, json400);
        expect(as(soqm, delete("/api/attachments/delete/{id}", control.getId())
                .param("filename", fileName).param("type", "details")), 400, json400);
        expect(as(soqm, put("/api/controls/{id}", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlDescription\":\"Changed\",\"editReason\":\"" + "x".repeat(501) + "\"}"),
                400, CompletedEdit.REASON_TOO_LONG);

        Control saved = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(saved.getControlDescription()).isNull();
        assertThat(saved.getAttachmentDetailsPath()).isEqualTo(fileName);
        assertThat(detailsRepository.findByControlId(control.getId()).orElseThrow().getControlStepsPerformed())
                .isEqualTo("Steps performed");
        assertThat(assignmentRepository.findByControlId(control.getId()).orElseThrow().getProcessOwner())
                .isEqualTo(owner.getMail());
        assertThat(auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).isEmpty();

        // A save that changes nothing needs no reason
        ok(as(soqm, post("/api/control-details"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"controlStepsPerformed\":\"Steps performed\"}"));
        assertThat(auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).isEmpty();
    }

    @Test
    void anAttachment_isAdded_andHidden_whichKeepsItOnDisk() throws Exception {
        Control control = completedControl(today.plusDays(3), today.minusDays(1));
        String folder = FileStorageService.controlFolder(control.getControlId(), control.getId());

        ok(as(soqm, multipart("/api/attachments/upload/{id}", control.getId())
                .file(new MockMultipartFile("attachmentDetails", "late-evidence.pdf", "application/pdf",
                        "%PDF-1.4 late".getBytes(StandardCharsets.UTF_8)))
                .param("editReason", "Evidence arrived late")));
        Control withFile = controlRepository.findById(control.getId()).orElseThrow();
        String added = withFile.getAttachmentDetailsPath().split(";")[1].trim();
        assertThat(attachmentRepository.findByControlIdAndTabAndFileName(control.getId(), ControlAttachment.TAB_DETAILS, added))
                .get().extracting(ControlAttachment::getUploadedStage).isEqualTo("COMPLETED");
        // Everyone who reads the control downloads it
        ok(as(owner, get("/api/attachments/download/{name}", added).param("controlId", String.valueOf(control.getId()))));

        mockMvcExpectJson(as(soqm, delete("/api/attachments/delete/{id}", control.getId())
                .param("filename", added).param("type", "details").param("editReason", "Wrong document")), "File hidden");

        Control hidden = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(hidden.getAttachmentDetailsPath()).doesNotContain(added);
        assertThat(attachmentRepository.findByControlIdAndTabAndFileName(control.getId(), ControlAttachment.TAB_DETAILS, added)).isEmpty();
        assertThat(perform(as(owner, get("/api/attachments/download/{name}", added)
                .param("controlId", String.valueOf(control.getId())))).getResponse().getStatus()).isEqualTo(404);
        // Hidden, not deleted: still on disk
        assertThat(fileStorageService.downloadFile(added, folder)).isNotEmpty();
        assertThat(hidden.getPerformanceStatus()).isEqualTo("COMPLETED");

        List<AdminAuditLog> audit = auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId());
        assertThat(audit).extracting(AdminAuditLog::getActionType).containsExactlyInAnyOrder("ATTACHMENT_ADDED", "ATTACHMENT_HIDDEN");
        assertThat(audit).allMatch(entry -> CompletedEdit.isMarked(entry.getActionDescription()));
        JsonNode changelog = json(as(soqm, get("/api/controls/{id}/changelog", control.getId())));
        List<String> events = new ArrayList<>();
        changelog.forEach(entry -> {
            if (entry.path("editedAfterCompletion").asBoolean()) {
                events.add(entry.path("eventName").asText() + ": " + entry.path("reason").asText());
            }
        });
        assertThat(events).containsExactlyInAnyOrder("Attachment Added (Details): Evidence arrived late",
                "Attachment Hidden (Details): Wrong document");
    }

    @Test
    void theCompletedExcelAndTheLists_showTheValuesAsChanged() throws Exception {
        Control control = completedControl(today.plusDays(3), today.minusDays(1));
        ok(as(soqm, put("/api/controls/{id}", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlDescription\":\"Corrected description\",\"editReason\":\"Out of date\"}"));
        ok(as(soqm, post("/api/control-details"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"controlStepsPerformed\":\"Corrected steps\","
                        + "\"controlOperatorReview\":\"Final program\",\"editReason\":\"Mistyped\"}"));
        ok(as(soqm, post("/api/control-assignment"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"processOwner\":[\"" + newOwner.getMail() + "\"],"
                        + "\"editReason\":\"The Process Owner changed\"}"));

        // The Excel of the completed control: SoQM Admin Team only (decision of 2026-10-14), not a person it is shared with
        assertThat(perform(as(shared, get("/api/controls/{id}/export/completed", control.getId())))
                .getResponse().getStatus()).isEqualTo(403);
        for (User reader : List.of(soqm)) {
            java.util.Map<String, String> rows = excelRows(control, reader);
            assertThat(rows).as(reader.getMail())
                    .containsEntry("Control Description", "Corrected description")
                    .containsEntry(com.kpmg.qtracker.service.ControlStepsFields.STEPS_LABEL, "Corrected steps")
                    .containsEntry(com.kpmg.qtracker.service.ControlStepsFields.OPERATOR_PROGRAM_LABEL, "Final program")
                    // this person's name is their e-mail, so it is shown once
                    .containsEntry("Process Owner", newOwner.getMail())
                    .containsEntry("Performance Status", "Completed");
        }
        // The All components list (the description is not one of its columns: the Excel above shows it)
        com.kpmg.qtracker.service.ComponentControlsList.Row row = controlsRow(control);
        assertThat(row.owners()).isEqualTo(newOwner.getDisplayName());
        assertThat(row.status()).isEqualTo("COMPLETED");
    }

    @Test
    void viewControl_soqmTeamGetsTheCompletedEditButton_everyoneElseTheLockedBanner() throws Exception {
        Control control = completedControl(today.plusDays(3), today.minusDays(1));
        User readOnly = userRepository.save(TestUsers.user("ce-ro-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test", com.kpmg.qtracker.enums.AccessLevel.READ_ONLY, com.kpmg.qtracker.enums.AccessScope.ALL, false));
        users.add(readOnly);

        String soqmPage = page(soqm, control);
        assertThat(soqmPage).contains("id=\"completedEditBanner\"", "Completed. Changes require a reason.",
                "Edit (completed control)", "id=\"completedEditReasonModal\"", "id=\"completedEditReason\"",
                "for=\"completedEditReason\"", "id=\"controlFrequencyCompletedHint\"", "id=\"soqmYearCompletedHint\"",
                "id=\"scheduleCompletedHint\"", "aria-describedby=\"controlOperationDateFeedback scheduleCompletedHint\"",
                "aria-describedby=\"controlFrequencyCompletedHint\"",
                "id=\"completedEdit\" value=\"true\"", "No notifications are sent.", "To return to the workflow, use")
                .doesNotContain("id=\"accessBanner\"", "data-completed-fixed", "id=\"scheduleFixedHint\"",
                        "id=\"controlFrequencyFixedHint\"");

        for (User reader : List.of(readOnly, owner, shared)) {
            String page = page(reader, control);
            assertThat(page).as(reader.getMail())
                    .contains("id=\"accessBanner\"", "data-notice=\"COMPLETED\"", "This control is completed and locked",
                            "Only SoQM Admin Team can change it.")
                    .doesNotContain("id=\"editBtn\"", "id=\"completedEditBanner\"", "id=\"completedEditReasonModal\"",
                            "id=\"workflow-buttons-container\"", "id=\"scheduleCompletedHint\"");
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The rows of the completed control's Excel (label, value) as the user downloads it. */
    private java.util.Map<String, String> excelRows(Control control, User user) throws Exception {
        MvcResult result = perform(as(user, get("/api/controls/{id}/export/completed", control.getId())));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        java.util.Map<String, String> rows = new java.util.LinkedHashMap<>();
        try (org.apache.poi.ss.usermodel.Workbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            for (org.apache.poi.ss.usermodel.Row row : workbook.getSheetAt(0)) {
                rows.put(row.getCell(0).getStringCellValue(),
                        row.getCell(1) != null ? row.getCell(1).getStringCellValue() : null);
            }
        }
        return rows;
    }

    private String page(User user, Control control) throws Exception {
        MvcResult result = perform(as(user, get("/view-control/{id}", control.getId())));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    /** A Monthly control completed on {@code completedOn}, with the four people, every field filled, one file. */
    private Control completedControl(LocalDate deadline, LocalDate completedOn) throws Exception {
        Control control = new Control();
        control.setControlId("CE-" + UUID.randomUUID().toString().substring(0, 8));
        control.setControlFrequency("Monthly");
        control.setControlStatus("ACTIVE");
        control.setSoqmYear(SoqmYear.current(today));
        control.setPerformanceStatus("COMPLETED");
        control.setDeadline(deadline);
        control.setCreatedBy(soqm);
        control.setCreatedAt(LocalDateTime.now().minusDays(30));
        control.setAttachmentDetailsPath(fileStorageService.saveFile(new MockMultipartFile("file", "evidence.pdf",
                "application/pdf", "%PDF-1.4 evidence".getBytes(StandardCharsets.UTF_8)), control.getControlId()));
        control = controlRepository.save(control);
        controls.add(control);

        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseGet(ControlAssignment::new);
        assignment.setControlId(control.getId());
        assignment.setFacilitator(facilitator.getMail());
        assignment.setControlOperator(operator.getMail());
        assignment.setSoqmLead(soqm.getMail());
        assignment.setProcessOwner(owner.getMail());
        assignment.setControlSharedWith(shared.getMail());
        assignment.setControlOperationDate(deadline.minusDays(14));
        assignment.setControlOperationDeadline(deadline);
        assignment.setNextControlOperationDate(deadline.minusDays(14).plusMonths(1));
        assignmentRepository.save(assignment);

        ControlDetails details = detailsRepository.findByControlId(control.getId()).orElseGet(ControlDetails::new);
        details.setControlId(control.getId());
        details.setControlStepsPerformed("Steps performed");
        details.setControlOperatorReview("Operator program");
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

    /** The control's row in the All components list (Overdue, Closed late, status), as SoQM sees it. */
    private com.kpmg.qtracker.service.ComponentControlsList.Row controlsRow(Control control) throws Exception {
        return ListPages.rows(mockMvc, request -> as(soqm, request), "/component/ALL?q=" + control.getControlId())
                .stream().filter(row -> control.getId().equals(row.id())).findFirst().orElseThrow();
    }

    private MockHttpServletRequestBuilder as(User actor, MockHttpServletRequestBuilder request) {
        return request.sessionAttr("currentUser", actor)
                .with(user(actor.getMail()).roles(String.valueOf(actor.getAccessLevel())))
                .with(csrf());
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private void ok(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = perform(request);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
    }

    /** The status and the refusal: "VALIDATION_ERROR: <message>" as text, or the JSON message of the attachments. */
    private void expect(MockHttpServletRequestBuilder request, int status, String message) throws Exception {
        MvcResult result = perform(request);
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(status);
        if (body.startsWith("{")) {
            assertThat(objectMapper.readTree(body).path("message").asText()).isEqualTo(message);
        } else {
            assertThat(body).isEqualTo("VALIDATION_ERROR: " + message);
        }
    }

    private void mockMvcExpectJson(MockHttpServletRequestBuilder request, String message) throws Exception {
        MvcResult result = perform(request);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("message").asText())
                .isEqualTo(message);
    }

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = perform(request);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> fieldNames(JsonNode entry) {
        List<String> names = new ArrayList<>();
        entry.path("fieldChanges").forEach(change -> names.add(change.path("field").asText()));
        return names;
    }

    private static JsonNode change(JsonNode entry, String field) {
        for (JsonNode change : entry.path("fieldChanges")) {
            if (field.equals(change.path("field").asText())) {
                return change;
            }
        }
        throw new AssertionError("No change of " + field + " in " + entry);
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
