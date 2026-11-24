package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The Process Owner's step without Edit (2026-11-24): in Process Owner Review their page has no Edit button, the
 * fields are open and Complete / Return save them; the server takes from them only Process Owner Comments, Control
 * Steps Performed and Results and Attachments, and only in that step. SoQM Admin Team keeps Edit.
 */
class ProcessOwnerStepPageIT extends ReleaseScenarioSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String page(MockHttpSession session, Control control) throws Exception {
        return body(call(session, get("/view-control/{id}", control.getId())));
    }

    private int upload(MockHttpSession session, Control control, String name) throws Exception {
        return call(session, multipart("/api/attachments/upload/{id}", control.getId())
                .file(new MockMultipartFile("attachmentDetails", name, "application/pdf",
                        "%PDF-1.4 po".getBytes(StandardCharsets.UTF_8)))).getResponse().getStatus();
    }

    private String column(Control control, String column) {
        return jdbc.queryForObject("SELECT COALESCE(" + column + ", '') FROM controls WHERE id = ?", String.class, control.getId());
    }

    @Test
    void inTheirStep_noEdit_theFieldsAreOpen_andCompleteTakesWhatTheyWrote() throws Exception {
        User po = myEdit("pst-po");
        User soqm = soqm("pst-soqm");
        Control control = control("PST", "PROCESS_OWNER_REVIEW", people().facilitator(myEdit("pst-fac"))
                .operator(myEdit("pst-op")).soqm(soqm).owner(po));
        MockHttpSession session = login(po);

        String html = page(session, control);
        assertThat(html).contains("id=\"processOwnerStep\" value=\"true\"",
                        "Your fields and files are open here and are saved when you press one of them.")
                .doesNotContain("id=\"editBtn\"", "(press Edit to change it)");
        // SoQM Admin Team on the same control: Edit as before
        assertThat(page(login(soqm), control)).contains("id=\"editBtn\"", "id=\"processOwnerStep\" value=\"false\"");

        // What Complete sends first: the two fields, then each file, then the step
        assertThat(callJson(session, post("/api/control-details"), "{\"controlId\":" + control.getId()
                + ",\"processOwnerComments\":\"Approved\",\"controlStepsPerformed\":\"Checked by the owner\"}")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(upload(session, control, "owner-evidence.pdf")).isEqualTo(200);
        assertThat(status(session, post("/api/workflow/complete-control").param("controlId", String.valueOf(control.getId()))))
                .isEqualTo(200);
        assertThat(column(control, "performance_status")).isEqualTo("COMPLETED");
        assertThat(column(control, "process_owner_comments")).isEqualTo("Approved");
        assertThat(column(control, "control_steps_performed")).isEqualTo("Checked by the owner");
        assertThat(column(control, "attachment_details_path")).contains("owner-evidence.pdf");
    }

    @Test
    void onlyTheirThreeFields_andOnlyInTheirStep() throws Exception {
        User po = myEdit("pst2-po");
        Control control = control("PST2", "PROCESS_OWNER_REVIEW", people().facilitator(myEdit("pst2-fac"))
                .operator(myEdit("pst2-op")).soqm(soqm("pst2-soqm")).owner(po));
        MockHttpSession session = login(po);
        String priority = column(control, "priority");
        // A field of the control: refused, nothing changes
        assertThat(callJson(session, put("/api/controls/{id}", control.getId()),
                "{\"id\":" + control.getId() + ",\"controlId\":\"" + control.getControlId() + "\",\"priority\":\"Changed\"}")
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(column(control, "priority")).isEqualTo(priority);
        // Another details field sent with theirs: theirs is saved, the other kept
        assertThat(callJson(session, post("/api/control-details"), "{\"controlId\":" + control.getId()
                + ",\"processOwnerComments\":\"Ok\",\"soqmHeadComments\":\"not theirs\",\"processName\":\"not theirs\"}")
                .getResponse().getStatus()).isIn(200, 403);
        assertThat(column(control, "soqm_head_comments")).isNotEqualTo("not theirs");
        assertThat(column(control, "process_name")).isNotEqualTo("not theirs");

        // Outside their step: no field, no file, and their page has no open fields
        for (String status : List.of("IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "COMPLETED")) {
            Control other = control("PST3", status, people().facilitator(myEdit("pst3-fac")).operator(myEdit("pst3-op"))
                    .soqm(soqm("pst3-soqm")).owner(po));
            assertThat(upload(session, other, "late.pdf")).as(status).isEqualTo(403);
            assertThat(page(session, other)).as(status).contains("id=\"processOwnerStep\" value=\"false\"");
        }
    }
}
