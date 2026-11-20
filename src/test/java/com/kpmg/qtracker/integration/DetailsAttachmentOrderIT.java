package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Details (2026-11-20): Attachment comes right after Control Steps Performed and Results and before SoQM Admin Team
 * Comments, for a person who may edit and one who only reads; the Documents tab is gone.
 */
class DetailsAttachmentOrderIT extends ReleaseScenarioSupport {

    @Test
    void attachment_followsTheSteps_beforeTheSoqmComments_forEditAndReadOnly() throws Exception {
        User soqm = soqm("dao-soqm");
        User reader = myRead("dao-ro");
        Control control = control("HR-DAO", "IN_PROGRESS", people().facilitator(myEdit("dao-fac")).operator(myEdit("dao-op"))
                .soqm(soqm).owner(myEdit("dao-po")).shared(reader));
        for (User user : new User[] {soqm, reader}) {
            String html = body(call(login(user), get("/view-control/{id}", control.getId())));
            int steps = html.indexOf("id=\"controlStepsPerformed\"");
            int attachment = html.indexOf("id=\"attachmentRow\"");
            int soqmComments = html.indexOf("id=\"soqmHeadComments\"");
            assertThat(steps).as(user.getMail()).isPositive();
            assertThat(attachment).as(user.getMail()).isGreaterThan(steps);
            assertThat(soqmComments).as(user.getMail()).isGreaterThan(attachment);
            assertThat(html).as(user.getMail()).doesNotContain("href=\"#documents-tab\"", "id=\"documents-tab\"");
        }
    }
}
