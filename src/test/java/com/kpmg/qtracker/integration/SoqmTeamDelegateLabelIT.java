package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Business decision of 2026-10-14: the assignment field stored as soqm_lead is called "SoQM Team / Delegate" wherever
 * a person sees it - the Assignment tab, the Changelog, the Excel of a list and of one control, the column of the
 * component lists and the KDN page, the Performance Cycle page, the messages. The role keeps its name, SoQM Admin Team.
 */
class SoqmTeamDelegateLabelIT extends ReleaseScenarioSupport {

    private static final String LABEL = "SoQM Team / Delegate";

    @Test
    void theField_isCalledSoqmTeamDelegate_everywhereAPersonSeesIt() throws Exception {
        User soqm = soqm("lbl-soqm");
        User otherSoqm = soqm("lbl-soqm2");
        People people = people().soqm(soqm).facilitator(myEdit("lbl-fac")).operator(myEdit("lbl-op"))
                .owner(myEdit("lbl-po"));
        Control completed = control("KDN-LBL", "COMPLETED", people);
        MockHttpSession session = login(soqm);

        // The Assignment tab: the label and the picker's placeholder
        String viewControl = body(call(session, get("/view-control/" + completed.getId())));
        assertThat(viewControl).contains(">" + LABEL + "</label>", "placeholder=\"Select " + LABEL + "...\"")
                .doesNotContain("SoQM Lead", "Select SoQM Team...");
        // The Performance Cycle page
        assertThat(body(call(session, get("/performance-cycle/" + completed.getId()))))
                .contains("<dt>" + LABEL + "</dt>").doesNotContain("<dt>SoQM Team</dt>");
        // The column of a component list and of the KDN page, on screen and as the phone card's label
        for (String list : List.of("/component/ALL", "/kdn")) {
            assertThat(body(call(session, get(list).param("q", completed.getControlId()))))
                    .as(list).contains(">" + LABEL + "<", "data-label=\"" + LABEL + "\"").doesNotContain("SoQM Lead");
        }
        // The Excel of a list and of the completed control
        for (String export : List.of("/component/ALL/export?q=" + completed.getControlId(),
                "/kdn/export?q=" + completed.getControlId(), "/api/controls/" + completed.getId() + "/export/completed")) {
            assertThat(cells(call(session, get(export)))).as(export).contains(LABEL).doesNotContain("SoQM Lead / Delegate");
        }

        // The Changelog of a change of the field (on a completed control, with a reason)
        MvcResult changed = callJson(session, post("/api/control-assignment").contentType(MediaType.APPLICATION_JSON),
                "{\"controlId\":" + completed.getId() + ",\"soqmLead\":[\"" + otherSoqm.getMail() + "\"],"
                        + "\"editReason\":\"Another delegate\"}");
        assertThat(changed.getResponse().getStatus()).as(body(changed)).isEqualTo(200);
        List<String> fields = new ArrayList<>();
        json(call(session, get("/api/controls/{id}/changelog", completed.getId())))
                .forEach(entry -> entry.path("fieldChanges").forEach(change -> fields.add(change.path("field").asText())));
        assertThat(fields).contains(LABEL).doesNotContain("SoQM Head/Lead", "SoQM Team");

        // The message when moving on to the SoQM review with nobody in the field
        Control noDelegate = control("HR-LBL", "REVIEW", people().facilitator(myEdit("lbl-fac2")).operator(myEdit("lbl-op2")));
        MvcResult refused = call(session, post("/api/workflow/move").param("controlId", String.valueOf(noDelegate.getId()))
                .param("targetStatus", "SOQM_HEAD_REVIEW").param("comments", "On behalf"));
        assertThat(body(refused)).contains(LABEL + " is not assigned. Please assign a " + LABEL + " first.");

        // Deleting a user: the controls not completed where they still are, and in which field
        control("HR-LBL", "IN_PROGRESS", people().soqm(otherSoqm));
        assertThat(body(call(session, post("/api/users/{id}/delete-check", otherSoqm.getId())))).contains(LABEL);
    }

    private static List<String> cells(MvcResult excel) throws Exception {
        assertThat(excel.getResponse().getStatus()).isEqualTo(200);
        List<String> texts = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excel.getResponse().getContentAsByteArray()))) {
            for (Row row : workbook.getSheetAt(0)) {
                for (Cell cell : row) {
                    texts.add(cell.toString());
                }
            }
        }
        return texts;
    }
}
