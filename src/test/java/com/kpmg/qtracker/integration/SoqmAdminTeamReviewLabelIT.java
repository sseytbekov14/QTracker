package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The SoQM step is called "SoQM Admin Team Review" (2026-11-10, earlier "SoQM Head Review"): on View Control, on
 * Performance cycle and in the list with its status filter. The stored status stays SOQM_HEAD_REVIEW.
 */
class SoqmAdminTeamReviewLabelIT extends ReleaseScenarioSupport {

    @Test
    void aControlAtTheSoqmStep_readsSoqmAdminTeamReview_everywhere() throws Exception {
        User soqm = soqm("satr-soqm");
        MockHttpSession session = login(soqm);
        Control control = control("HR-SATR", "SOQM_HEAD_REVIEW", people().facilitator(myEdit("satr-fac"))
                .operator(myEdit("satr-op")).soqm(soqm).owner(myEdit("satr-po")));

        for (String page : new String[] {"/view-control/" + control.getId(), "/performance-cycle/" + control.getId(),
                "/component/ALL?status=SOQM_HEAD_REVIEW&q=" + control.getControlId()}) {
            String html = body(call(session, get(page)));
            assertThat(html).as(page).contains("SoQM Admin Team Review").doesNotContain("SoQM Head");
        }
        assertThat(reload(control).getPerformanceStatus()).isEqualTo("SOQM_HEAD_REVIEW");
    }
}
