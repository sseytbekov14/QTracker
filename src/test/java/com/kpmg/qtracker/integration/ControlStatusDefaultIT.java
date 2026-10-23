package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.ComponentControlsList;
import com.kpmg.qtracker.service.SoqmYear;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Control Status of a new control (business decision of 2026-10-22, the old system's default): New Control - the page
 * posts to /api/controls - stores Active when none is given, keeps a given choice and refuses any other value with
 * 400 and nothing created. Create Control carries it to the next cycle, the Excel of a completed control shows it,
 * an empty value reads "Not set" in View Control, and Deleted / Superseded keep their badge and stop Create Control.
 */
class ControlStatusDefaultIT extends ReleaseScenarioSupport {

    private User soqm;
    private MockHttpSession session;

    @BeforeEach
    void signIn() throws Exception {
        soqm = soqm("cs-soqm");
        session = login(soqm);
    }

    private static String uniqueId(String prefix) {
        return prefix + "-" + next() + "-" + RUN.toUpperCase(Locale.ROOT);
    }

    /** The New Control page's body; controlStatus left out when null. */
    private String newControl(String controlId, String controlStatus) {
        ObjectNode json = objectMapper.createObjectNode().put("controlId", controlId).put("controlFrequency", "Monthly")
                .put("controlCategory", "Manual").put("controlType", "Preventive").put("component", "HR")
                .put("operatedBy", "Finance").put("priority", "High").put("nonAuditServicesApplicability", "No")
                .put("controlDescription", "New").put("prp", "PRP").put("soqmYear", SoqmYear.current(today()));
        if (controlStatus != null) {
            json.put("controlStatus", controlStatus);
        }
        return json.toString();
    }

    private Control create(String controlId, String controlStatus) throws Exception {
        MvcResult result = callJson(session, post("/api/controls"), newControl(controlId, controlStatus));
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return controlRepository.findById(json(result).get("id").asLong()).orElseThrow();
    }

    @Test
    void newControl_withoutAStatus_isActive_aChoiceIsKept_anyOtherValueIs400AndNothingIsCreated() throws Exception {
        assertThat(create(uniqueId("HR-CS"), null).getControlStatus()).isEqualTo("ACTIVE");
        assertThat(create(uniqueId("HR-CS"), "  ").getControlStatus()).isEqualTo("ACTIVE");
        assertThat(create(uniqueId("HR-CS"), "Superseded").getControlStatus()).isEqualTo("SUPERSEDED");
        assertThat(create(uniqueId("HR-CS"), "not applicable").getControlStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(create(uniqueId("HR-CS"), "DELETED").getControlStatus()).isEqualTo("DELETED");

        for (String wrong : new String[] {"Retired", "IN_PROGRESS", "Activ"}) {
            String id = uniqueId("HR-CS");
            long before = controlRepository.count();
            MvcResult refused = callJson(session, post("/api/controls"), newControl(id, wrong));

            assertThat(refused.getResponse().getStatus()).as(wrong).isEqualTo(400);
            JsonNode answer = json(refused);
            assertThat(answer.get("error").asText()).isEqualTo("INVALID_CONTROL_STATUS");
            assertThat(answer.get("message").asText())
                    .isEqualTo("Control Status must be one of Not Applicable, Active, Deleted, Superseded");
            assertThat(controlRepository.existsByControlId(id)).as(wrong).isFalse();
            assertThat(controlRepository.count()).as(wrong).isEqualTo(before);
        }
    }

    @Test
    void createControl_carriesActiveToTheNextCycle() throws Exception {
        Control source = runningSource(create(uniqueId("HR-CS") + "/FY27/Central/OCT", null));

        MvcResult created = callJson(session, post("/api/controls/{id}/next-cycle", source.getId()), "{}");

        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(200);
        Control next = controlRepository.findById(json(created).get("id").asLong()).orElseThrow();
        assertThat(next.getControlId()).endsWith("/NOV");
        assertThat(next.getControlStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void anEmptyStatus_readsNotSet_inViewControl_theChoicesStayAsTheyAre() throws Exception {
        Control empty = control("HR-CS", "IN_PROGRESS", people().soqm(soqm), c -> c.setControlStatus(null));
        Control active = control("HR-CS", "IN_PROGRESS", people().soqm(soqm));

        String page = body(call(session, get("/view-control/{id}", empty.getId())));

        assertThat(page).contains("<option value=\"\" data-view-text=\"Not set\" data-edit-text=\"Select Status\">"
                + "Not set</option>");
        assertThat(page).contains("<option value=\"ACTIVE\">Active</option>")
                .contains("<option value=\"SUPERSEDED\">Superseded</option>")
                .doesNotContain(">Select Status</option>");
        assertThat(body(call(session, get("/view-control/{id}", active.getId()))))
                .contains("<option value=\"ACTIVE\" selected=\"selected\">Active</option>");
    }

    @Test
    void deletedAndSuperseded_keepTheirBadge_andStopCreateControl() throws Exception {
        for (String status : new String[] {"Deleted", "Superseded"}) {
            Control retired = runningSource(create(uniqueId("HR-CS") + "/FY27/Central/OCT", status));
            long before = controlRepository.count();

            assertThat(listRows(session, "/component/ALL?q=" + retired.getControlId().substring(0, 12)))
                    .filteredOn(row -> row.id().equals(retired.getId()))
                    .singleElement().extracting(ComponentControlsList.Row::controlStatusBadge).isEqualTo(status);
            MvcResult preview = call(session, get("/api/controls/{id}/next-cycle", retired.getId()));
            assertThat(json(preview).get("message").asText())
                    .isEqualTo("This control is " + status + ": it has no next control");
            MvcResult refused = callJson(session, post("/api/controls/{id}/next-cycle", retired.getId()), "{}");
            assertThat(refused.getResponse().getStatus()).as(status).isEqualTo(400);
            assertThat(controlRepository.count()).as(status).isEqualTo(before);
        }
    }

    @Test
    void theExcelOfACompletedNewControl_saysActive() throws Exception {
        Control control = create(uniqueId("HR-CS"), null);
        control.setPerformanceStatus("COMPLETED");
        controlRepository.save(control);

        MvcResult file = call(session, get("/api/controls/{id}/export/completed", control.getId()));

        assertThat(file.getResponse().getStatus()).isEqualTo(200);
        assertThat(excelRows(file)).containsEntry("Control ID", control.getControlId())
                .containsEntry("Control Status", "Active");
    }

    /** A control made by New Control, given people and dates and made In Progress, as Initiate would. */
    private Control runningSource(Control created) {
        User facilitator = myEdit("cs-fac");
        User operator = myEdit("cs-op");
        User owner = myEdit("cs-po");
        ControlAssignment assignment = assignmentRepository.findByControlId(created.getId()).orElseThrow();
        assignment.setFacilitator(facilitator.getMail());
        assignment.setControlOperator(operator.getMail());
        assignment.setSoqmLead(soqm.getMail());
        assignment.setProcessOwner(owner.getMail());
        assignment.setControlOperationDate(LocalDate.of(2026, 10, 12));
        assignment.setNextControlOperationDate(LocalDate.of(2026, 11, 10));
        assignmentRepository.save(assignment);
        ControlDetails details = detailsRepository.findByControlId(created.getId()).orElseThrow();
        details.setProcessActivities("Activities");
        detailsRepository.save(details);
        Control control = reload(created);
        control.setPerformanceStatus("IN_PROGRESS");
        return controlRepository.save(control);
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
