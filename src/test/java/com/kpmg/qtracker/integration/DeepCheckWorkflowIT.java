package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.WorkflowRequiredFieldService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Found by the deep check of 2026-11-25: the Process Owner cannot complete a control whose Steps they emptied; a
 * deactivated user cannot be put into a field through the API (one already there stays); the page shows the reason
 * of the return that brought the control to its step.
 */
class DeepCheckWorkflowIT extends ReleaseScenarioSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String column(Control control, String column) {
        return jdbc.queryForObject("SELECT COALESCE(" + column + ", '') FROM controls WHERE id = ?", String.class, control.getId());
    }

    @Test
    void completeNeedsTheSteps_aReturnDoesNot() throws Exception {
        User po = myEdit("dc-po");
        Control control = control("DC1", "PROCESS_OWNER_REVIEW", people().facilitator(myEdit("dc-fac"))
                .operator(myEdit("dc-op")).soqm(soqm("dc-soqm")).owner(po));
        MockHttpSession session = login(po);
        assertThat(callJson(session, post("/api/control-details"), "{\"controlId\":" + control.getId()
                + ",\"controlStepsPerformed\":\"\",\"processOwnerComments\":\"Fine\"}").getResponse().getStatus()).isEqualTo(200);

        MvcResult refused = call(session, post("/api/workflow/complete-control").param("controlId", String.valueOf(control.getId())));
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(refused)).contains(WorkflowRequiredFieldService.MISSING_STEPS);
        assertThat(column(control, "performance_status")).isEqualTo("PROCESS_OWNER_REVIEW");
        // a return still goes without the Steps
        assertThat(status(session, post("/api/workflow/return-to-operator").param("controlId", String.valueOf(control.getId()))
                .param("comments", "Steps are empty, please fill them"))).isEqualTo(200);
        assertThat(column(control, "performance_status")).isEqualTo("REVIEW");
    }

    @Test
    void aDeactivatedUser_cannotBePutIntoAField_oneAlreadyThereStays() throws Exception {
        User soqm = soqm("dc2-soqm");
        User op = myEdit("dc2-op");
        Control control = control("DC2", "IN_PROGRESS", people().facilitator(myEdit("dc2-fac")).operator(op).soqm(soqm)
                .owner(myEdit("dc2-po")));
        MockHttpSession session = login(soqm);
        User off = disable(myEdit("dc2-off"));

        MvcResult refused = callJson(session, post("/api/control-assignment"), "{\"controlId\":" + control.getId()
                + ",\"processOwner\":[\"" + off.getMail() + "\"]}");
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(refused)).contains(off.getMail(), "is deactivated");

        // The Control Operator deactivated later: the control can still be saved with them in the field
        disable(op);
        MvcResult kept = callJson(session, post("/api/control-assignment"), "{\"controlId\":" + control.getId()
                + ",\"controlOperator\":[\"" + op.getMail() + "\"]}");
        assertThat(kept.getResponse().getStatus()).as(body(kept)).isEqualTo(200);
    }

    @Test
    void theReturnReason_isShownAboveTheTabs_whileTheControlIsAtThatStep() throws Exception {
        User fac = myEdit("dc3-fac");
        User op = myEdit("dc3-op");
        Control control = control("DC3", "REVIEW", people().facilitator(fac).operator(op).soqm(soqm("dc3-soqm"))
                .owner(myEdit("dc3-po")));
        assertThat(status(login(op), post("/api/workflow/return-to-facilitator").param("controlId", String.valueOf(control.getId()))
                .param("comments", "Add the leavers too"))).isEqualTo(200);

        String page = body(call(login(fac), get("/view-control/{id}", control.getId())));
        assertThat(page).contains("id=\"returnNote\"", "Returned by the Control Operator", "Add the leavers too");
        // Sent on again: the note goes
        assertThat(status(login(fac), post("/api/workflow/move").param("controlId", String.valueOf(control.getId()))
                .param("targetStatus", "REVIEW").param("comments", ""))).isEqualTo(200);
        assertThat(body(call(login(op), get("/view-control/{id}", control.getId())))).doesNotContain("id=\"returnNote\"");
    }
}
