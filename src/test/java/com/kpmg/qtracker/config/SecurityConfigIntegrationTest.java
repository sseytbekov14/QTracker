package com.kpmg.qtracker.config;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:security-config-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class SecurityConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Test
        void apiRequestWithoutAuthenticationRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/api/users"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void publicEndpointsAreAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 503));

        mockMvc.perform(get("/css/style.css"))
                .andExpect(status().isOk());
    }

    @Test
    void loginPostWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", "soqm1")
                        .param("password", "aaa"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginSuccessWithCsrfRedirectsToHomeAndPopulatesSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "soqm1")
                        .param("password", "aaa"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute("currentUser")).isNotNull();
        assertThat(session.getAttribute("userRole")).isEqualTo("SOQM_LEAD");
    }

    @Test
    void loginFailureWithCsrfRedirectsToLoginError() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "soqm1")
                        .param("password", "wrong-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute("SPRING_SECURITY_LAST_EXCEPTION")).isNotNull();
    }

    @Test
    void apiPostBypassesCsrfProtectionButStillHitsControllerWhenAuthenticated() throws Exception {
        MockHttpSession session = login("soqm1", "aaa");

        mockMvc.perform(post("/api/controls/999/rename-id")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content("{\"newControlId\":\"CTRL-999\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void securityFilterChainContainsCorrelationAndRateLimitingFilters() {
        List<Filter> filters = springSecurityFilterChain.getFilters("/login");

        assertThat(filters).anyMatch(CorrelationIdFilter.class::isInstance);
        assertThat(filters).anyMatch(com.kpmg.qtracker.security.RateLimitingFilter.class::isInstance);
    }

    @Test
        void correlationIdHeaderIsReturnedForUnauthenticatedApiResponses() throws Exception {
        mockMvc.perform(get("/api/users"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrlPattern("**/login"))
                .andExpect(header().exists("X-Correlation-Id"));
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}