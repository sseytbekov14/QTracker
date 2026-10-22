package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.User;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Release test pass, stage I: the main pages read the controls a person sees with a fixed number of SQL
 * statements, not a few per control (specification 19: no N+1 on primary pages). 60 controls with four people each.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ReleaseScenarioQueriesIT extends ReleaseScenarioSupport {

    private static final int CONTROLS = 60;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void allComponentsTheDashboardAComponentListAndTheExport_readTheControlsWithAFixedNumberOfStatements()
            throws Exception {
        User me = myEdit("q-me");
        User lead = soqm("q-soqm");
        People people = people().facilitator(me).operator(myEdit("q-op")).soqm(lead).owner(myEdit("q-po"));
        for (int i = 0; i < CONTROLS; i++) {
            control("RS-Q", i % 2 == 0 ? "IN_PROGRESS" : "REVIEW", people);
        }
        MockHttpSession session = login(me);
        Map<String, Long> statements = new LinkedHashMap<>();
        statements.put("/component/ALL", count(session, get("/component/ALL")));
        statements.put("/component/ALL?status=YOUR_TURN", count(session, get("/component/ALL").param("status", "YOUR_TURN")));
        statements.put("/component/ALL/export (SoQM)", count(login(lead), get("/component/ALL/export")));
        statements.put("/", count(session, get("/")));
        statements.put("/component/HR", count(session, get("/component/HR")));
        // A fixed part (the person, the session, the lists of ids, the completion times) and no part per control
        statements.forEach((page, n) -> assertThat(n).as("%s: %s statements for %d controls", page, n, CONTROLS)
                .isLessThan(CONTROLS / 2));
    }

    /**
     * The dashboard's own charts of someone who sees every control read the controls' assignments in one query
     * (load test: one query per control, thousands per chart).
     */
    @Test
    void theOwnChartsOfAllControls_readTheAssignmentsWithAFixedNumberOfStatements() throws Exception {
        User me = allEdit("qc-all");
        People people = people().facilitator(me).operator(myEdit("qc-op")).soqm(soqm("qc-soqm")).owner(myEdit("qc-po"));
        for (int i = 0; i < CONTROLS; i++) {
            control("RS-QC", "IN_PROGRESS", people);
        }
        MockHttpSession session = login(me);
        for (String chart : java.util.List.of("/api/dashboard/my/frequency", "/api/dashboard/my/component")) {
            assertThat(count(session, get(chart))).as(chart).isLessThan(CONTROLS / 2);
        }
    }

    private long count(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertThat(call(session, request).getResponse().getStatus()).isEqualTo(200);
        return statistics.getPrepareStatementCount();
    }
}
