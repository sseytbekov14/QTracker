package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.ControlTextLimits;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Every free-text field of a control holds 20 000 characters (ControlTextLimits.MAX, 2026-11-20); 20 001 is refused
 * with the field's name; the long text survives the history (Changelog), a save on behalf of another role, an edit of
 * a completed control, Create Control and the Excel file.
 */
class ControlTextLimitsIT extends ReleaseScenarioSupport {

    private static final int MAX = ControlTextLimits.MAX;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everyField_holdsTwentyThousand_oneMoreIsRefusedByName() throws Exception {
        User soqm = soqm("tl-soqm");
        MockHttpSession session = login(soqm);
        Control control = control("TL", "IN_PROGRESS", people().soqm(soqm));
        for (String[] field : new String[][] {{"controlDescription", "Control Description"}, {"prp", "PRP"},
                {"referencesToControl", "References to Control"}}) {
            assertThat(status(session, put("/api/controls/{id}", control.getId()).contentType("application/json")
                    .content("{\"" + field[0] + "\":\"" + "c".repeat(MAX) + "\"}"))).as(field[0]).isEqualTo(200);
            MvcResult refused = callJson(session, put("/api/controls/{id}", control.getId()),
                    "{\"" + field[0] + "\":\"" + "c".repeat(MAX + 1) + "\"}");
            assertThat(refused.getResponse().getStatus()).as(field[0]).isEqualTo(400);
            assertThat(body(refused)).as(field[0]).contains(field[1] + " must be at most 20000 characters");
        }
        for (String[] field : new String[][] {{"processName", "Process Name"}, {"department", "Department"},
                {"processActivities", "Process Activities"}, {"controlOperatorReview", "Operator"},
                {"controlStepsPerformed", "Control Steps Performed"}, {"soqmHeadComments", "SoQM Admin Team Comments"},
                {"processOwnerComments", "Process Owner Comments"}}) {
            MvcResult ok = callJson(session, post("/api/control-details"),
                    "{\"controlId\":" + control.getId() + ",\"" + field[0] + "\":\"" + "t".repeat(MAX) + "\"}");
            assertThat(ok.getResponse().getStatus()).as(field[0] + " " + body(ok)).isEqualTo(200);
            MvcResult refused = callJson(session, post("/api/control-details"),
                    "{\"controlId\":" + control.getId() + ",\"" + field[0] + "\":\"" + "t".repeat(MAX + 1) + "\"}");
            assertThat(refused.getResponse().getStatus()).as(field[0]).isEqualTo(400);
            assertThat(body(refused)).as(field[0]).contains(field[1]).contains("at most 20000 characters");
        }
        for (String column : List.of("control_description", "prp", "references_to_control", "process_name", "department",
                "process_activities", "control_operator_review", "control_steps_performed", "soqm_head_comments",
                "process_owner_comments")) {
            assertThat(jdbc.queryForObject("SELECT LENGTH(" + column + ") FROM controls WHERE id = ?", Integer.class,
                    control.getId())).as(column).isEqualTo(MAX);
        }
        // The Changelog keeps the whole new value
        String changelog = body(call(session, get("/api/controls/{id}/changelog", control.getId())));
        assertThat(changelog).contains("t".repeat(MAX));
        // A new control too
        MvcResult created = callJson(session, post("/api/controls"), "{\"controlId\":\"TL-NEW-" + RUN + "\",\"component\":"
                + "\"Human Resources\",\"controlFrequency\":\"Monthly\",\"controlType\":\"Manual\",\"operatedBy\":\"Firm\","
                + "\"priority\":\"Medium\",\"nonAuditServicesApplicability\":\"No\",\"controlDescription\":\"" + "n".repeat(MAX) + "\"}");
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(200);
    }

