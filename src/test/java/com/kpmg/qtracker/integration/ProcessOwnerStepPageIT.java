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
        assertThat(html).contains("id=\"participantStep\" value=\"true\"", "id=\"participantSaveBtn\"",
                        "Your fields and files are open here: Save keeps your work, and those buttons save it too.")
                .doesNotContain("id=\"editBtn\"", "(press Edit to change it)");
        // SoQM Admin Team on the same control: Edit as before
        assertThat(page(login(soqm), control)).contains("id=\"editBtn\"", "id=\"participantStep\" value=\"false\"");

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

    /**
     * The Facilitator in In Progress and the Control Operator in Review the same way (2026-11-25): no Edit, their step
     * open with Save; not at another's step; one person in both fields at each of their steps.
     */
    @Test
    void theFacilitatorAndTheControlOperator_atTheirSteps_noEdit_withSave() throws Exception {
        User fac = myEdit("pst4-fac");
        User op = myEdit("pst4-op");
        User soqm = soqm("pst4-soqm");
        for (String[] step : new String[][] {{"IN_PROGRESS", "fac"}, {"REVIEW", "op"}}) {
            Control control = control("PST4", step[0], people().facilitator(fac).operator(op).soqm(soqm)
                    .owner(myEdit("pst4-po")));
            MockHttpSession holder = login("fac".equals(step[1]) ? fac : op);
            MockHttpSession other = login("fac".equals(step[1]) ? op : fac);
            assertThat(page(holder, control)).as(step[0])
                    .contains("id=\"participantStep\" value=\"true\"", "id=\"participantSaveBtn\"", "Save keeps your work")
                    // no Discard button (2026-11-25), no Edit
                    .doesNotContain("id=\"editBtn\"", "id=\"participantDiscardBtn\"");
            assertThat(page(other, control)).as(step[0] + " not theirs")
                    .contains("id=\"participantStep\" value=\"false\"").doesNotContain("id=\"participantSaveBtn\"")
                    // assigned, someone else's step: the calm "View only" line (2026-11-25)
                    .contains("id=\"viewOnlyHint\"", "View only:",
                            ">" + ("IN_PROGRESS".equals(step[0]) ? "Facilitator" : "Control Operator") + "</strong>'s step");
            assertThat(page(holder, control)).as(step[0] + " theirs: no View only line, no red sentence under the field")
                    .doesNotContain("id=\"viewOnlyHint\"", "This field is required before submission.",
                            "Required for your step: fill it");
            assertThat(page(login(soqm), control)).as(step[0] + " SoQM: no View only line").doesNotContain("id=\"viewOnlyHint\"");
            assertThat(page(login(soqm), control)).as(step[0] + " SoQM")
                    .contains("id=\"editBtn\"").doesNotContain("id=\"participantSaveBtn\"");
            // Save sends the steps field alone: taken; another field from them: kept as it was
            assertThat(callJson(holder, post("/api/control-details"), "{\"controlId\":" + control.getId()
                    + ",\"controlStepsPerformed\":\"Draft by the holder\"}").getResponse().getStatus()).isEqualTo(200);
            assertThat(column(control, "control_steps_performed")).isEqualTo("Draft by the holder");
            assertThat(column(control, "performance_status")).as("Save leaves the step").isEqualTo(step[0]);
        }
        // One person in both fields: open at each of their steps
        User both = myEdit("pst5-both");
        for (String status : List.of("IN_PROGRESS", "REVIEW")) {
            Control control = control("PST5", status, people().facilitator(both).operator(both).soqm(soqm)
                    .owner(myEdit("pst5-po")));
            assertThat(page(login(both), control)).as(status).contains("id=\"participantStep\" value=\"true\"");
        }
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
            assertThat(page(session, other)).as(status).contains("id=\"participantStep\" value=\"false\"");
        }
    }
}
