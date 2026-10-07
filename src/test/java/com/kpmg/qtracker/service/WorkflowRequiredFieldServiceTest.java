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
                .contains("Required field is missing: Control Operator's Program");

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

    /**
     * The "Your step" hint names the field the server checks at that step: with that field empty the step's
     * actions are refused with its label, so the hint and the refusal never disagree.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "IN_PROGRESS,          controlStepsPerformed, Control Steps Performed and Results, false",
            "SOQM_HEAD_REVIEW,     soqmHeadComments,      SoQM Head/Team Comments,             false",
            "PROCESS_OWNER_REVIEW, processOwnerComments,  Process Owner Comments,              false"
    })
    void stepField_isTheFieldTheServerChecksAtThatStep(String status, String field, String label, boolean check) {
        WorkflowRequiredFieldService.StepField stepField = WorkflowRequiredFieldService.stepField(status).orElseThrow();
        assertThat(stepField.field()).isEqualTo(field);
        assertThat(stepField.label()).isEqualTo(label);
        assertThat(stepField.check()).isEqualTo(check);
        assertThat(stepField.required()).isTrue();

        // Details with every other step field filled, this one empty
        ControlDetails details = new ControlDetails();
        details.setControlStepsPerformed("controlStepsPerformed".equals(field) ? " " : "Steps");
        details.setSoqmHeadComments("soqmHeadComments".equals(field) ? null : "SoQM");
        details.setProcessOwnerComments("processOwnerComments".equals(field) ? "" : "Owner");
        when(repository.findByControlId(1L)).thenReturn(Optional.of(details));
        Optional<String> refusal = service.getMissingReviewCommentMessage(control(status))
                .or(() -> service.getMissingFieldMessage(control(status)));
        assertThat(refusal).hasValueSatisfying(message -> assertThat(message).containsIgnoringCase(label));
    }

    @Test
    void stepField_review_differentPeople_isTheOperatorsField_whichSubmitToSoqmChecks() {
        WorkflowRequiredFieldService.StepField stepField =
                WorkflowRequiredFieldService.stepField("REVIEW", true).orElseThrow();
        assertThat(stepField.field()).isEqualTo("controlOperatorReview");
        assertThat(stepField.label()).isEqualTo(ControlStepsFields.OPERATOR_PROGRAM_LABEL);
        assertThat(stepField.check()).isFalse();
        assertThat(stepField.required()).isTrue();
        // the other steps are the same in both modes
        for (String status : java.util.List.of("IN_PROGRESS", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW")) {
            assertThat(WorkflowRequiredFieldService.stepField(status, true))
                    .isEqualTo(WorkflowRequiredFieldService.stepField(status));
        }

        assigned("fac@x.kz", "op@x.kz");
        ControlDetails details = new ControlDetails();
        details.setControlStepsPerformed("Steps");
        when(repository.findByControlId(1L)).thenReturn(Optional.of(details));
        assertThat(service.getMissingFieldMessage(control("REVIEW")))
                .hasValueSatisfying(message -> assertThat(message).contains(stepField.label()));
    }

    @Test
    void stepField_review_onePerson_isTheOperatorsField_too_optional_asSubmitToSoqmDoesNotCheckIt() {
        WorkflowRequiredFieldService.StepField stepField =
                WorkflowRequiredFieldService.stepField("REVIEW", false).orElseThrow();
        assertThat(stepField.field()).isEqualTo("controlOperatorReview");
        assertThat(stepField.label()).isEqualTo(ControlStepsFields.OPERATOR_PROGRAM_LABEL);
        assertThat(stepField.required()).isFalse();
        assertThat(WorkflowRequiredFieldService.stepField("REVIEW")).contains(stepField);

        assigned("fac@x.kz", "fac@x.kz");
        ControlDetails details = new ControlDetails();
        details.setControlStepsPerformed("Steps");
        when(repository.findByControlId(1L)).thenReturn(Optional.of(details));
        assertThat(service.getMissingFieldMessage(control("REVIEW"))).isEmpty();
    }

    @Test
    void stepField_noneForDraftAndCompleted() {
        assertThat(WorkflowRequiredFieldService.stepField("DRAFT")).isEmpty();
        assertThat(WorkflowRequiredFieldService.stepField("COMPLETED")).isEmpty();
        assertThat(WorkflowRequiredFieldService.stepField(null)).isEmpty();
        assertThat(WorkflowRequiredFieldService.stepField(" soqm_head_review ")).isPresent();
    }

    private Control control(String status) {
        Control control = new Control();
        control.setId(1L);
        control.setPerformanceStatus(status);
        return control;
    }
}
