package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The deadline of 10 working days (ControlScheduleCalculator.DEADLINE) from an operation date on a weekend, through
 * everything that reads it: both stored columns, GET /api/control-assignment, the dashboard calendar, View Control
 * and the Excel of a completed control (the Assignment tab's deadline). The dates lie in 2031, so the control is never overdue when the test runs.
 */
class DeadlineWorkingDaysIT extends ReleaseScenarioSupport {

    private static final LocalDate SATURDAY = LocalDate.of(2031, 5, 17);
    private static final LocalDate SUNDAY = LocalDate.of(2031, 5, 18);
    private static final LocalDate MONDAY = LocalDate.of(2031, 5, 19);
    private static final LocalDate FRIDAY_DEADLINE = LocalDate.of(2031, 5, 30);
    private static final LocalDate MONDAY_DEADLINE = LocalDate.of(2031, 6, 2);

    @Test
    void aSaturday_isDueOnTheFridayTwoWeeksLater_everywhereTheDeadlineIsShown() throws Exception {
        User soqm = soqm("dwd-soqm");
        Control control = control("HR-DWD", "IN_PROGRESS", people().soqm(soqm).facilitator(myEdit("dwd-fac"))
                .operator(myEdit("dwd-op")).owner(myEdit("dwd-po")));
        MockHttpSession session = login(soqm);

        // The page's own deadline and next date are not taken
        saveDate(session, control, SATURDAY, "{\"controlOperationDeadline\":\"2031-05-31\","
                + "\"nextControlOperationDate\":\"2031-05-18\"}");
        assertStored(control, FRIDAY_DEADLINE, SATURDAY.plusMonths(1));
        JsonNode read = json(call(session, get("/api/control-assignment")
                .param("controlId", String.valueOf(control.getId()))));
        assertThat(read.path("controlOperationDeadline").asText()).isEqualTo(FRIDAY_DEADLINE.toString());

        // Dashboard calendar: the event is on the Friday
        JsonNode events = json(call(session, get("/api/dashboard/deadline-calendar")
                .param("start", "2031-05-01").param("end", "2031-07-01")));
        assertThat(eventDays(events, control.getControlId())).containsExactly(FRIDAY_DEADLINE.toString());

        // View Control: the deadline in the facts and the rule under the field
        String page = body(call(session, get("/view-control/" + control.getId())));
        assertThat(page).contains("30.05.2031")
                .contains("10 working days (Monday to Friday) after the Control Operation Date");

        // Another frequency: recalculated, the same Friday, the next date of the new frequency
        assertThat(call(session, put("/api/controls/" + control.getId()).contentType("application/json")
                .content(controlForm("Annual"))).getResponse().getStatus()).isEqualTo(200);
        assertStored(control, FRIDAY_DEADLINE, SATURDAY.plusYears(1));

        // Another date: Sunday counts from the same Monday, Monday from the Tuesday after it
        saveDate(session, control, SUNDAY, "{}");
        assertStored(control, FRIDAY_DEADLINE, SUNDAY.plusYears(1));
        saveDate(session, control, MONDAY, "{}");
        assertStored(control, MONDAY_DEADLINE, MONDAY.plusYears(1));

        // The Excel of the completed control prints the Assignment tab's dates
        Control completed = reload(control);
        completed.setPerformanceStatus("COMPLETED");
        controlRepository.save(completed);
        MvcResult excel = call(session, get("/api/controls/{id}/export/completed", control.getId()));
        assertThat(excel.getResponse().getStatus()).isEqualTo(200);
        Map<String, String> rows = excelRows(excel);
        assertThat(rows).containsEntry("Control Operation Date", "19.05.2031")
                .containsEntry("Control Operation Deadline", "02.06.2031");
    }

    private void saveDate(MockHttpSession session, Control control, LocalDate date, String extraJson) throws Exception {
        String extra = extraJson.substring(1, extraJson.length() - 1);
        MvcResult result = callJson(session, post("/api/control-assignment"), "{\"controlId\":" + control.getId()
                + ",\"controlOperationDate\":\"" + date + "\"" + (extra.isBlank() ? "" : "," + extra) + "}");
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
    }

    private void assertStored(Control control, LocalDate deadline, LocalDate nextDate) {
        ControlAssignment stored = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(stored.getControlOperationDeadline()).isEqualTo(deadline);
        assertThat(stored.getNextControlOperationDate()).isEqualTo(nextDate);
        assertThat(reload(control).getDeadline()).isEqualTo(deadline);
    }

    private static String controlForm(String frequency) {
        return "{\"controlFrequency\":\"" + frequency + "\",\"controlCategory\":\"Manual\",\"controlType\":\"Preventive\","
                + "\"component\":\"HR\",\"operatedBy\":\"Finance\",\"controlStatus\":null,\"priority\":\"High\","
                + "\"nonAuditServicesApplicability\":\"No\",\"soqmYear\":null,"
                + "\"controlDescription\":\"Release test control\",\"prp\":\"PRP\",\"editReason\":null}";
    }

    private static java.util.List<String> eventDays(JsonNode events, String controlId) {
        java.util.List<String> days = new java.util.ArrayList<>();
        for (JsonNode event : events) {
            if (event.path("title").asText().contains(controlId)) {
                days.add(event.path("start").asText());
            }
        }
        return days;
    }

    private static Map<String, String> excelRows(MvcResult excel) throws Exception {
        Map<String, String> rows = new LinkedHashMap<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excel.getResponse().getContentAsByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getCell(0) != null && row.getCell(1) != null) {
                    rows.putIfAbsent(row.getCell(0).getStringCellValue(), row.getCell(1).getStringCellValue());
                }
            }
        }
        return rows;
    }
}
