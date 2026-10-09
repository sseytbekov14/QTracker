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
 * Business decision of 2026-10-09: SoQM Team changes a completed control in place, without returning it - every
 * field of its tabs, Control Operator's Program, Control Steps Performed and Results, the attachments (a delete
 * only hides the file) and the people, Shared With included - with a reason each time. The status, the
 * completion, reopened_at, "Closed late", Overdue, the schedule (and with it the auto-creation of the next cycle),
 * the SoQM Year and the Control Status stay as they were; the Changelog and the audit trail show the author, the
 * values before and after, the reason and "Edited after completion". Everyone else is refused (ReopenCompletedIT,
 * RoleMatrixIT).
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
        ControlResponseDTO rowBefore = controlsRow(control);
        assertThat(rowBefore.isClosedLate()).isTrue();
        assertThat(rowBefore.isOverdue()).isFalse();

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
        ok(as(soqm, post("/api/control-documents"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"soqmDevelopmentMaterials\":\"Yes\","
                        + "\"editReason\":\"Materials were found\"}"));
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
        JsonNode documents = json(as(soqm, get("/api/control-documents").param("controlId", String.valueOf(control.getId()))));
        assertThat(documents.path("soqmDevelopmentMaterials").asText()).isEqualTo("Yes");
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
        ControlResponseDTO rowAfter = controlsRow(control);
        assertThat(rowAfter.isClosedLate()).isTrue();
        assertThat(rowAfter.isOverdue()).isFalse();
        assertThat(rowAfter.getPerformanceStatus()).isEqualTo("COMPLETED");
        // Nobody is told of the change (TODO: BUSINESS CONFIRMATION); a person newly in Shared With hears of
        // the share, as on any control
        assertThat(notices(control)).extracting(Notification::getType).containsOnly("CONTROL_SHARED");
        assertThat(notices(control)).extracting(Notification::getUserId).containsOnly(newShared.getId());

        // The Changelog: author, the values before and after, the reason and the mark, for every save
        JsonNode changelog = json(as(soqm, get("/api/controls/{id}/changelog", control.getId())));
        List<JsonNode> marked = new ArrayList<>();
        changelog.forEach(entry -> {
            if (entry.path("editedAfterCompletion").asBoolean()) {
                marked.add(entry);
            }
        });
        assertThat(marked).extracting(entry -> entry.path("reason").asText()).containsExactlyInAnyOrder(
                "Description was out of date", "Steps were mistyped", "Materials were found", "The Process Owner changed");
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
        assertThat(audit).hasSize(4).allMatch(entry -> CompletedEdit.isMarked(entry.getActionDescription())
                && soqm.getMail().equals(entry.getAdminEmail())
                && entry.getChangedFields().contains("\"Reason\"")
                && entry.getPreviousValues() != null && entry.getNewValues().contains("\"Reason\":"));
    }

    @Test
    void theScheduleSoqmYearAndControlStatus_areRefused_orIgnoredWhenSentUnchanged() throws Exception {
        Control control = completedControl(today.plusDays(3), today.minusDays(1));
        ControlAssignment before = assignmentRepository.findByControlId(control.getId()).orElseThrow();

        expect(as(soqm, post("/api/control-assignment"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"controlOperationDate\":\""
                        + before.getControlOperationDate().plusDays(1) + "\",\"editReason\":\"Wrong date\"}"),
                403, AccessPolicy.completedFixedMessage("Control Operation Date"));
        expect(as(soqm, put("/api/controls/{id}", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlFrequency\":\"Quarterly\",\"editReason\":\"Wrong frequency\"}"),
                403, AccessPolicy.completedFixedMessage("Control Frequency"));
        expect(as(soqm, put("/api/controls/{id}", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"soqmYear\":\"" + SoqmYear.current(today.plusYears(1)) + "\",\"editReason\":\"Wrong year\"}"),
                403, AccessPolicy.completedFixedMessage("SoQM Year"));
        expect(as(soqm, put("/api/controls/{id}", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlStatus\":\"SUPERSEDED\",\"editReason\":\"Replaced\"}"),
                403, AccessPolicy.completedFixedMessage("Control Status"));

        // Only the deadline and the next date sent: nothing to save, nothing changes
        ok(as(soqm, post("/api/control-assignment"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId()
                        + ",\"controlOperationDeadline\":\"" + today.plusYears(1) + "\""
                        + ",\"nextControlOperationDate\":\"" + today.plusYears(2) + "\"}"));

        Control saved = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(saved.getControlFrequency()).isEqualTo("Monthly");
        assertThat(saved.getSoqmYear()).isEqualTo(control.getSoqmYear());
        assertThat(saved.getControlStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getDeadline()).isEqualTo(control.getDeadline());
        ControlAssignment after = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(after.getControlOperationDate()).isEqualTo(before.getControlOperationDate());
        assertThat(after.getControlOperationDeadline()).isEqualTo(before.getControlOperationDeadline());
        assertThat(after.getNextControlOperationDate()).isEqualTo(before.getNextControlOperationDate());
        assertThat(auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).isEmpty();
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
        expect(as(soqm, post("/api/control-documents"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + control.getId() + ",\"soqmDevelopmentMaterials\":\"Yes\"}"), 400, json400);
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
    void viewControl_soqmTeamGetsTheCompletedEditButton_everyoneElseTheLockedBanner() throws Exception {
        Control control = completedControl(today.plusDays(3), today.minusDays(1));
        User readOnly = userRepository.save(TestUsers.user("ce-ro-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test", com.kpmg.qtracker.enums.AccessLevel.READ_ONLY, com.kpmg.qtracker.enums.AccessScope.ALL, false));
        users.add(readOnly);

        String soqmPage = page(soqm, control);
        assertThat(soqmPage).contains("id=\"completedEditBanner\"", "Your changes are recorded with a reason",
                "Edit (completed control)", "id=\"completedEditReasonModal\"", "id=\"completedEditReason\"",
                "for=\"completedEditReason\"", "id=\"controlFrequencyFixedHint\"", "id=\"soqmYearFixedHint\"",
                "id=\"controlStatusFixedHint\"", "id=\"scheduleFixedHint\"", "data-completed-fixed=\"scheduleFixedHint\"",
                "id=\"completedEdit\" value=\"true\"")
                .doesNotContain("id=\"accessBanner\"");

        for (User reader : List.of(readOnly, owner, shared)) {
            String page = page(reader, control);
            assertThat(page).as(reader.getMail())
                    .contains("id=\"accessBanner\"", "data-notice=\"COMPLETED\"", "This control is completed and locked",
                            "Only SoQM Team can change it.")
                    .doesNotContain("id=\"editBtn\"", "id=\"completedEditBanner\"", "id=\"completedEditReasonModal\"",
                            "id=\"workflow-buttons-container\"", "id=\"scheduleFixedHint\"");
        }
    }

    // ------------------------------------------------------------------ helpers

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

    /** The control's row on the Controls page (Overdue, Closed late, status), as SoQM sees it. */
    @SuppressWarnings("unchecked")
    private ControlResponseDTO controlsRow(Control control) throws Exception {
        List<ControlResponseDTO> rows = (List<ControlResponseDTO>) perform(as(soqm, get("/controls")))
                .getModelAndView().getModel().get("controls");
        return rows.stream().filter(row -> control.getId().equals(row.getId())).findFirst().orElseThrow();
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
