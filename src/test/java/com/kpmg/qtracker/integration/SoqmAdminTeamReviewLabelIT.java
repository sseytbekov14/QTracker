package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.enums.WorkflowActionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The SoQM step is called "SoQM Admin Team Review" (2026-11-10, earlier "SoQM Head Review"): on View Control, on
 * Performance cycle and in the list with its status filter. The stored status stays SOQM_HEAD_REVIEW.
 */
class SoqmAdminTeamReviewLabelIT extends ReleaseScenarioSupport {

    @Autowired
    JdbcTemplate jdbc;

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

    /**
     * What was saved before the rename keeps its text - an audit entry "Move to SoQM Head Review: ..." - and is shown
     * under today's name: on the Audit trail (also found by it), in its details, and in the control's history, where
     * the steps are names, not codes.
     */
    @Test
    void whatWasSavedBeforeTheRename_isShownUnderTodaysName() throws Exception {
        User soqm = soqm("satr2-soqm");
        MockHttpSession session = login(soqm);
        Control control = control("HR-SATR2", "PROCESS_OWNER_REVIEW", people().facilitator(myEdit("satr2-fac"))
                .operator(myEdit("satr2-op")).soqm(soqm).owner(myEdit("satr2-po")));
        String old = "Move to SoQM Head Review: Review -> SoQM Head Review";
        jdbc.update("INSERT INTO admin_audit_log (admin_email, admin_name, action_type, control_id, control_control_id,"
                        + " action_description, changed_fields, previous_values, new_values, created_at)"
                        + " VALUES (?, ?, 'WORKFLOW_MOVE', ?, ?, ?, '[\"Performance Status\"]',"
                        + " '{\"Performance Status\":\"REVIEW\"}', '{\"Performance Status\":\"SOQM_HEAD_REVIEW\"}', ?)",
                soqm.getMail(), soqm.getDisplayName(), control.getId(), control.getControlId(), old,
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)));
        WorkflowHistory history = new WorkflowHistory();
        history.setControlId(control.getId());
        history.setActionType(WorkflowActionType.SUBMIT_TO_PROCESS_OWNER);
        history.setFromStep("SOQM_HEAD_REVIEW");
        history.setToStep("PROCESS_OWNER_REVIEW");
        history.setCreatedAt(LocalDateTime.now().minusMinutes(4));
        historyRepository.save(history);

        // Audit trail: the text and its details by today's name; searched by today's name, it is found
        String audit = body(call(session, get("/admin/audit").param("q", control.getControlId()))).replaceAll("\\s+", " ");
        assertThat(audit).contains("Move to SoQM Admin Team Review: Review -&gt; SoQM Admin Team Review")
                .contains("is-before\">Review</td> <td class=\"is-after\">SoQM Admin Team Review</td>")
                .doesNotContain("SoQM Head", "SOQM_HEAD_REVIEW");
        assertThat(body(call(session, get("/admin/audit").param("q", "admin team review: review"))))
                .contains(control.getControlId());
        // The control's history: names, not codes
        String changelog = body(call(session, get("/api/controls/{id}/changelog", control.getId())));
        assertThat(changelog).contains("\"fromStep\":\"SoQM Admin Team Review\"", "\"toStep\":\"Process Owner Review\"")
                .doesNotContain("SOQM_HEAD_REVIEW", "SoQM Head");
        // Stored as it was
        assertThat(jdbc.queryForObject("SELECT action_description FROM admin_audit_log WHERE control_id = ?", String.class,
                control.getId())).isEqualTo(old);
    }
}
