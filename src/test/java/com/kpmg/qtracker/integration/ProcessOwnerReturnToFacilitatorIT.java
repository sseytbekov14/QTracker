package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.notification.NotificationRecipients;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The Process Owner returns the control to the Facilitator (2026-11-25), besides Return to Control Operator: from
 * Process Owner Review to In Progress, with a reason, their own step (not SoQM on behalf); the Facilitator gets T7
 * "returned to you by the Process Owner", the history says "Returned to Facilitator". Nobody else uses it at that step.
 */
class ProcessOwnerReturnToFacilitatorIT extends NotificationScenarioSupport {

    static final LocalDate DAY = LocalDate.of(2026, 11, 13);

    @Autowired
    JdbcTemplate jdbc;

    private Control control(String tag, User fac, User op, User soqm, User po) throws Exception {
        return dated(control("PORF-" + tag, "PROCESS_OWNER_REVIEW", people().facilitator(fac).operator(op).soqm(soqm)
                .owner(po)), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 19));
    }

    private int returnToFacilitator(MockHttpSession session, Control control, String reason) throws Exception {
        var request = post("/api/workflow/return-to-facilitator").param("controlId", String.valueOf(control.getId()));
        if (reason != null) {
            request.param("comments", reason);
        }
        return status(session, request);
    }

    private String column(Control control, String column) {
        return jdbc.queryForObject("SELECT COALESCE(" + column + ", '') FROM controls WHERE id = ?", String.class, control.getId());
    }

    @Test
    void theProcessOwner_returnsToTheFacilitator_withAReason_theFacilitatorIsTold() throws Exception {
        User fac = myEdit("porf-fac");
        User op = myEdit("porf-op");
        User po = myEdit("porf-po");
        Control control = control("A", fac, op, soqm("porf-soqm"), po);
        CLOCK.set(DAY.atTime(12, 0));
        MockHttpSession session = login(po);

        // The page offers it next to Return to Control Operator
        assertThat(body(call(session, get("/view-control/{id}", control.getId()))))
                .contains("id=\"ownerReturnToFacilitatorBtn\"", "Return to Facilitator",
                        "Complete, Return to Control Operator or Return to Facilitator");
        // Not their step: the Facilitator and the Control Operator cannot
        assertThat(returnToFacilitator(login(fac), control, "no")).isIn(403, 409);
        assertThat(returnToFacilitator(login(op), control, "no")).isIn(403, 409);
        // A reason is required
        assertThat(returnToFacilitator(session, control, null)).isEqualTo(400);
        assertThat(returnToFacilitator(session, control, "  ")).isEqualTo(400);
        assertThat(column(control, "performance_status")).isEqualTo("PROCESS_OWNER_REVIEW");

        assertThat(returnToFacilitator(session, control, "The sample is from the wrong month")).isEqualTo(200);
        assertThat(column(control, "performance_status")).isEqualTo("IN_PROGRESS");
        assertThat(column(control, "return_to_facilitator_comment")).isEqualTo("The sample is from the wrong month");
        assertThat(jdbc.queryForObject("SELECT action_type FROM workflow_history WHERE control_id = ? ORDER BY id DESC"
                + " LIMIT 1", String.class, control.getId())).isEqualTo("RETURN_TO_FACILITATOR");
        assertThat(sent(control)).containsExactly(line("T7", fac, DAY));
        assertThat(mails(control).get(0).getBodyHtml()).contains("returned to you by the "
                + NotificationRecipients.PROCESS_OWNER + " for rework", "The sample is from the wrong month");
        // Back at the Facilitator: their step again
        assertThat(body(call(login(fac), get("/view-control/{id}", control.getId())))).contains("Your turn");
    }

    @Test
    void theSameThroughTheMoveOfTheProcessOwner_andSoqmOnBehalfNeedsAReasonToo() throws Exception {
        User po = myEdit("porf2-po");
        User soqm = soqm("porf2-soqm");
        Control byMove = control("B", myEdit("porf2-fac"), myEdit("porf2-op"), soqm, po);
        CLOCK.set(DAY.atTime(12, 0));
        assertThat(status(login(po), post("/api/workflow/move").param("controlId", String.valueOf(byMove.getId()))
                .param("targetStatus", "IN_PROGRESS").param("comments", ""))).isEqualTo(400);
        assertThat(status(login(po), post("/api/workflow/move").param("controlId", String.valueOf(byMove.getId()))
                .param("targetStatus", "IN_PROGRESS").param("comments", "Redo it"))).isEqualTo(200);
        assertThat(column(byMove, "performance_status")).isEqualTo("IN_PROGRESS");

        Control bySoqm = control("C", myEdit("porf3-fac"), myEdit("porf3-op"), soqm, myEdit("porf3-po"));
        assertThat(returnToFacilitator(login(soqm), bySoqm, null)).isEqualTo(400);
        assertThat(returnToFacilitator(login(soqm), bySoqm, "For the Process Owner")).isEqualTo(200);
        assertThat(column(bySoqm, "performance_status")).isEqualTo("IN_PROGRESS");
    }
}
