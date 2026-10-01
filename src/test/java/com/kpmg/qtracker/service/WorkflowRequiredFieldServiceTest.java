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

    private Control control(String status) {
        Control control = new Control();
        control.setId(1L);
        control.setPerformanceStatus(status);
        return control;
    }
}
