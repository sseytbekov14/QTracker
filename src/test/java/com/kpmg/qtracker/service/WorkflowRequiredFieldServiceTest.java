package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowRequiredFieldServiceTest {

    private final ControlDetailsRepository repository = mock(ControlDetailsRepository.class);
    private final WorkflowRequiredFieldService service = new WorkflowRequiredFieldService(repository);

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
            "IN_PROGRESS,          controlStepsPerformed, Control steps performed and results, false",
            "REVIEW,               controlStepsPerformed, Control steps performed and results, true",
            "SOQM_HEAD_REVIEW,     soqmHeadComments,      SoQM Head/Team Comments,             false",
            "PROCESS_OWNER_REVIEW, processOwnerComments,  Process Owner Comments,              false"
    })
    void stepField_isTheFieldTheServerChecksAtThatStep(String status, String field, String label, boolean check) {
        WorkflowRequiredFieldService.StepField stepField = WorkflowRequiredFieldService.stepField(status).orElseThrow();
        assertThat(stepField.field()).isEqualTo(field);
        assertThat(stepField.label()).isEqualTo(label);
        assertThat(stepField.check()).isEqualTo(check);

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
