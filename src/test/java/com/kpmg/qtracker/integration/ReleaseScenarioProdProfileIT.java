package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release test pass, stage I: the prod profile. Since feature/sso-saml it has its own chain with sign-in by
 * Microsoft Entra ID (SsoChainIT, SsoSignInIT): everything needs a sign-in, Swagger is off and the password form
 * no longer signs anyone in (5_release_test_report.md, D-09). The only settings given here are the ones prod
 * requires from the environment, the SAML ones pointing at the test identity provider.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:release-prod-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "file.upload.dir=target/it-uploads-prod",
        "app.base-url=https://qtracker.example.test",
        "reminders.enabled=false",
        "controls.auto-create.enabled=false",
        "auth.sso.entity-id=https://qtracker.example.test/saml/qtracker",
        "auth.sso.acs-url=https://qtracker.example.test/login/saml2/sso/entra",
        "auth.sso.metadata-uri=classpath:saml/test-idp-metadata.xml"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ReleaseScenarioProdProfileIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void everythingNeedsASignIn_andSwaggerIsOff() throws Exception {
        for (String path : List.of("/", "/controls", "/admin/users", "/api/control-details?controlId=1",
                "/v3/api-docs", "/swagger-ui/index.html", "/actuator/env")) {
            MvcResult result = mockMvc.perform(get(path)).andReturn();
            assertThat(result.getResponse().getStatus()).as(path).isIn(302, 401, 403, 404);
            assertThat(result.getResponse().getContentAsString()).as(path).doesNotContain("openapi");
        }
    }

    @Test
    void aPersonOfTheDatabase_cannotGetIntoTheApplication_withThePasswordForm() throws Exception {
        User user = new User();
        user.setMail("prod-" + UUID.randomUUID().toString().substring(0, 6) + "@release.test");
        user.setDisplayName("Prod Person");
        user.setAccessLevel(AccessLevel.SOQM);
        user.setAccessScope(AccessScope.ALL);
        user.setAdminAccess(true);
        user.setEnabled(true);
        user.setPassword(passwordEncoder.encode("Release#2026"));
        userRepository.save(user);

        MvcResult signIn = mockMvc.perform(post("/login").with(csrf())
                .param("username", user.getMail()).param("password", "Release#2026")).andReturn();
        MockHttpSession session = (MockHttpSession) signIn.getRequest().getSession(false);
        // With SSO nothing processes the form, so there may be no session at all
        MvcResult home = mockMvc.perform(session == null ? get("/") : get("/").session(session)).andReturn();
        // Recorded: wherever the form sends the person, the dashboard does not open for them
        boolean dashboard = home.getResponse().getStatus() == 200 && home.getModelAndView() != null
                && "dashboard".equals(home.getModelAndView().getViewName());
        assertThat(dashboard).as("sign-in %s -> %s, then / -> %s %s", signIn.getResponse().getStatus(),
                signIn.getResponse().getRedirectedUrl(), home.getResponse().getStatus(),
                home.getResponse().getRedirectedUrl()).isFalse();
        System.out.println("RELEASE-SCENARIO prod sign-in: " + signIn.getResponse().getStatus() + " "
                + signIn.getResponse().getRedirectedUrl() + "; then / : " + home.getResponse().getStatus() + " "
                + home.getResponse().getRedirectedUrl());
    }
}
