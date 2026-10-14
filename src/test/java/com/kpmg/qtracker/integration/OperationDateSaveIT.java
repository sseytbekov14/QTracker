package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.ControlScheduleCalculator;
import com.kpmg.qtracker.service.SoqmYear;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The Control Operation Date picked on the Assignment tab and saved with Save, as View Control sends it: the Control
 * tab (PUT /api/controls/{id}), the Assignment tab, Details, Documents, one after the other. The date reaches the
 * database, GET /api/control-assignment and the page give it back after a reload, in DRAFT and every later status
 * before COMPLETED. Before 0490b23 (D-04) the Control loaded by the assignment save wrote its old date (none on a
 * draft) back over the new one: the field came back empty while the deadline was calculated.
 */
class OperationDateSaveIT extends ReleaseScenarioSupport {

    @Test
    void aDraftWithoutADate_todaySavedWithSave_isStored_andComesBackAfterAReload() throws Exception {
        People people = new People();
        Control control = people.draftWithoutADate();
        assertThat(stored(control).getControlOperationDate()).isNull();

        MockHttpSession session = login(people.soqm);
        pageSave(session, control, today(), people);

        ControlAssignment stored = stored(control);
        assertThat(stored.getControlOperationDate()).isEqualTo(today());
        assertThat(stored.getControlOperationDeadline()).isEqualTo(ControlScheduleCalculator.DEADLINE.from(today()));
        assertThat(reload(control).getControlOperationDate()).isEqualTo(today());
        assertThat(reload(control).getDeadline()).isEqualTo(ControlScheduleCalculator.DEADLINE.from(today()));
        assertThat(reload(control).getPerformanceStatus()).isEqualTo("DRAFT");

        JsonNode read = json(call(session, get("/api/control-assignment")
                .param("controlId", String.valueOf(control.getId()))));
        assertThat(read.path("controlOperationDate").asText()).isEqualTo(today().toString());
        assertThat(read.path("controlOperationDeadline").asText()).isEqualTo(ControlScheduleCalculator.DEADLINE.from(today()).toString());
        MvcResult page = call(session, get("/view-control/" + control.getId()));
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        // The rule under the deadline field comes from ControlScheduleCalculator.DEADLINE
        assertThat(body(page)).contains("id=\"controlOperationDeadlineHint\"")
                .contains("10 working days (Monday to Friday) after the Control Operation Date");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW"})
    void anotherDate_savedWithSave_replacesTheStoredOne_inEveryStatusBeforeCompleted(String status) throws Exception {
        People people = new People();
        Control control = people.draftWithoutADate();
        MockHttpSession session = login(people.soqm);
        pageSave(session, control, today().plusDays(3), people);
        Control moved = reload(control);
        moved.setPerformanceStatus(status);
        controlRepository.save(moved);

        pageSave(session, control, today().plusDays(10), people);

        assertThat(stored(control).getControlOperationDate()).as(status).isEqualTo(today().plusDays(10));
        assertThat(stored(control).getControlOperationDeadline()).as(status).isEqualTo(ControlScheduleCalculator.DEADLINE.from(today().plusDays(10)));
        JsonNode read = json(call(session, get("/api/control-assignment")
                .param("controlId", String.valueOf(control.getId()))));
        assertThat(read.path("controlOperationDate").asText()).as(status).isEqualTo(today().plusDays(10).toString());
    }

    @Test
    void aRefusedSave_storesNoDate_andTheNextSaveStoresIt() throws Exception {
        People people = new People();
        Control control = people.draft(false);
        MockHttpSession session = login(people.soqm);

        // No Facilitator: the page stops before any request; a request sent anyway is refused and changes nothing
        MvcResult refused = callJson(session, post("/api/control-assignment"), "{\"controlId\":" + control.getId()
                + ",\"facilitator\":[],\"controlOperationDate\":\"" + today() + "\"}");
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(refused)).contains("Facilitator is required");
        assertThat(stored(control).getControlOperationDate()).isNull();

        // A date that is not ISO (the page sends ISO only) is refused too
        MvcResult notIso = callJson(session, post("/api/control-assignment"), "{\"controlId\":" + control.getId()
                + ",\"controlOperationDate\":\"13.10.2026\"}");
        assertThat(notIso.getResponse().getStatus()).isEqualTo(400);
        assertThat(stored(control).getControlOperationDate()).isNull();

        pageSave(session, control, today(), people);
        assertThat(stored(control).getControlOperationDate()).isEqualTo(today());
    }

