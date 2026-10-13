package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.User;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Release test pass, stage I: the main pages over 2000 controls and 10000 history entries - the time of the
 * Controls list, the dashboard, Action Centre, a component list and the Excel of the list, and how many SQL
 * statements each runs (N+1). Heavy: runs only with -Drelease.perf=true; the numbers go to the report (the
 * specification has no response-time target yet, section 24), the test only refuses minutes.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ReleaseScenarioPerformanceIT extends ReleaseScenarioSupport {

    private static final int CONTROLS = 2000;
    private static final int HISTORY = 10000;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void mainPages_over2000Controls_and10000HistoryEntries() throws Exception {
        assumeTrue(Boolean.getBoolean("release.perf"), "heavy: -Drelease.perf=true");
        User soqm = soqm("pf-soqm");
        User fac = myEdit("pf-fac");
        String[] statuses = {"DRAFT", "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED"};
        String[] components = {"HR", "EP", "A&C", "RER", "GOV", "RAP"};
        LocalDate base = LocalDate.of(2026, 9, 1);
        List<Object[]> controls = new ArrayList<>();
        for (int i = 0; i < CONTROLS; i++) {
            LocalDate op = base.plusDays(i % 90);
            controls.add(new Object[]{"PF-" + RUN + "-" + i, "Monthly", "Preventive", "Manual", components[i % 6],
                    statuses[i % 6], "ACTIVE", "Performance control " + i, fac.getMail(), fac.getMail(),
                    soqm.getMail(), fac.getMail(), java.sql.Date.valueOf(op), java.sql.Date.valueOf(op.plusDays(14)),
                    java.sql.Date.valueOf(op.plusDays(14)), Timestamp.valueOf(LocalDateTime.now())});
        }
        jdbc.batchUpdate("INSERT INTO controls (control_id, control_frequency, control_type, control_category, "
                + "component, performance_status, control_status, control_description, facilitator, control_operator, "
                + "soqm_team, process_owner, control_operation_date, control_operation_deadline, deadline, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", controls);
        List<Long> ids = jdbc.queryForList("SELECT id FROM controls WHERE control_id LIKE ?", Long.class,
                "PF-" + RUN + "-%");
        assertThat(ids).hasSize(CONTROLS);
        List<Object[]> history = new ArrayList<>();
        for (int i = 0; i < HISTORY; i++) {
            history.add(new Object[]{ids.get(i % ids.size()), i % 5 == 4 ? "APPROVE" : "SUBMIT_TO_SOQM_TEAM",
                    "REVIEW", i % 5 == 4 ? "COMPLETED" : "SOQM_HEAD_REVIEW", fac.getMail(), "Pf Fac",
                    Timestamp.valueOf(LocalDateTime.now().minusMinutes(i)), false});
        }
        jdbc.batchUpdate("INSERT INTO workflow_history (control_id, action_type, from_step, to_step, "
                + "performed_by_email, performed_by_name, created_at, on_behalf) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", history);

        MockHttpSession soqmSession = login(soqm);
        MockHttpSession facSession = login(fac);
        Map<String, String> measured = new LinkedHashMap<>();
        measure(measured, "SoQM /component/ALL", soqmSession, get("/component/ALL"));
        measure(measured, "SoQM dashboard", soqmSession, get("/"));
        measure(measured, "SoQM /component/HR", soqmSession, get("/component/HR"));
        measure(measured, "SoQM Excel of All components", soqmSession, get("/component/ALL/export"));
        measure(measured, "User (2000 own) /component/ALL", facSession, get("/component/ALL"));
        measure(measured, "User (2000 own) dashboard", facSession, get("/"));
        measure(measured, "View Control", soqmSession, get("/view-control/{id}", ids.get(0)));
        measure(measured, "Changelog", soqmSession, get("/api/controls/{id}/changelog", ids.get(0)));
        measured.forEach((page, result) -> System.out.println("RELEASE-SCENARIO perf " + page + ": " + result));
    }

    private void measure(Map<String, String> measured, String name, MockHttpSession session,
                         MockHttpServletRequestBuilder request) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        long start = System.nanoTime();
        MvcResult result = call(session, request);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(result.getResponse().getStatus()).as(name).isEqualTo(200);
        assertThat(millis).as(name + " takes minutes").isLessThan(120_000);
        measured.put(name, millis + " ms, " + statistics.getPrepareStatementCount() + " SQL statements");
    }
}
