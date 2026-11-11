package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A Control ID is taken in any case (2026-11-11): New Control and Rename ID took "hr-1" beside "HR-1", while Create
 * Control (next cycle) already refused it. A control may still change only the case of its own ID.
 */
class ControlIdAnyCaseIT extends ReleaseScenarioSupport {

    private static final String NEW_CONTROL = "{\"controlId\":\"%s\",\"component\":\"Human Resources\","
            + "\"controlFrequency\":\"Monthly\",\"controlType\":\"Manual\",\"operatedBy\":\"Firm\",\"priority\":\"Medium\","
            + "\"nonAuditServicesApplicability\":\"No\",\"controlDescription\":\"Any case\"}";

    @Test
    void anIdInAnotherCase_isTaken_onNewControl_theLiveCheck_andRenameId() throws Exception {
        User soqm = soqm("cic-soqm");
        MockHttpSession session = login(soqm);
        Control taken = control("HR-CIC", "DRAFT", people().soqm(soqm));
        Control other = control("HR-CIC-OTHER", "DRAFT", people().soqm(soqm));
        String lower = taken.getControlId().toLowerCase();

        // New Control: refused, nothing made
        long before = controlRepository.count();
        assertThat(status(session, post("/api/controls").contentType("application/json")
                .content(NEW_CONTROL.formatted(lower)))).isEqualTo(409);
        assertThat(status(session, post("/api/controls").contentType("application/json")
                .content(NEW_CONTROL.formatted("  " + lower + "  ")))).isIn(400, 409);
        assertThat(controlRepository.count()).isEqualTo(before);
        // The live check of the forms
        assertThat(json(call(session, get("/api/controls/check-id-unique").param("controlId", lower)))
                .path("unique").asBoolean()).isFalse();
        assertThat(json(call(session, get("/api/controls/check-id-unique").param("controlId", lower + "-free")))
                .path("unique").asBoolean()).isTrue();
        // Rename ID: another control may not take it in any case
        int refused = status(session, post("/api/controls/{id}/rename-id", other.getId()).contentType("application/json")
                .content("{\"newControlId\":\"" + lower + "\",\"comment\":\"x\"}"));
        assertThat(refused).isGreaterThanOrEqualTo(400);
        assertThat(reload(other).getControlId()).isEqualTo(other.getControlId());
        // ... the control itself may change the case of its own ID
        assertThat(status(session, post("/api/controls/{id}/rename-id", taken.getId()).contentType("application/json")
                .content("{\"newControlId\":\"" + lower + "\",\"comment\":\"Lower case\"}"))).isEqualTo(200);
        assertThat(reload(taken).getControlId()).isEqualTo(lower);
    }
}
