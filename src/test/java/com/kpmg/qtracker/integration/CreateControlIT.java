package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.DeletedUser;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.entity.WorkflowStep;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.enums.WorkflowActionType;
import com.kpmg.qtracker.repository.DeletedUserRepository;
import com.kpmg.qtracker.repository.WorkflowStepRepository;
import com.kpmg.qtracker.service.ControlAutoCreationService;
import com.kpmg.qtracker.service.ControlAutoCreationService.NextCycleCreated;
import com.kpmg.qtracker.service.ControlAutoCreationService.NextCycleRefused;
import com.kpmg.qtracker.service.ControlScheduleCalculator;
import com.kpmg.qtracker.service.SoqmYear;
import com.kpmg.qtracker.service.WorkflowService;
import jakarta.mail.Address;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Create Control (business decision of 2026-10-14): the next cycle is made In Progress at once, as Initiate does it,
 * in one transaction; whatever stops it is listed for the source's Assignment tab and nothing is created. E-mail is
 * on here, sent to a mocked mail server.
 */
@TestPropertySource(properties = {"notifications.email.enabled=true", "notifications.email.whitelist-enforced=false",
        "management.health.mail.enabled=false"})
class CreateControlIT extends ReleaseScenarioSupport {

    @MockitoBean
    private JavaMailSender mailSender;

    @MockitoSpyBean
    private WorkflowService workflowService;

    @Autowired
    private ControlAutoCreationService service;

    @Autowired
    private WorkflowStepRepository stepRepository;

    @Autowired
    private DeletedUserRepository deletedUserRepository;

    private User soqm;
    private User facilitator;
    private User operator;
    private User owner;
    private User shared;

    @BeforeEach
    void setUp() {
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
        doCallRealMethod().when(workflowService).initiateWorkflow(any(), anyString(), any());
        soqm = soqm("cc-soqm");
        facilitator = myEdit("cc-fac");
        operator = myEdit("cc-op");
        owner = myEdit("cc-po");
        shared = myRead("cc-shared");
    }

    private Control source(String status, String frequency, String period) throws Exception {
        return source(status, frequency, period,
                people().soqm(soqm).facilitator(facilitator).operator(operator).owner(owner).shared(shared));
    }

    /** A source whose ID ends with the period, e.g. HR-CC-n-run/FY27/Central/OCT. */
    private Control source(String status, String frequency, String period, People people) throws Exception {
        Control control = control("HR-CC", status, people, c -> {
            c.setControlFrequency(frequency);
            c.setSoqmHeadComments("SoQM comments of the source");
            c.setProcessOwnerComments("Owner comments of the source");
            c.setReturnToFacilitatorComment("Returned once");
        });
        control.setControlId(control.getControlId() + "/FY27/Central/" + period);
        control = controlRepository.save(control);
        ControlDetails details = detailsRepository.findByControlId(control.getId()).orElseThrow();
        details.setProcessName("Process of the source");
        details.setDepartment("Department of the source");
        details.setItApplications("SAP");
        details.setControlOperatorReview("Operator's Program of the source");
        detailsRepository.save(details);
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setControlOperationDate(LocalDate.of(2026, 10, 12));
        assignment.setNextControlOperationDate(LocalDate.of(2026, 11, 10));
        assignmentRepository.save(assignment);
        return reload(control);
    }

    @Test
    void otherRelatedControls_theNextCycleHasTheSameControlIds_inTheirOrder() throws Exception {
        Control source = source("COMPLETED", "Monthly", "OCT");
        ControlDetails sourceDetails = detailsRepository.findByControlId(source.getId()).orElseThrow();
        sourceDetails.setOtherRelatedControls("HR-REL-2/FY27; EP-REL-1/FY27");
        detailsRepository.save(sourceDetails);

        NextCycleCreated created = service.createNextCycle(source.getId(), null, soqm);

        assertThat(detailsRepository.findByControlId(created.id()).orElseThrow().getOtherRelatedControls())
                .isEqualTo("HR-REL-2/FY27; EP-REL-1/FY27");
        // The source keeps its own
        assertThat(detailsRepository.findByControlId(source.getId()).orElseThrow().getOtherRelatedControls())
                .isEqualTo("HR-REL-2/FY27; EP-REL-1/FY27");
    }

