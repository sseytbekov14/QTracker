package com.kpmg.qtracker.config;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.support.TestUsers;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.security.CsrfAccessDeniedHandler;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
@ExtendWith(OutputCaptureExtension.class)
class SecurityConfigIntegrationTest {

    private static final Set<RequestMethod> WRITE_METHODS =
            EnumSet.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

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
                        .param("username", "soqm1@qtracker.local")
                        .param("password", "aaa"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginSuccessWithCsrfRedirectsToHomeAndPopulatesSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "soqm1@qtracker.local")
                        .param("password", "aaa"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute("currentUser")).isNotNull();
        assertThat(session.getAttribute("userRole")).isEqualTo("SOQM_TEAM");
    }

    @Test
    void loginFailureWithCsrfRedirectsToLoginError() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "soqm1@qtracker.local")
                        .param("password", "wrong-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute("SPRING_SECURITY_LAST_EXCEPTION")).isNotNull();
    }

    @Test
    void apiPostWithCsrfHeaderHitsControllerWhenAuthenticated() throws Exception {
        MockHttpSession session = login("soqm1@qtracker.local", "aaa");

        mockMvc.perform(post("/api/controls/999/rename-id")
                        .with(csrf().asHeader())
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content("{\"newControlId\":\"CTRL-999\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void apiPostWithoutCsrfTokenIsForbiddenWithJsonMessage() throws Exception {
        mockMvc.perform(post("/api/controls/999/rename-id")
                        .contentType(APPLICATION_JSON)
                        .content("{\"newControlId\":\"CTRL-999\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(CsrfAccessDeniedHandler.CODE))
                .andExpect(jsonPath("$.message").value(CsrfAccessDeniedHandler.MESSAGE));
    }

    @Test
    void apiPostWithInvalidCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/controls/999/rename-id").with(csrf().asHeader().useInvalidToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CsrfAccessDeniedHandler.CODE));
    }

    @Test
    void csrfFailureIsLoggedWithMethodAndPathButWithoutToken(CapturedOutput output) throws Exception {
        String token = "not-a-real-token-1234567890";
        mockMvc.perform(post("/api/controls/999/rename-id").header("X-XSRF-TOKEN", token))
                .andExpect(status().isForbidden());

        assertThat(output.getAll()).contains("CSRF token missing or invalid: POST /api/controls/999/rename-id");
        assertThat(output.getAll()).doesNotContain(token);
    }

    @Test
    void multipartUploadNeedsCsrfTokenAndAcceptsItAsHeader() throws Exception {
        MockMultipartFile file = new MockMultipartFile("attachmentDetails", "a.pdf", "application/pdf", new byte[] {1});

        mockMvc.perform(multipart("/api/attachments/upload/1").file(file))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CsrfAccessDeniedHandler.CODE));

        // With the header the request gets past CSRF and only then meets the login check
        mockMvc.perform(multipart("/api/attachments/upload/1").file(file).with(csrf().asHeader()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void everyApiEndpointThatChangesDataRequiresCsrfToken() throws Exception {
        List<String> checked = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            // A mapping without methods answers all of them, POST included
            Set<RequestMethod> writeMethods = methods.isEmpty()
                    ? EnumSet.of(RequestMethod.POST)
                    : EnumSet.noneOf(RequestMethod.class);
            methods.stream().filter(WRITE_METHODS::contains).forEach(writeMethods::add);
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                String path = pattern.replaceAll("\\{[^}]+}", "1");
                for (RequestMethod method : writeMethods) {
                    String name = method + " " + pattern;
                    mockMvc.perform(request(HttpMethod.valueOf(method.name()), path))
                            .andExpect(result -> assertThat(result.getResponse().getStatus()).as(name).isEqualTo(403))
                            .andExpect(jsonPath("$.code").value(CsrfAccessDeniedHandler.CODE));
                    checked.add(name);
                }
            }
        }
        // Guards against the scan silently finding nothing (e.g. another mapping bean)
        assertThat(checked).contains("POST /api/controls", "PUT /api/controls/{id}",
                "POST /api/attachments/upload/{controlId}", "DELETE /api/attachments/delete/{controlId}");
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

    @Test
    void disabledUserLoginReturns403() throws Exception {
        User disabledUser = new User();
        disabledUser.setMail("disabled.user@qtracker.local");
        disabledUser.setDisplayName("Disabled User");
        TestUsers.withRole(disabledUser, "SOQM_TEAM");
        disabledUser.setEnabled(false);
        disabledUser.setPassword(passwordEncoder.encode("aaa"));
        userRepository.save(disabledUser);

        mockMvc.perform(post("/login")
                        .with(csrf())
                .param("username", "disabled.user@qtracker.local")
                        .param("password", "aaa"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

        @Test
        void disabledUserAfterLoginIsForbiddenOnEveryRequest() throws Exception {
                User user = new User();
                user.setMail("disable.after.login@qtracker.local");
                user.setDisplayName("Disable After Login");
                TestUsers.withRole(user, "SOQM_TEAM");
                user.setEnabled(true);
                user.setPassword(passwordEncoder.encode("aaa"));
                userRepository.save(user);

                MockHttpSession session = login("disable.after.login@qtracker.local", "aaa");

                user = userRepository.findByMail("disable.after.login@qtracker.local").orElseThrow();
                user.setEnabled(false);
                userRepository.save(user);

                mockMvc.perform(get("/api/users")
                                                .session(session))
                                .andExpect(status().isForbidden());
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