    /** The four requests of the Save button, in its order, each answered 200. */
    private void pageSave(MockHttpSession session, Control control, LocalDate date, People people) throws Exception {
        expectOk(callJson(session, put("/api/controls/" + control.getId()), "{\"controlFrequency\":\"Monthly\","
                + "\"controlCategory\":\"Manual\",\"controlType\":\"Preventive\",\"component\":\"HR\","
                + "\"operatedBy\":\"Finance\",\"controlStatus\":null,\"priority\":\"High\","
                + "\"nonAuditServicesApplicability\":\"No\",\"soqmYear\":null,"
                + "\"controlDescription\":\"Synthetic draft\",\"prp\":\"PRP\",\"editReason\":null}"));
        expectOk(callJson(session, post("/api/control-assignment"), "{\"controlId\":" + control.getId()
                + ",\"facilitator\":[\"" + people.fac.getMail() + "\"],\"controlOperator\":[\"" + people.op.getMail()
                + "\"],\"soqmLead\":[\"" + people.soqm.getMail() + "\"],\"processOwner\":[\"" + people.po.getMail() + "\"]"
                + ",\"controlSharedWith\":null,\"controlOperationDate\":\"" + date + "\",\"editReason\":null}"));
        expectOk(callJson(session, post("/api/control-details"), "{\"controlId\":" + control.getId()
                + ",\"processName\":\"Payroll\",\"processActivities\":\"Payroll run\",\"editReason\":null}"));
        expectOk(callJson(session, post("/api/control-documents"), "{\"controlId\":" + control.getId()
                + ",\"soqmDevelopmentMaterials\":\"\",\"editReason\":null}"));
    }

    private static void expectOk(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as("%s %s: %s", result.getRequest().getMethod(),
                result.getRequest().getRequestURI(), body(result)).isEqualTo(200);
    }

    private ControlAssignment stored(Control control) {
        return assignmentRepository.findByControlId(control.getId()).orElseThrow();
    }

    /** SoQM Team and the people of a draft created on the New Control page, without a date. */
    private final class People {
        final User soqm = soqm("odsave-soqm");
        final User fac = myEdit("odsave-fac");
        final User op = myEdit("odsave-op");
        final User po = myEdit("odsave-po");

        Control draftWithoutADate() throws Exception {
            return draft(true);
        }

        Control draft(boolean withPeople) throws Exception {
            String controlId = "HR-CTRL-ODS-" + next() + "/FY27/" + RUN;
            MockHttpSession session = login(soqm);
            expectOk(callJson(session, post("/api/controls"), "{\"controlId\":\"" + controlId + "\","
                    + "\"controlFrequency\":\"Monthly\",\"controlCategory\":\"Manual\",\"controlType\":\"Preventive\","
                    + "\"component\":\"HR\",\"operatedBy\":\"Finance\",\"priority\":\"High\","
                    + "\"nonAuditServicesApplicability\":\"No\",\"controlDescription\":\"Synthetic draft\","
                    + "\"prp\":\"PRP\",\"soqmYear\":\"" + SoqmYear.current(today()) + "\""
                    + (withPeople ? people() : "") + "}"));
            Control control = controlRepository.findByControlId(controlId).orElseThrow();
            assertThat(control.getPerformanceStatus()).isEqualTo("DRAFT");
            return control;
        }

        private String people() {
            return ",\"facilitator\":[\"" + fac.getMail() + "\"],\"controlOperator\":[\"" + op.getMail()
                    + "\"],\"soqmLead\":[\"" + soqm.getMail() + "\"],\"processOwner\":[\"" + po.getMail() + "\"]";
        }
    }
}