    @Test
    void monthly_madeInProgress_initiatedByThePresser_fieldsCopied_restEmpty_facilitatorsTold() throws Exception {
        Control source = source("COMPLETED", "Monthly", "OCT");

        NextCycleCreated created = service.createNextCycle(source.getId(), null, soqm);

        String expectedId = source.getControlId().replace("/OCT", "/NOV");
        assertThat(created.controlId()).isEqualTo(expectedId);
        assertThat(created.sourceControlId()).isEqualTo(source.getControlId());
        Control copy = controlRepository.findById(created.id()).orElseThrow();
        assertThat(copy.getPerformanceStatus()).isEqualTo("IN_PROGRESS");
        // Control tab
        assertThat(copy.getControlFrequency()).isEqualTo("Monthly");
        assertThat(copy.getControlCategory()).isEqualTo(source.getControlCategory());
        assertThat(copy.getControlType()).isEqualTo(source.getControlType());
        assertThat(copy.getComponent()).isEqualTo(source.getComponent());
        assertThat(copy.getOperatedBy()).isEqualTo(source.getOperatedBy());
        assertThat(copy.getPriority()).isEqualTo(source.getPriority());
        assertThat(copy.getNonAuditServicesApplicability()).isEqualTo(source.getNonAuditServicesApplicability());
        assertThat(copy.getControlStatus()).isEqualTo(source.getControlStatus());
        assertThat(copy.getControlDescription()).isEqualTo(source.getControlDescription());
        assertThat(copy.getPrp()).isEqualTo(source.getPrp());
        assertThat(copy.getSoqmYear()).isEqualTo(SoqmYear.current(LocalDate.of(2026, 11, 10)));
        assertThat(copy.getCreatedBy().getId()).isEqualTo(soqm.getId());
        // Not copied
        assertThat(copy.getSoqmHeadComments()).isNull();
        assertThat(copy.getProcessOwnerComments()).isNull();
        assertThat(copy.getReturnToFacilitatorComment()).isNull();
        assertThat(copy.getAttachmentDetailsPath()).isNull();
        assertThat(copy.getAttachmentDocumentsPath()).isNull();
        assertThat(attachmentRepository.findAll()).noneMatch(a -> created.id().equals(a.getControlId()));
        // Details tab
        ControlDetails details = detailsRepository.findByControlId(created.id()).orElseThrow();
        assertThat(details.getProcessName()).isEqualTo("Process of the source");
        assertThat(details.getDepartment()).isEqualTo("Department of the source");
        assertThat(details.getItApplications()).isEqualTo("SAP");
        assertThat(details.getProcessActivities()).isNull();
        assertThat(details.getControlStepsPerformed()).isNull();
        assertThat(details.getControlOperatorReview()).isNull();
        assertThat(details.getSoqmHeadComments()).isNull();
        assertThat(details.getProcessOwnerComments()).isNull();
        // Assignment tab: the people, the dates from the source's next date
        ControlAssignment assignment = assignmentRepository.findByControlId(created.id()).orElseThrow();
        assertThat(assignment.getFacilitator()).isEqualTo(facilitator.getMail());
        assertThat(assignment.getControlOperator()).isEqualTo(operator.getMail());
        assertThat(assignment.getProcessOwner()).isEqualTo(owner.getMail());
        assertThat(assignment.getSoqmLead()).isEqualTo(soqm.getMail());
        assertThat(assignment.getControlSharedWith()).isEqualTo(shared.getMail());
        assertThat(assignment.getControlOperationDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(assignment.getControlOperationDeadline())
                .isEqualTo(ControlScheduleCalculator.DEADLINE.from(LocalDate.of(2026, 11, 10)))
                .isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(copy.getDeadline()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(assignment.getNextControlOperationDate()).isEqualTo(LocalDate.of(2026, 12, 10));

        // As Initiate: four steps, the Facilitator's in progress, one "Initiated" by the presser
        List<WorkflowStep> steps = stepRepository.findByControlIdOrderBySequenceOrderAsc(created.id());
        assertThat(steps).hasSize(4);
        List<WorkflowHistory> history = historyRepository.findByControlIdOrderByCreatedAtDesc(created.id());
        assertThat(history).singleElement().satisfies(entry -> {
            assertThat(entry.getActionType()).isEqualTo(WorkflowActionType.INITIATE);
            assertThat(entry.getPerformedByEmail()).isEqualTo(soqm.getMail());
            assertThat(entry.getToStep()).isEqualTo("IN_PROGRESS");
        });
        // Control Initiated to the Facilitator, in the application and by e-mail; nothing else
        List<Notification> notices = notificationRepository.findByControlIdOrderByCreatedAtDesc(created.id());
        assertThat(notices).singleElement().satisfies(notice -> {
            assertThat(notice.getType()).isEqualTo("INITIATE");
            assertThat(notice.getUserId()).isEqualTo(facilitator.getId());
        });
        assertThat(mailedTo()).contains(facilitator.getMail());

        // The source is left as it was
        Control after = reload(source);
        assertThat(after.getPerformanceStatus()).isEqualTo("COMPLETED");
        assertThat(after.getControlId()).isEqualTo(source.getControlId());
        assertThat(assignmentRepository.findByControlId(source.getId()).orElseThrow().getNextControlOperationDate())
                .isEqualTo(LocalDate.of(2026, 11, 10));
    }

    @Test
    void quarterly_typedId_isUsedAsTyped() throws Exception {
        Control source = source("IN_PROGRESS", "Quarterly", "4Q");
        String typed = "HR-TYPED-" + next() + "-" + RUN + "/FY28/Central/1Q";

        NextCycleCreated created = service.createNextCycle(source.getId(), "  " + typed + " ", soqm);

        assertThat(created.controlId()).isEqualTo(typed);
        Control copy = controlRepository.findById(created.id()).orElseThrow();
        assertThat(copy.getPerformanceStatus()).isEqualTo("IN_PROGRESS");
        ControlAssignment assignment = assignmentRepository.findByControlId(created.id()).orElseThrow();
        assertThat(assignment.getNextControlOperationDate()).isEqualTo(LocalDate.of(2027, 2, 10));
    }

    @Test
    void quarterly_proposedId_crossesIntoTheNextFiscalYear() throws Exception {
        Control source = source("IN_PROGRESS", "Quarterly", "4Q");

        NextCycleCreated created = service.createNextCycle(source.getId(), "", soqm);

        assertThat(created.controlId()).isEqualTo(source.getControlId().replace("/FY27/Central/4Q", "/FY28/Central/1Q"));
    }

    @Test
    void disabledFacilitator_nothingCreated_theProblemNamed() throws Exception {
        Control source = source("IN_PROGRESS", "Monthly", "OCT");
        disable(facilitator);

        NextCycleRefused refused = refused(source);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.getMessage()).contains("Assignment tab of " + source.getControlId());
        assertThat(refused.problems()).containsExactly("Facilitator: " + facilitator.getMail() + " is disabled");
    }

    @Test
    void deletedControlOperator_disabledProcessOwner_emptySoqm_allNamed_nothingCreated() throws Exception {
        Control source = source("REVIEW", "Monthly", "OCT",
                people().facilitator(facilitator).operator(operator).owner(owner));
        deleteUser(operator);
        disable(owner);

        NextCycleRefused refused = refused(source);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.problems()).containsExactly(
                "Control Operator: " + operator.getMail() + " was deleted from QTracker",
                "Process Owner: " + owner.getMail() + " is disabled",
                "SoQM Team / Delegate: nobody is assigned");
    }

