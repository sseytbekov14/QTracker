package com.kpmg.qtracker.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * dev (and stage, the same chain) with SSO switched on by the variables, as on stage before prod: no password
 * form, no username login, pages go to Microsoft.
 */
@SpringBootTest(properties = {
        "auth.sso.enabled=true",
        "auth.password.enabled=false",
        "auth.username-login.enabled=true",
        "app.base-url=" + SsoTestSupport.SITE,
        "auth.sso.entity-id=" + SsoTestSupport.ENTITY_ID,
        "auth.sso.acs-url=" + SsoTestSupport.ACS_URL,
        "auth.sso.metadata-uri=classpath:saml/test-idp-metadata.xml",
        "spring.datasource.url=jdbc:h2:mem:sso-dev-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "reminders.enabled=false",
        "controls.auto-create.enabled=false",
        "file.upload.dir=target/it-uploads-sso-dev"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class SsoDevProfileIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationContext context;

    @Test
    void withSso_thePasswordFormAndTheUsernameLogin_doNotExist() throws Exception {
        assertThat(context.containsBean("securityFilterChainDev")).isTrue();
        assertThat(context.containsBean("devAuthenticationProvider")).isFalse();

        mockMvc.perform(get("/login")).andExpect(status().isFound()).andExpect(redirectedUrl("/"));
        // A username and the password "aaa" of the dev accounts: nothing processes the form
        int status = mockMvc.perform(post("/login").with(csrf()).param("username", "soqm1").param("password", "aaa"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isIn(403, 404, 405);
        mockMvc.perform(get("/controls")).andExpect(redirectedUrlPattern("**" + SsoTestSupport.SIGN_ON_PATH));
    }
}
