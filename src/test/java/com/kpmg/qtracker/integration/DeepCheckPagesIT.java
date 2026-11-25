package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Found by the deep check of 2026-11-25 on the other pages: "Awaiting my action" links to the same list for everyone;
 * the Control ID helpers are SoQM's; an ID sent with spaces is kept without them and still unique in any case.
 */
class DeepCheckPagesIT extends ReleaseScenarioSupport {

    private static final Pattern VIEW_ALL = Pattern.compile("<a[^>]*class=\"action-queue-all\"[^>]*>");
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void awaitingMyAction_linksToTheYourTurnList_forSoqmToo() throws Exception {
        User soqm = soqm("dp2-soqm");
        for (int i = 0; i < 12; i++) {
            control("DP2-" + i, "SOQM_HEAD_REVIEW", people().facilitator(myEdit("dp2-fac" + i)).operator(myEdit("dp2-op" + i))
                    .soqm(soqm).owner(myEdit("dp2-po" + i)));
        }
        String dashboard = body(call(login(soqm), get("/")));
        // the "View all (N)" link of Awaiting my action: the Your turn list, as its count
        Matcher link = VIEW_ALL.matcher(dashboard);
        assertThat(link.find()).as("View all link").isTrue();
        Matcher href = HREF.matcher(link.group());
        assertThat(href.find()).isTrue();
        assertThat(href.group(1)).isEqualTo("/component/ALL?status=YOUR_TURN");
    }

    @Test
    void theIdHelpers_areSoqmOnly_anIdIsKeptWithoutSpaces_andUniqueInAnyCase() throws Exception {
        User soqm = soqm("dp3-soqm");
        MockHttpSession soqmSession = login(soqm);
        MockHttpSession participant = login(myEdit("dp3-user"));
        assertThat(status(participant, get("/api/controls/check-id-unique").param("controlId", "KDN-ANY"))).isEqualTo(403);
        assertThat(status(participant, get("/api/controls/generate-id").param("component", "HR").param("frequency", "Monthly")))
                .isEqualTo(403);
        assertThat(status(soqmSession, get("/api/controls/check-id-unique").param("controlId", "KDN-ANY"))).isEqualTo(200);

        String id = "DP3-" + RUN;
        MvcResult created = callJson(soqmSession, post("/api/controls"), "{\"controlId\":\"  " + id + "  \",\"component\":\"Human Resources\","
                + "\"controlFrequency\":\"Monthly\",\"controlType\":\"Manual\",\"operatedBy\":\"Firm\",\"priority\":\"Medium\","
                + "\"nonAuditServicesApplicability\":\"No\",\"controlDescription\":\"x\"}");
        assertThat(created.getResponse().getStatus()).as(body(created)).isIn(200, 201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM controls WHERE control_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(json(call(soqmSession, get("/api/controls/check-id-unique").param("controlId", " " + id.toLowerCase() + " ")))
                .path("unique").asBoolean()).isFalse();
    }
}
