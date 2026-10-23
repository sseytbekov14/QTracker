package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.ComponentControlsList;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The Performance Cycle page from the Action Centre's list: a row of an initiated control opens it (a draft opens View
 * Control), and its Export (the Excel of a completed control) is there for SoQM Admin Team only and only on a
 * completed control; everyone else gets neither the button nor the file (403).
 */
class PerformanceCycleIT extends ReleaseScenarioSupport {

    @Test
    void aListRow_opensThePerformanceCycle_onceInitiated_aDraftOpensViewControl() throws Exception {
        User soqm = soqm("pc-soqm");
        People people = people().soqm(soqm).facilitator(myEdit("pc-fac")).operator(myEdit("pc-op")).owner(myEdit("pc-po"));
        String prefix = "HR-PC-" + RUN.toUpperCase();
        Control draft = control(prefix, "DRAFT", people);
        Control running = control(prefix, "IN_PROGRESS", people);
        Control completed = control(prefix, "COMPLETED", people);
        MockHttpSession session = login(soqm);

        MvcResult page = call(session, get("/component/ALL").param("q", prefix).param("size", "50"));
        String html = body(page);
        Map<Long, String> hrefs = new LinkedHashMap<>();
        for (ComponentControlsList.Row row : list(session, "/component/ALL?q=" + prefix + "&size=50").rows()) {
            hrefs.put(row.id(), row.href());
        }
        Map<Long, String> expected = Map.of(draft.getId(), "/view-control/" + draft.getId(),
                running.getId(), "/performance-cycle/" + running.getId(),
                completed.getId(), "/performance-cycle/" + completed.getId());
        assertThat(hrefs).containsExactlyInAnyOrderEntriesOf(expected);
        for (String href : expected.values()) {
            // the Control ID link and the whole row (data-href) open the same page
            assertThat(html).contains("data-href=\"" + href + "\"").contains("href=\"" + href + "\"");
        }

        // The click opens the page: the cycle of the control, with "Open control" to View Control
        for (Control control : List.of(running, completed)) {
            MvcResult opened = call(session, get("/performance-cycle/" + control.getId()));
            assertThat(opened.getResponse().getStatus()).as(control.getControlId()).isEqualTo(200);
            assertThat(opened.getModelAndView().getViewName()).isEqualTo("performance-cycle");
            assertThat(body(opened)).contains(control.getControlId())
                    .contains("href=\"/view-control/" + control.getId() + "\"");
        }
        assertThat(call(session, get("/view-control/" + draft.getId())).getModelAndView().getViewName())
                .isEqualTo("view-control");
    }

    @Test
    void export_onlySoqmAdminTeam_onlyACompletedControl() throws Exception {
        User soqm = soqm("pcx-soqm");
        User facilitator = myEdit("pcx-fac");
        User owner = myEdit("pcx-po");
        User sharedReader = myRead("pcx-shared");
        User sharedEditor = myEdit("pcx-shared-edit");
        User allReader = allRead("pcx-allro");
        User allEditor = allEdit("pcx-alledit");
        User operator = myEdit("pcx-op");
        People people = people().soqm(soqm).facilitator(facilitator).operator(operator).owner(owner)
                .shared(sharedReader, sharedEditor);
        Control running = control("HR-PCX", "IN_PROGRESS", people);
        Control completed = control("HR-PCX", "COMPLETED", people);
        // The role KDN sees every KDN control, this one in its Process Owner field too
        User kdnUser = kdn("pcx-kdn");
        Control kdnCompleted = control("KDN-PCX", "COMPLETED", people().soqm(soqm).facilitator(facilitator)
                .operator(operator).owner(kdnUser));

        // SoQM Admin Team: the button and the file on a completed control
        MockHttpSession soqmSession = login(soqm);
        assertThat(body(call(soqmSession, get("/performance-cycle/" + completed.getId()))))
                .contains("id=\"exportCompletedBtn\"")
                .contains("href=\"/api/controls/" + completed.getId() + "/export/completed\"");
        MvcResult file = call(soqmSession, get("/api/controls/{id}/export/completed", completed.getId()));
        assertThat(file.getResponse().getStatus()).isEqualTo(200);
        assertThat(file.getResponse().getContentType()).contains("spreadsheetml");
        assertThat(excelRows(file)).containsEntry("Control ID", completed.getControlId())
                .containsEntry("Performance Status", "Completed");

        // ... and neither on a control that is not completed
        assertThat(body(call(soqmSession, get("/performance-cycle/" + running.getId()))))
                .doesNotContain("exportCompletedBtn").doesNotContain("/export/completed");
        MvcResult notCompleted = call(soqmSession, get("/api/controls/{id}/export/completed", running.getId()));
        assertThat(notCompleted.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(notCompleted)).contains("Control is not completed");

        // Everyone else who opens the page: no button, 403 for the file (Shared With too, decision of 2026-10-14)
        for (User user : List.of(facilitator, operator, owner, sharedReader, sharedEditor, allReader, allEditor)) {
            assertNoExport(user, completed);
        }
        assertNoExport(kdnUser, kdnCompleted);
        assertThat(call(soqmSession, get("/api/controls/{id}/export/completed", kdnCompleted.getId()))
                .getResponse().getStatus()).isEqualTo(200);
    }

    /** The page opens for the user, without the Download button; the file is refused (403). */
    private void assertNoExport(User user, Control completed) throws Exception {
        MockHttpSession session = login(user);
        MvcResult pageOfIt = call(session, get("/performance-cycle/" + completed.getId()));
        assertThat(pageOfIt.getResponse().getStatus()).as(user.getMail()).isEqualTo(200);
        assertThat(body(pageOfIt)).as(user.getMail()).doesNotContain("exportCompletedBtn").doesNotContain("/export/completed");
        MvcResult file = call(session, get("/api/controls/{id}/export/completed", completed.getId()));
        assertThat(file.getResponse().getStatus()).as(user.getMail()).isEqualTo(403);
        assertThat(file.getResponse().getContentType()).as(user.getMail()).doesNotContain("spreadsheetml");
    }

    private static Map<String, String> excelRows(MvcResult excel) throws Exception {
        Map<String, String> rows = new LinkedHashMap<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excel.getResponse().getContentAsByteArray()))) {
            for (Row row : workbook.getSheetAt(0)) {
                if (row.getCell(0) != null && row.getCell(1) != null) {
                    rows.putIfAbsent(row.getCell(0).getStringCellValue(), row.getCell(1).getStringCellValue());
                }
            }
        }
        return rows;
    }
}
