package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowRequiredFieldServiceTest {

    private final ControlDetailsRepository repository = mock(ControlDetailsRepository.class);
    private final ControlAssignmentRepository assignments = mock(ControlAssignmentRepository.class);
    private final WorkflowRequiredFieldService service = new WorkflowRequiredFieldService(repository, assignments);

    @Test
    void review_differentPeople_requiresTheOperatorReview_afterTheSteps() {
        assigned("fac@x.kz", "op@x.kz");
        ControlDetails details = new ControlDetails();
        when(repository.findByControlId(1L)).thenReturn(Optional.of(details));

        assertThat(service.getMissingFieldMessage(control("REVIEW")))
                .contains("Required field is missing: Control steps performed and results");

        details.setControlStepsPerformed("Steps by the Facilitator");
        details.setControlOperatorReview("  ");
        assertThat(service.getMissingFieldMessage(control("REVIEW")))
                .contains("Required field is missing: Control Operator Review and Results");

        details.setControlOperatorReview("Reviewed by the Operator");
        assertThat(service.getMissingFieldMessage(control("REVIEW"))).isEmpty();
    }

    @Test
    void review_onePerson_needsOnlyTheStepsField() {
        assigned("fac@x.kz; second@x.kz", " FAC@x.kz ");
        ControlDetails details = new ControlDetails();
        details.setControlStepsPerformed("Steps");
        when(repository.findByControlId(1L)).thenReturn(Optional.of(details));

        assertThat(service.getMissingFieldMessage(control("REVIEW"))).isEmpty();
    }

    @Test
    void otherSteps_differentPeople_requireNothingNew() {
        assigned("fac@x.kz", "op@x.kz");
        ControlDetails details = new ControlDetails();
        details.setControlStepsPerformed("Steps");
        when(repository.findByControlId(1L)).thenReturn(Optional.of(details));

        assertThat(service.getMissingFieldMessage(control("IN_PROGRESS"))).isEmpty();
        assertThat(service.getMissingFieldMessage(control("SOQM_HEAD_REVIEW"))).isEmpty();
        assertThat(service.getMissingFieldMessage(control("PROCESS_OWNER_REVIEW"))).isEmpty();
    }

    @Test
    void review_withoutAssignment_isOnePerson() {
        ControlDetails details = new ControlDetails();
        details.setControlStepsPerformed("Steps");
        when(repository.findByControlId(1L)).thenReturn(Optional.of(details));
        when(assignments.findByControlId(1L)).thenReturn(Optional.empty());

        assertThat(service.getMissingFieldMessage(control("REVIEW"))).isEmpty();
    }

    private void assigned(String facilitators, String operators) {
        ControlAssignment assignment = new ControlAssignment();
        assignment.setControlId(1L);
        assignment.setFacilitator(facilitators);
        assignment.setControlOperator(operators);
        when(assignments.findByControlId(1L)).thenReturn(Optional.of(assignment));
    }

    @Test
    void soqmReview_requiresSoqmHeadComments() {
        Control control = control("SOQM_HEAD_REVIEW");
        when(repository.findByControlId(1L)).thenReturn(Optional.of(new ControlDetails()));

        assertThat(service.getMissingReviewCommentMessage(control))
                .contains("Required field is missing: SoQM Head/Team Comments");
    }

    @Test
    void processOwnerReview_requiresProcessOwnerComments() {
        Control control = control("PROCESS_OWNER_REVIEW");
        ControlDetails details = new ControlDetails();
        details.setSoqmHeadComments("ok");
        details.setProcessOwnerComments("   ");
        when(repository.findByControlId(1L)).thenReturn(Optional.of(details));

        assertThat(service.getMissingReviewCommentMessage(control))
                .contains("Required field is missing: Process Owner Comments");
    }

    @Test
    void filledComment_orOtherStatus_passes() {
        ControlDetails details = new ControlDetails();
        details.setProcessOwnerComments("Reviewed");
        when(repository.findByControlId(1L)).thenReturn(Optional.of(details));

        assertThat(service.getMissingReviewCommentMessage(control("PROCESS_OWNER_REVIEW"))).isEmpty();
        assertThat(service.getMissingReviewCommentMessage(control("REVIEW"))).isEmpty();
    }

    private Control control(String status) {
        Control control = new Control();
        control.setId(1L);
        control.setPerformanceStatus(status);
        return control;
    }
}
