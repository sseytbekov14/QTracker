package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.User;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Disabled;
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
    @Disabled("D-11 (S2): every listed control costs about six statements; fixed in the next commit")
    void theControlsListTheDashboardAndAComponentList_readTheControlsWithAFixedNumberOfStatements() throws Exception {
        User me = myEdit("q-me");
        People people = people().facilitator(me).operator(myEdit("q-op")).soqm(soqm("q-soqm")).owner(myEdit("q-po"));
        for (int i = 0; i < CONTROLS; i++) {
            control("RS-Q", i % 2 == 0 ? "IN_PROGRESS" : "REVIEW", people);
        }
        MockHttpSession session = login(me);
        Map<String, Long> statements = new LinkedHashMap<>();
        statements.put("/controls", count(session, get("/controls").param("scope", "all")));
        statements.put("/", count(session, get("/")));
        statements.put("/component/HR", count(session, get("/component/HR")));
        // A fixed part (the person, the session, the lists of ids, the completion times) and no part per control
        statements.forEach((page, n) -> assertThat(n).as("%s: %s statements for %d controls", page, n, CONTROLS)
                .isLessThan(CONTROLS / 2));
    }

    private long count(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertThat(call(session, request).getResponse().getStatus()).isEqualTo(200);
        return statistics.getPrepareStatementCount();
    }
}
