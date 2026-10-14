package com.kpmg.qtracker.integration;

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
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

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
        details.setOtherRelatedControls("Related");
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
        assertThat(details.getOtherRelatedControls()).isEqualTo("Related");
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

    // ---------------------------------------------------------------- helpers

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