    @Test
    void soqmTeamDelegate_whoIsNoLongerSoqm_isAProblem() throws Exception {
        User former = soqm("cc-former");
        Control source = source("IN_PROGRESS", "Monthly", "OCT",
                people().soqm(former).facilitator(facilitator).operator(operator).owner(owner));
        User stored = userRepository.findById(former.getId()).orElseThrow();
        stored.setAccessLevel(AccessLevel.PARTICIPANT);
        stored.setAccessScope(AccessScope.OWN);
        userRepository.save(stored);

        NextCycleRefused refused = refused(source);

        assertThat(refused.problems()).containsExactly(
                "SoQM Team / Delegate: " + former.getMail() + " is not SoQM Admin Team");
    }

    @Test
    void noNextDate_isAProblem() throws Exception {
        Control source = source("IN_PROGRESS", "Monthly", "OCT");
        ControlAssignment assignment = assignmentRepository.findByControlId(source.getId()).orElseThrow();
        assignment.setNextControlOperationDate(null);
        assignmentRepository.save(assignment);

        NextCycleRefused refused = refused(source);

        assertThat(refused.problems()).singleElement().asString().startsWith("Next Control Operation Date: empty");
    }

    @Test
    void sharedWith_aDeletedPersonIsLeftOut_aDisabledOneKept() throws Exception {
        User gone = myEdit("cc-gone");
        User off = myEdit("cc-off");
        Control source = source("IN_PROGRESS", "Monthly", "OCT",
                people().soqm(soqm).facilitator(facilitator).operator(operator).owner(owner).shared(gone, off, shared));
        deleteUser(gone);
        disable(off);

        NextCycleCreated created = service.createNextCycle(source.getId(), null, soqm);

        assertThat(created.sharedWithLeftOut()).containsExactly(gone.getMail() + " (deleted from QTracker)");
        assertThat(assignmentRepository.findByControlId(created.id()).orElseThrow().getControlSharedWith())
                .isEqualTo(off.getMail() + "," + shared.getMail());
    }

