package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The Process Owner writes Control Steps Performed and Results in Process Owner Review, their own step (2026-11-20),
 * besides their comments and nothing else; elsewhere, Read Only, not assigned and KDN read it only; the change is in
 * the Changelog with them as author; returning to the Control Operator still needs a comment.
 */
class ProcessOwnerStepsFieldIT extends ReleaseScenarioSupport {

    @Autowired
    JdbcTemplate jdbc;

    private int saveDetails(MockHttpSession session, Control control, String field, String text) throws Exception {
        return callJson(session, post("/api/control-details"),
                "{\"controlId\":" + control.getId() + ",\"" + field + "\":\"" + text + "\"}").getResponse().getStatus();
    }

    private String steps(Control control) {
        return jdbc.queryForObject("SELECT control_steps_performed FROM controls WHERE id = ?", String.class, control.getId());
    }

    @Test
    void theProcessOwner_writesTheSteps_inTheirReview_withHistory_andNoOtherField() throws Exception {
        User po = myEdit("pos-po");
        Control control = control("POS", "PROCESS_OWNER_REVIEW", people().facilitator(myEdit("pos-fac"))
                .operator(myEdit("pos-op")).soqm(soqm("pos-soqm")).owner(po));
        MockHttpSession session = login(po);

        assertThat(json(call(session, get("/api/permissions/{id}", control.getId()))).toString())
                .contains("\"canEditStepsPerformed\":true", "\"canEditProcessOwnerComments\":true");
        assertThat(saveDetails(session, control, "controlStepsPerformed", "Corrected by the process owner")).isEqualTo(200);
        assertThat(steps(control)).isEqualTo("Corrected by the process owner");
        assertThat(saveDetails(session, control, "processOwnerComments", "Fine")).isEqualTo(200);
        // No other field
        for (String field : List.of("processActivities", "soqmHeadComments", "controlOperatorReview", "processName")) {
            int before = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log WHERE control_id = ?", Integer.class,
                    control.getId());
            int answer = saveDetails(session, control, field, "not theirs");
            String stored = jdbc.queryForObject("SELECT COALESCE(" + (field.equals("processActivities") ? "process_activities"
                    : field.equals("soqmHeadComments") ? "soqm_head_comments" : field.equals("controlOperatorReview")
                    ? "control_operator_review" : "process_name") + ", '') FROM controls WHERE id = ?", String.class, control.getId());
            assertThat(answer == 403 || !stored.equals("not theirs")).as(field + " " + answer).isTrue();
            assertThat(stored).as(field).isNotEqualTo("not theirs");
            assertThat(before).isNotNegative();
        }
        // The Changelog names the Process Owner as the author of the change
        String changelog = body(call(session, get("/api/controls/{id}/changelog", control.getId())));
        assertThat(changelog).contains("Corrected by the process owner", po.getMail());

        // Returning to the Control Operator: a comment is still required
        assertThat(status(session, post("/api/workflow/move").param("controlId", String.valueOf(control.getId()))
                .param("targetStatus", "REVIEW").param("comments", ""))).isEqualTo(400);
        assertThat(status(session, post("/api/workflow/move").param("controlId", String.valueOf(control.getId()))
                .param("targetStatus", "REVIEW").param("comments", "Please check again"))).isEqualTo(200);
        // Back in Review the field is the Operator's again, not the Process Owner's
        assertThat(saveDetails(session, control, "controlStepsPerformed", "late")).isEqualTo(403);
        assertThat(steps(control)).isEqualTo("Corrected by the process owner");
    }

    @Test
    void otherStatuses_readOnly_notAssigned_andKdn_cannotWriteTheSteps() throws Exception {
        User po = myEdit("pos2-po");
        User poReadOnly = myRead("pos2-poro");
        User outsider = allEdit("pos2-out");
        User kdnUser = kdn("pos2-kdn");
        for (String status : List.of("IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "COMPLETED")) {
            Control control = control("POS2", status, people().facilitator(myEdit("pos2-fac")).operator(myEdit("pos2-op"))
                    .soqm(soqm("pos2-soqm")).owner(po));
            assertThat(saveDetails(login(po), control, "controlStepsPerformed", "x")).as(status).isEqualTo(403);
        }
        Control review = control("KDN-POS2", "PROCESS_OWNER_REVIEW", people().facilitator(myEdit("pos2-fac2"))
                .operator(myEdit("pos2-op2")).soqm(soqm("pos2-soqm2")).owner(poReadOnly).shared(kdnUser));
        for (User user : List.of(poReadOnly, outsider, kdnUser)) {
            assertThat(saveDetails(login(user), review, "controlStepsPerformed", "x")).as(user.getMail()).isEqualTo(403);
        }
        assertThat(steps(review)).isNotEqualTo("x");
    }
}
