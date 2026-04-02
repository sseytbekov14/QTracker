package com.kpmg.qtracker.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void everyResponseContainsCorrelationIdAndMdcIsClearedAfterCompletion() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        filter.doFilter(request, response, chain);

        String responseCorrelationId = response.getHeader("X-Correlation-Id");
        assertThat(responseCorrelationId).isNotBlank();
        assertThat(chain.correlationIdSeenInsideChain).isEqualTo(responseCorrelationId);
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void eachRequestGetsOwnCorrelationId() throws Exception {
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/login"), firstResponse, new CapturingChain());
        filter.doFilter(new MockHttpServletRequest("GET", "/api/users"), secondResponse, new CapturingChain());

        assertThat(firstResponse.getHeader("X-Correlation-Id")).isNotBlank();
        assertThat(secondResponse.getHeader("X-Correlation-Id")).isNotBlank();
        assertThat(firstResponse.getHeader("X-Correlation-Id"))
                .isNotEqualTo(secondResponse.getHeader("X-Correlation-Id"));
    }

    private static final class CapturingChain implements FilterChain {
        private String correlationIdSeenInsideChain;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
            correlationIdSeenInsideChain = MDC.get("correlationId");
        }
    }
}