    @Test
    void idUsedInAnotherCase_isAConflict_nothingCreated() throws Exception {
        Control source = source("IN_PROGRESS", "Monthly", "OCT");
        Control other = control("HR-OTHER", "DRAFT", people());

        NextCycleRefused refused = refused(source, other.getControlId().toLowerCase(Locale.ROOT));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.existing().getId()).isEqualTo(other.getId());
    }

    @Test
    void aFailureWhileInitiating_leavesNothingBehind() throws Exception {
        Control source = source("IN_PROGRESS", "Monthly", "OCT");
        String expectedId = source.getControlId().replace("/OCT", "/NOV");
        long before = controlRepository.count();
        doThrow(new IllegalStateException("workflow down")).when(workflowService)
                .initiateWorkflow(any(), eq(facilitator.getMail()), any());

        Throwable failure = catchThrowableOfType(IllegalStateException.class,
                () -> service.createNextCycle(source.getId(), null, soqm));

        assertThat(failure).hasMessage("workflow down");
        assertThat(controlRepository.count()).isEqualTo(before);
        assertThat(controlRepository.findByControlIdIgnoringCase(expectedId)).isEmpty();
    }

    // ---------------------------------------------------------------- over HTTP: who and which controls (item 1)

    @Test
    void soqmAdminTeam_previewsAndCreates_overHttp() throws Exception {
        Control source = source("IN_PROGRESS", "Monthly", "OCT");
        MockHttpSession session = login(soqm);

        assertThat(button(session, source)).isTrue();
        MvcResult preview = call(session, get("/api/controls/{id}/next-cycle", source.getId()));
        assertThat(preview.getResponse().getStatus()).as(body(preview)).isEqualTo(200);
        JsonNode plan = objectMapper.readTree(body(preview));
        assertThat(plan.get("proposedControlId").asText()).isEqualTo(source.getControlId().replace("/OCT", "/NOV"));
        assertThat(plan.get("operationDate").asText()).isEqualTo("10.11.2026");
        assertThat(plan.get("deadline").asText()).isEqualTo("24.11.2026");
        assertThat(plan.get("nextOperationDate").asText()).isEqualTo("10.12.2026");
        assertThat(plan.get("soqmYear").asText()).isEqualTo("1 OCT 2026 - 30 SEP 2027");
        assertThat(plan.get("problems")).isEmpty();
        assertThat(plan.get("notCopied")).hasSize(9);
        assertThat(plan.get("people").get(0).get("label").asText()).isEqualTo("Facilitator");
        assertThat(plan.get("people").get(0).get("people").get(0).asText())
                .isEqualTo(facilitator.getDisplayName() + " (" + facilitator.getMail() + ")");
        assertThat(plan.get("people").get(3).get("label").asText()).isEqualTo("SoQM Team / Delegate");
        assertThat(plan.get("people").get(4).get("label").asText()).isEqualTo("Control Shared With");
        assertThat(plan.get("people").get(0).get("required").asBoolean()).isTrue();
        assertThat(plan.get("people").get(3).get("required").asBoolean()).isTrue();
        assertThat(plan.get("people").get(4).get("required").asBoolean()).isFalse();
        assertThat(controlRepository.findByControlIdIgnoringCase(plan.get("proposedControlId").asText())).isEmpty();

        MvcResult created = callJson(session, post("/api/controls/{id}/next-cycle", source.getId()),
                "{\"controlId\":\"" + plan.get("proposedControlId").asText() + "\"}");
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(200);
        JsonNode answer = objectMapper.readTree(body(created));
        assertThat(answer.get("controlId").asText()).isEqualTo(plan.get("proposedControlId").asText());
        assertThat(answer.get("url").asText()).isEqualTo("/view-control/" + answer.get("id").asLong());
        assertThat(controlRepository.findById(answer.get("id").asLong()).orElseThrow().getPerformanceStatus())
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    void everyoneElse_403_noButton_nothingCreated() throws Exception {
        User kdnUser = kdn("cc-kdn");
        User editAssigned = myEdit("cc-edit");
        User allControls = allEdit("cc-all");
        User readAll = allRead("cc-ro-all");
        Control source = source("IN_PROGRESS", "Monthly", "OCT", people().soqm(soqm).facilitator(facilitator, editAssigned)
                .operator(operator).owner(owner).shared(shared));
        Control kdnSource = control("KDN-CC", "IN_PROGRESS",
                people().soqm(soqm).facilitator(kdnUser).operator(operator).owner(owner));
        kdnSource.setControlId(kdnSource.getControlId() + "/FY27/Central/OCT");
        kdnSource = controlRepository.save(kdnSource);
        long before = controlRepository.count();

        for (Object[] row : new Object[][]{{editAssigned, source}, {facilitator, source}, {allControls, source},
                {shared, source}, {readAll, source}, {kdnUser, kdnSource}}) {
            User user = (User) row[0];
            Control control = (Control) row[1];
            MockHttpSession session = login(user);
            assertThat(button(session, control)).as(user.getMail()).isFalse();
            assertThat(status(session, get("/api/controls/{id}/next-cycle", control.getId()))).as(user.getMail())
                    .isEqualTo(403);
            assertThat(callJson(session, post("/api/controls/{id}/next-cycle", control.getId()), "{}")
                    .getResponse().getStatus()).as(user.getMail()).isEqualTo(403);
        }
        // Not signed in
        assertThat(call(null, post("/api/controls/{id}/next-cycle", source.getId())).getResponse().getStatus())
                .isIn(302, 401);
        assertThat(controlRepository.count()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Annual", "Semi Annual", "Recurring", "Ad-hoc"})
    void otherFrequencies_400_noButton(String frequency) throws Exception {
        Control source = source("IN_PROGRESS", frequency, "OCT");
        MockHttpSession session = login(soqm);
        long before = controlRepository.count();

        assertThat(button(session, source)).isFalse();
        MvcResult preview = call(session, get("/api/controls/{id}/next-cycle", source.getId()));
        assertThat(preview.getResponse().getStatus()).isEqualTo(400);
        assertThat(objectMapper.readTree(body(preview)).get("message").asText())
                .isEqualTo("Create Control is only for Monthly and Quarterly controls");
        assertThat(callJson(session, post("/api/controls/{id}/next-cycle", source.getId()),
                "{\"controlId\":\"TYPED-" + next() + "\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(controlRepository.count()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED"})
    void anyStatusPastDraft_makesTheNextCycle(String status) throws Exception {
        Control source = source(status, "Quarterly", "2Q");
        MockHttpSession session = login(soqm);

        assertThat(button(session, source)).isTrue();
        MvcResult created = callJson(session, post("/api/controls/{id}/next-cycle", source.getId()), "{}");
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(200);
        assertThat(objectMapper.readTree(body(created)).get("controlId").asText())
                .isEqualTo(source.getControlId().replace("/2Q", "/3Q"));
    }

    @Test
    void draftDeletedSuperseded_400_noButton() throws Exception {
        MockHttpSession session = login(soqm);
        Control draft = source("DRAFT", "Monthly", "OCT");
        Control deleted = source("IN_PROGRESS", "Monthly", "OCT");
        deleted.setControlStatus("DELETED");
        deleted = controlRepository.save(deleted);
        Control superseded = source("COMPLETED", "Quarterly", "1Q");
        superseded.setControlStatus("SUPERSEDED");
        superseded = controlRepository.save(superseded);
        long before = controlRepository.count();

        for (Control control : List.of(draft, deleted, superseded)) {
            assertThat(button(session, control)).as(control.getControlId()).isFalse();
            MvcResult result = callJson(session, post("/api/controls/{id}/next-cycle", control.getId()), "{}");
            assertThat(result.getResponse().getStatus()).as(control.getControlId()).isEqualTo(400);
        }
        assertThat(objectMapper.readTree(body(call(session, get("/api/controls/{id}/next-cycle", draft.getId()))))
                .get("message").asText()).isEqualTo("This control is still a Draft: initiate it before creating its next control");
        assertThat(objectMapper.readTree(body(call(session, get("/api/controls/{id}/next-cycle", deleted.getId()))))
                .get("message").asText()).isEqualTo("This control is Deleted: it has no next control");
        assertThat(controlRepository.count()).isEqualTo(before);
    }

    @Test
    void preview_listsTheProblems_createRefusesWithThem() throws Exception {
        Control source = source("IN_PROGRESS", "Monthly", "OCT");
        disable(operator);
        MockHttpSession session = login(soqm);

        JsonNode plan = objectMapper.readTree(body(call(session, get("/api/controls/{id}/next-cycle", source.getId()))));
        assertThat(plan.get("problems").get(0).asText()).isEqualTo("Control Operator: " + operator.getMail() + " is disabled");
        MvcResult created = callJson(session, post("/api/controls/{id}/next-cycle", source.getId()), "{}");
        assertThat(created.getResponse().getStatus()).isEqualTo(400);
        JsonNode error = objectMapper.readTree(body(created));
        assertThat(error.get("message").asText())
                .isEqualTo("The next control cannot be created. Fix this on the Assignment tab of " + source.getControlId() + ":");
        assertThat(error.get("problems").get(0).asText()).isEqualTo("Control Operator: " + operator.getMail() + " is disabled");
    }

    // ---------------------------------------------------------------- duplicates (item 6)

    @Test
    void secondClick_409_withTheLinkToTheFirst_previewToo() throws Exception {
        Control source = source("IN_PROGRESS", "Monthly", "OCT");
        MockHttpSession session = login(soqm);

        MvcResult first = callJson(session, post("/api/controls/{id}/next-cycle", source.getId()), "{}");
        assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(200);
        long firstId = objectMapper.readTree(body(first)).get("id").asLong();
        long count = controlRepository.count();

        for (String typed : new String[]{"", source.getControlId().replace("/OCT", "/NOV"),
                source.getControlId().replace("/OCT", "/NOV").toLowerCase(Locale.ROOT)}) {
            MvcResult again = callJson(session, post("/api/controls/{id}/next-cycle", source.getId()),
                    "{\"controlId\":\"" + typed + "\"}");
            assertThat(again.getResponse().getStatus()).as(typed).isEqualTo(409);
            JsonNode error = objectMapper.readTree(body(again));
            assertThat(error.get("existing").get("id").asLong()).isEqualTo(firstId);
            assertThat(error.get("existing").get("url").asText()).isEqualTo("/view-control/" + firstId);
        }
        MvcResult preview = call(session, get("/api/controls/{id}/next-cycle", source.getId()));
        assertThat(preview.getResponse().getStatus()).isEqualTo(409);
        JsonNode error = objectMapper.readTree(body(preview));
        assertThat(error.get("message").asText()).isEqualTo("The next control of " + source.getControlId()
                + " already exists: " + source.getControlId().replace("/OCT", "/NOV"));
        assertThat(error.get("existing").get("controlId").asText()).isEqualTo(source.getControlId().replace("/OCT", "/NOV"));
        assertThat(controlRepository.count()).isEqualTo(count);
    }

    @Test
    void aCycleOfTheSameBaseOnTheNewDate_madeElsewhere_is409() throws Exception {
        Control source = source("COMPLETED", "Monthly", "OCT");
        // As the daily auto-creation names its cycles: the base, /FY/location/period, maybe "(1)"
        String base = source.getControlId().substring(0, source.getControlId().indexOf('/'));
        Control other = control("X", "DRAFT", people());
        other.setControlId(base + "/FY27/Central/NOV(1)");
        other = controlRepository.save(other);
        ControlAssignment otherAssignment = assignmentRepository.findByControlId(other.getId()).orElseThrow();
        otherAssignment.setControlOperationDate(LocalDate.of(2026, 11, 10));
        assignmentRepository.save(otherAssignment);

        NextCycleRefused refused = refused(source, "SOMETHING-ELSE-" + next());

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.existing().getId()).isEqualTo(other.getId());
    }

    @Test
    void parallelRequests_makeOneControl() throws Exception {
        Control source = source("IN_PROGRESS", "Quarterly", "1Q");
        long before = controlRepository.count();
        int requests = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        List<Future<Object>> answers = new ArrayList<>();
        try {
            for (int i = 0; i < requests; i++) {
                // Half take the proposed ID, half type it (in another case)
                String typed = i % 2 == 0 ? null : source.getControlId().replace("/1Q", "/2q");
                Callable<Object> request = () -> {
                    start.await();
                    try {
                        return service.createNextCycle(source.getId(), typed, soqm);
                    } catch (NextCycleRefused e) {
                        return e;
                    }
                };
                answers.add(pool.submit(request));
            }
            start.countDown();
            List<NextCycleCreated> made = new ArrayList<>();
            List<NextCycleRefused> refusedAnswers = new ArrayList<>();
            for (Future<Object> answer : answers) {
                Object value = answer.get(60, TimeUnit.SECONDS);
                if (value instanceof NextCycleCreated created) {
                    made.add(created);
                } else {
                    refusedAnswers.add((NextCycleRefused) value);
                }
            }
            assertThat(made).hasSize(1);
            assertThat(refusedAnswers).hasSize(requests - 1)
                    .allSatisfy(r -> {
                        assertThat(r.status()).isEqualTo(409);
                        assertThat(r.existing().getId()).isEqualTo(made.get(0).id());
                    });
            assertThat(controlRepository.count()).isEqualTo(before + 1);
            assertThat(historyRepository.findByControlIdOrderByCreatedAtDesc(made.get(0).id())).hasSize(1);
            assertThat(notificationRepository.findByControlIdOrderByCreatedAtDesc(made.get(0).id())).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- confirmation, banner, audit (item 7)

    @Test
    void createdFrom_audit_changelog_banner() throws Exception {
        Control source = source("COMPLETED", "Monthly", "OCT");
        MockHttpSession session = login(soqm);

        MvcResult created = callJson(session, post("/api/controls/{id}/next-cycle", source.getId()), "{}");
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(200);
        long newId = objectMapper.readTree(body(created)).get("id").asLong();
        String newControlId = source.getControlId().replace("/OCT", "/NOV");
        assertThat(controlRepository.findById(newId).orElseThrow().getCreatedFromControlId()).isEqualTo(source.getId());
        assertThat(reload(source).getCreatedFromControlId()).isNull();

        // admin_audit_log: who, from which control, which new ID - on both controls
        List<AdminAuditLog> onNew = auditRepository.findByControlIdOrderByCreatedAtDesc(newId);
        assertThat(onNew).singleElement().satisfies(entry -> {
            assertThat(entry.getActionType()).isEqualTo(ControlAutoCreationService.AUDIT_CREATED_FROM);
            assertThat(entry.getAdminEmail()).isEqualTo(soqm.getMail());
            assertThat(entry.getControlControlId()).isEqualTo(newControlId);
            assertThat(entry.getNewValues()).isEqualTo("{\"Created from\":\"" + source.getControlId()
                    + "\",\"Control ID\":\"" + newControlId + "\"}");
            assertThat(entry.getActionDescription()).isEqualTo("Created from " + source.getControlId() + " with Create Control");
        });
        assertThat(auditRepository.findByControlIdOrderByCreatedAtDesc(source.getId()))
                .filteredOn(entry -> ControlAutoCreationService.AUDIT_NEXT_CREATED.equals(entry.getActionType()))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.getAdminEmail()).isEqualTo(soqm.getMail());
                    assertThat(entry.getNewValues()).isEqualTo("{\"Next control\":\"" + newControlId + "\"}");
                });

        // The Changelog of each shows it
        JsonNode newLog = objectMapper.readTree(body(call(session, get("/api/controls/{id}/changelog", newId))));
        assertThat(newLog.findValuesAsText("eventName")).contains("New Control", "Created with Create Control");
        assertThat(body(call(session, get("/api/controls/{id}/changelog", newId))))
                .contains(source.getControlId()).contains(newControlId);
        JsonNode sourceLog = objectMapper.readTree(body(call(session, get("/api/controls/{id}/changelog", source.getId()))));
        assertThat(sourceLog.findValuesAsText("eventName")).contains("Next Control Created");

        // The new control's page: "Created from <ID>" with a link to the source
        String page = body(call(session, get("/view-control/{id}", newId)));
        assertThat(page).contains("id=\"createdFromBanner\"")
                .contains("href=\"/view-control/" + source.getId() + "\"");
        assertThat(body(call(session, get("/view-control/{id}", source.getId())))).doesNotContain("id=\"createdFromBanner\"");
    }

    @Test
    void theButtonAndItsConfirmation_onThePage_ofSoqmOnly() throws Exception {
        Control source = source("IN_PROGRESS", "Quarterly", "1Q");

        String soqmPage = body(call(login(soqm), get("/view-control/{id}", source.getId())));
        assertThat(soqmPage).contains("id=\"createControlBtn\"", "id=\"createControlModal\"", "id=\"ccControlId\"",
                "/js/create-control.js?v=");
        // Between Back and Edit
        assertThat(soqmPage.indexOf("id=\"backBtn\"")).isLessThan(soqmPage.indexOf("id=\"createControlBtn\""));
        assertThat(soqmPage.indexOf("id=\"createControlBtn\"")).isLessThan(soqmPage.indexOf("id=\"editBtn\""));
        // In the bar of every tab, not only Assignment (decision of 2026-10-15): rendered without "hidden"
        String button = soqmPage.substring(soqmPage.indexOf("id=\"createControlBtn\""));
        assertThat(button.substring(0, button.indexOf(">"))).doesNotContain("hidden");

        String facilitatorPage = body(call(login(facilitator), get("/view-control/{id}", source.getId())));
        assertThat(facilitatorPage).doesNotContain("id=\"createControlBtn\"", "id=\"createControlModal\"");
    }

    @Test
    void aSecondCycleFromTheSameSource_underAnotherTypedId_is409() throws Exception {
        Control source = source("IN_PROGRESS", "Monthly", "OCT");
        NextCycleCreated first = service.createNextCycle(source.getId(), "FIRST-" + next() + "-" + RUN, soqm);

        NextCycleRefused refused = refused(source, "SECOND-" + next() + "-" + RUN);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.existing().getId()).isEqualTo(first.id());
        assertThat(refused.getMessage()).isEqualTo("The next control of " + source.getControlId()
                + " already exists: " + first.controlId());
    }

    // ---------------------------------------------------------------- helpers

    /** Whether View Control offers Create Control to the signed-in user (null when the page does not open). */
    private Boolean button(MockHttpSession session, Control control) throws Exception {
        MvcResult page = call(session, get("/view-control/{id}", control.getId()));
        if (page.getResponse().getStatus() != 200 || page.getModelAndView() == null
                || !"view-control".equals(page.getModelAndView().getViewName())) {
            return false;
        }
        return Boolean.TRUE.equals(page.getModelAndView().getModel().get("canCreateControl"));
    }

    private NextCycleRefused refused(Control source) {
        return refused(source, null);
    }

    private NextCycleRefused refused(Control source, String typedId) {
        long before = controlRepository.count();
        NextCycleRefused refused = catchThrowableOfType(NextCycleRefused.class,
                () -> service.createNextCycle(source.getId(), typedId, soqm));
        assertThat(refused).as("refused").isNotNull();
        assertThat(controlRepository.count()).as("nothing created").isEqualTo(before);
        return refused;
    }

    /** As the Admin Panel's deletion leaves it: no user row, the address kept in deleted_users. */
    private void deleteUser(User user) {
        DeletedUser deleted = new DeletedUser();
        deleted.setUserId(user.getId());
        deleted.setMail(user.getMail());
        deleted.setDisplayName(user.getDisplayName());
        deleted.setRole("USER");
        deleted.setDeletedAt(LocalDateTime.now());
        deleted.setDeletedByEmail(soqm.getMail());
        deletedUserRepository.save(deleted);
        userRepository.deleteById(user.getId());
    }

    private List<String> mailedTo() throws Exception {
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, atLeastOnce()).send(sent.capture());
        List<String> to = new ArrayList<>();
        for (MimeMessage message : sent.getAllValues()) {
            for (Address address : message.getAllRecipients()) {
                to.add(address.toString());
            }
        }
        return to;
    }
}
