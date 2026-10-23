package com.kpmg.qtracker.security;

import com.kpmg.qtracker.entity.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The limits with small numbers and a clock the test moves: window 60 s, 3 sign-ins, 4 API writes, 2 user lists,
 * 2 heavy calls. Everyone comes from the same address, as behind the port forward.
 */
class RateLimitingFilterTest {

    private static final String SHARED_ADDRESS = "127.0.0.1";

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final RateLimitingFilter filter = new RateLimitingFilter(60, 3, 4, 2, 2, now::get);
    private final CountingChain chain = new CountingChain();

    @Test
    void signIns_countPerNameAndAddressTogether() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(signIn("anna@example.test", SHARED_ADDRESS).getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = signIn(" ANNA@example.test ", SHARED_ADDRESS);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getContentAsString()).contains("RATE_LIMITED");

        // Another person from the same address, and the same name from another address, are not held up
        assertThat(signIn("boris@example.test", SHARED_ADDRESS).getStatus()).isEqualTo(200);
        assertThat(signIn("anna@example.test", "10.0.0.9").getStatus()).isEqualTo(200);
    }

    @Test
    void apiWrites_countPerSignedInUser_notPerAddress() throws Exception {
        MockHttpSession anna = session(1L);
        MockHttpSession boris = session(2L);
        for (int i = 0; i < 4; i++) {
            assertThat(call("POST", "/api/control-details", anna).getStatus()).isEqualTo(200);
        }
        assertThat(call("PUT", "/api/control-details", anna).getStatus()).isEqualTo(200);
        assertThat(call("POST", "/api/control-details", anna).getStatus()).isEqualTo(429);
        assertThat(call("POST", "/api/control-details", boris).getStatus()).isEqualTo(200);
    }

    @Test
    void withoutASession_theAddressCounts() throws Exception {
        for (int i = 0; i < 4; i++) {
            call("POST", "/api/controls", null);
        }
        assertThat(call("POST", "/api/controls", null).getStatus()).isEqualTo(429);
        assertThat(call("POST", "/api/controls", session(3L)).getStatus()).isEqualTo(200);
    }

    @Test
    void theWindow_letsRequestsThroughAgainWhenItHasPassed() throws Exception {
        MockHttpSession anna = session(1L);
        for (int i = 0; i < 4; i++) {
            call("DELETE", "/api/controls/5", anna);
        }
        assertThat(call("DELETE", "/api/controls/5", anna).getStatus()).isEqualTo(429);

        now.addAndGet(60_001);
        assertThat(call("DELETE", "/api/controls/5", anna).getStatus()).isEqualTo(200);
    }

    @Test
    void heavyCallsAndTheUserList_haveTheirOwnLimits() throws Exception {
        MockHttpSession anna = session(1L);
        call("POST", "/api/attachments/upload/42", anna);
        call("POST", "/api/attachments/upload/42", anna);
        assertThat(call("POST", "/api/attachments/upload/42", anna).getStatus()).isEqualTo(429);
        assertThat(call("POST", "/api/control-details", anna).getStatus()).isEqualTo(200);

        call("GET", "/api/users", anna);
        call("GET", "/api/users", anna);
        assertThat(call("GET", "/api/users", anna).getStatus()).isEqualTo(429);
    }

    @Test
    void microsoftsAnswers_pages_andReads_areNotCounted() throws Exception {
        MockHttpSession anna = session(1L);
        for (int attempt = 1; attempt <= 25; attempt++) {
            assertThat(call("POST", "/login/saml2/sso/entra", null).getStatus()).isEqualTo(200);
            assertThat(call("GET", "/login", null).getStatus()).isEqualTo(200);
            assertThat(call("GET", "/api/control-details", anna).getStatus()).isEqualTo(200);
            assertThat(call("POST", "/notifications/mark-all-read", anna).getStatus()).isEqualTo(200);
        }
        assertThat(chain.invocations).isEqualTo(100);
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> new RateLimitingFilter(0, 1, 1, 1, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RateLimitingFilter(60, 1, -5, 1, 1)).isInstanceOf(IllegalStateException.class);
    }

    private MockHttpServletResponse signIn(String username, String address) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr(address);
        request.addParameter("username", username);
        request.addParameter("password", "x");
        return run(request);
    }

    private MockHttpServletResponse call(String method, String path, MockHttpSession session)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(SHARED_ADDRESS);
        if (session != null) {
            request.setSession(session);
        }
        return run(request);
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpSession session(long userId) {
        User user = new User();
        user.setId(userId);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("currentUser", user);
        return session;
    }

    private static final class CountingChain implements FilterChain {
        private int invocations;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            invocations++;
        }
    }
}
