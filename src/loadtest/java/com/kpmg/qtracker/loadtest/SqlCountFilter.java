package com.kpmg.qtracker.loadtest;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * The number of SQL statements of each request (Hibernate, SqlStatementCounter), by method and route pattern, as
 * the metric loadtest.sql.statements (/actuator/metrics/loadtest.sql.statements?tag=uri:/view-control/{id}).
 * Outermost filter, so the sign-in check, the page and its rendering (open-in-view) are all counted.
 */
@Component
@Profile("loadtest")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SqlCountFilter extends OncePerRequestFilter {

    static final String METRIC = "loadtest.sql.statements";

    private final MeterRegistry registry;

    public SqlCountFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SqlStatementCounter.reset();
        try {
            chain.doFilter(request, response);
        } finally {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String uri = pattern != null ? pattern.toString() : "UNKNOWN";
            if (!uri.startsWith("/actuator")) {
                DistributionSummary.builder(METRIC)
                        .tag("method", request.getMethod())
                        .tag("uri", uri)
                        .register(registry)
                        .record(SqlStatementCounter.count());
            }
            SqlStatementCounter.reset();
        }
    }
}
