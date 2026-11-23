package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Rich text where it is shown (2026-11-23): the headers show the cleaned HTML, never a script; the pages carry the
 * editor; the completed-control Excel and the Changelog show plain text; an old plain text and the same text from the
 * editor are no change, so a person saving a field that is not theirs untouched is not refused.
 */
class RichTextDisplayIT extends ReleaseScenarioSupport {

    private static final String RICH = "<p><strong>Monthly</strong> review</p><ol><li data-list=\"bullet\">one</li></ol>";

    @Test
    void headersShowCleanedHtml_excelAndChangelogShowText() throws Exception {
        User soqm = soqm("rtd-soqm");
        MockHttpSession session = login(soqm);
        Control control = control("RTD", "COMPLETED", people().facilitator(myEdit("rtd-fac")).operator(myEdit("rtd-op"))
                .soqm(soqm).owner(myEdit("rtd-po")));
        assertThat(status(session, put("/api/controls/{id}", control.getId()).contentType("application/json")
                .content("{\"controlDescription\":\"" + RICH.replace("\"", "\\\"") + "<script>alert(1)</script>\","
                        + "\"editReason\":\"Formatting\"}"))).isEqualTo(200);
        assertThat(reload(control).getControlDescription()).isEqualTo(RICH);

        for (String page : new String[] {"/view-control/" + control.getId(), "/performance-cycle/" + control.getId()}) {
            String html = body(call(session, get(page)));
            assertThat(html).as(page).contains("class=\"ctl-desc-text rich-html\"", "<strong>Monthly</strong> review")
                    .doesNotContain("alert(1)");
        }
        String view = body(call(session, get("/view-control/{id}", control.getId())));
        assertThat(view).contains("/js/vendor/quill/quill.js", "/js/rich-text.js", "data-rich-text");

        MvcResult excel = call(session, get("/api/controls/{id}/export/completed", control.getId()));
        Map<String, String> cells = new HashMap<>();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(excel.getResponse().getContentAsByteArray()))) {
            for (Row row : book.getSheetAt(0)) {
                if (row.getCell(1) != null) {
                    cells.put(row.getCell(0).getStringCellValue(), row.getCell(1).getStringCellValue());
                }
            }
        }
        assertThat(cells.get("Control Description")).isEqualTo("Monthly review\n• one");

        String changelog = body(call(session, get("/api/controls/{id}/changelog", control.getId())));
        assertThat(changelog).contains("Monthly review").doesNotContain("<strong>");
    }

    @Test
    void anOldPlainText_savedUntouchedFromTheEditor_isNoChange_soNotRefused() throws Exception {
        User fac = myEdit("rtd2-fac");
        Control control = control("RTD2", "IN_PROGRESS", people().facilitator(fac).operator(myEdit("rtd2-op"))
                .soqm(soqm("rtd2-soqm")).owner(myEdit("rtd2-po")));
        // An old plain text in a field that is not the Facilitator's
        detailsRepository.save(detailsRepository.findByControlId(control.getId()).map(d -> {
            d.setProcessActivities("Line one\nLine two");
            return d;
        }).orElseThrow());
        MockHttpSession session = login(fac);
        // The page sends that field as the editor shows it, the Facilitator's own field changed
        MvcResult saved = callJson(session, post("/api/control-details"), "{\"controlId\":" + control.getId()
                + ",\"processActivities\":\"<p>Line one</p><p>Line two</p>\",\"controlStepsPerformed\":\"<p><em>Done</em></p>\"}");
        assertThat(saved.getResponse().getStatus()).as(body(saved)).isEqualTo(200);
        assertThat(detailsRepository.findByControlId(control.getId()).orElseThrow().getProcessActivities())
                .isEqualTo("Line one\nLine two");
        assertThat(detailsRepository.findByControlId(control.getId()).orElseThrow().getControlStepsPerformed())
                .isEqualTo("<p><em>Done</em></p>");
    }
}