    @Test
    void onBehalf_completedEdit_createControl_andExcel_keepTheLongText() throws Exception {
        User soqm = soqm("tl2-soqm");
        User op = myEdit("tl2-op");
        MockHttpSession session = login(soqm);
        // On behalf: SoQM Admin Team writes the operator's field in Review
        Control review = control("TL2", "REVIEW", people().facilitator(myEdit("tl2-fac")).operator(op).soqm(soqm)
                .owner(myEdit("tl2-po")));
        MvcResult onBehalf = callJson(session, post("/api/control-details"),
                "{\"controlId\":" + review.getId() + ",\"controlStepsPerformed\":\"" + "b".repeat(MAX) + "\"}");
        assertThat(onBehalf.getResponse().getStatus()).as(body(onBehalf)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT LENGTH(control_steps_performed) FROM controls WHERE id = ?", Integer.class,
                review.getId())).isEqualTo(MAX);

        // Completed, changed in place with a reason
        Control done = control("TL2", "COMPLETED", people().facilitator(myEdit("tl2-fac2")).operator(op).soqm(soqm)
                .owner(myEdit("tl2-po2")));
        assertThat(status(session, put("/api/controls/{id}", done.getId()).contentType("application/json")
                .content("{\"controlDescription\":\"" + "e".repeat(MAX) + "\",\"editReason\":\"Full text\"}"))).isEqualTo(200);
        MvcResult details = callJson(session, post("/api/control-details"), "{\"controlId\":" + done.getId()
                + ",\"processActivities\":\"" + "a".repeat(MAX) + "\",\"editReason\":\"Full text\"}");
        assertThat(details.getResponse().getStatus()).as(body(details)).isEqualTo(200);
        assertThat(reload(done).getControlDescription()).hasSize(MAX);

        // The Excel of the completed control: whole values in their cells
        MvcResult excel = call(session, get("/api/controls/{id}/export/completed", done.getId()));
        assertThat(excel.getResponse().getStatus()).isEqualTo(200);
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(excel.getResponse().getContentAsByteArray()))) {
            Sheet sheet = book.getSheetAt(0);
            boolean found = false;
            for (Row row : sheet) {
                Cell value = row.getCell(1);
                if (value != null && value.getStringCellValue().equals("e".repeat(MAX))) {
                    found = true;
                }
            }
            assertThat(found).as("the 20 000 characters of the description in one cell").isTrue();
        }

        // Create Control copies the long fields as they are
        MvcResult next = callJson(session, post("/api/controls/{id}/next-cycle", done.getId()),
                "{\"controlId\":\"" + done.getControlId() + "-NEXT\"}");
        assertThat(next.getResponse().getStatus()).as(body(next)).isEqualTo(200);
        long nextId = json(next).path("id").asLong();
        assertThat(jdbc.queryForObject("SELECT LENGTH(control_description) FROM controls WHERE id = ?", Integer.class, nextId))
                .isEqualTo(MAX);
        assertThat(jdbc.queryForObject("SELECT LENGTH(process_activities) FROM controls WHERE id = ?", Integer.class, nextId))
                .isEqualTo(MAX);
    }

    @Test
    void thePages_takeTheSameMaximum_forEveryFreeTextField_andTheCounters() throws Exception {
        User soqm = soqm("tl3-soqm");
        MockHttpSession session = login(soqm);
        Control control = control("TL3", "IN_PROGRESS", people().soqm(soqm));
        String view = body(call(session, get("/view-control/{id}", control.getId())));
        for (String name : List.of("controlDescription", "prp", "processName", "referencesToControl", "department",
                "processActivities", "controlOperatorReview", "controlStepsPerformed", "soqmHeadComments", "processOwnerComments")) {
            java.util.regex.Matcher field = java.util.regex.Pattern.compile("<(?:textarea|input)[^>]*name=\"" + name
                    + "\"[^>]*>").matcher(view);
            assertThat(field.find()).as(name).isTrue();
            String tag = field.group();
            assertThat(tag).as(name).contains("maxlength=\"" + MAX + "\"").doesNotContain("maxlength=\"2000\"");
        }
        String create = body(call(session, get("/new-control")));
        assertThat(create).contains("id=\"controlDescription-counter\">0 / " + MAX + "<", "id=\"prp-counter\">0 / " + MAX + "<");
        assertThat(create).doesNotContain("maxlength=\"1000\"");
    }
}
