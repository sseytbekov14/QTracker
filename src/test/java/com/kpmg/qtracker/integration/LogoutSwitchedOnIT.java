package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Logout switched back on with the password (AUTH_LOGOUT_ENABLED=true; off by default since 2026-10-16, AuthMode):
 * the sidebar link and /logout end the session, and nothing opens with the old cookie afterwards.
 */
@TestPropertySource(properties = "auth.logout.enabled=true")
class LogoutSwitchedOnIT extends ReleaseScenarioSupport {

    @Test
    void theLogoutLinkOfTheSidebar_endsTheSession_nothingOpensWithItAfterwards() throws Exception {
        User user = myEdit("logout");
        Control control = control("RS-OUT", "IN_PROGRESS", people().facilitator(user));
        MockHttpSession session = login(user);
        assertThat(status(session, get("/api/control-details").param("controlId", String.valueOf(control.getId()))))
                .isEqualTo(200);

        MvcResult logout = call(session, get("/logout"));
        assertThat(logout.getResponse().getRedirectedUrl()).startsWith("/login");

        // The same browser session (cookie) afterwards
        for (String path : List.of("/", "/controls", "/view-control/" + control.getId(), "/admin/users")) {
            assertThat(outcome(call(session, get(path)))).as(path).isEqualTo("login");
        }
        for (String path : List.of("/api/control-details?controlId=" + control.getId(),
                "/api/controls/" + control.getId() + "/changelog",
                "/api/dashboard/deadline-countdown",
                "/api/attachments/info/" + control.getId())) {
            MvcResult result = call(session, get(path));
            assertThat(result.getResponse().getStatus()).as(path).isNotEqualTo(200);
        }
        assertThat(session.isInvalid()).as("the server session is invalidated").isTrue();
    }

    @Test
    void theSignOutFormOfSpringSecurity_endsTheSessionToo() throws Exception {
        User user = myEdit("logout-post");
        MockHttpSession session = login(user);
        MvcResult logout = call(session, post("/logout"));
        assertThat(logout.getResponse().getRedirectedUrl()).startsWith("/login");
        assertThat(outcome(call(session, get("/")))).isEqualTo("login");
    }
}
