package com.kpmg.qtracker.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitingFilterTest {

    private final RateLimitingFilter filter = new RateLimitingFilter();

    @Test
    void multipleLoginHitsEventuallyReturnTooManyRequests() throws Exception {
        CountingChain chain = new CountingChain();

        for (int attempt = 1; attempt <= 20; attempt++) {
            MockHttpServletResponse response = invoke("POST", "/login", "127.0.0.1", chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse blockedResponse = invoke("POST", "/login", "127.0.0.1", chain);

        assertThat(chain.invocations).isEqualTo(20);
        assertThat(blockedResponse.getStatus()).isEqualTo(429);
        assertThat(blockedResponse.getContentAsString()).contains("RATE_LIMITED");
    }

    @Test
    void nonProtectedEndpointsAreNotRateLimited() throws Exception {
        CountingChain chain = new CountingChain();

        for (int attempt = 1; attempt <= 25; attempt++) {
            MockHttpServletResponse response = invoke("GET", "/login", "127.0.0.1", chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }

        assertThat(chain.invocations).isEqualTo(25);
    }

    @Test
    void separateBucketsApplyPerPathAndMethod() throws Exception {
        CountingChain loginChain = new CountingChain();
        CountingChain attachmentChain = new CountingChain();

        for (int attempt = 1; attempt <= 20; attempt++) {
            invoke("POST", "/login", "127.0.0.1", loginChain);
        }

        MockHttpServletResponse blockedLogin = invoke("POST", "/login", "127.0.0.1", loginChain);
        MockHttpServletResponse attachmentResponse = invoke("POST", "/api/attachments/upload/42", "127.0.0.1", attachmentChain);

        assertThat(blockedLogin.getStatus()).isEqualTo(429);
        assertThat(attachmentResponse.getStatus()).isEqualTo(200);
        assertThat(attachmentChain.invocations).isEqualTo(1);
    }

    private MockHttpServletResponse invoke(String method, String path, String remoteAddr, CountingChain chain)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddr);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static final class CountingChain implements FilterChain {
        private int invocations;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response) {
            invocations++;
        }
    }
}