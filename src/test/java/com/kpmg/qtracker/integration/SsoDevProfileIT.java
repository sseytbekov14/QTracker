package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * dev (and stage, the same chain) with SSO switched on by the variables, as on stage before prod: no password
 * form, no username login, pages go to Microsoft. Logout is switched on here (AUTH_LOGOUT_ENABLED=true): it ends
 * the QTracker session only and says so. The debug endpoint /me is on too (AUTH_SSO_DEBUG_ENDPOINT=true).
 */
@SpringBootTest(properties = {
        "auth.sso.enabled=true",
        "auth.password.enabled=false",
        "auth.username-login.enabled=true",
        "auth.logout.enabled=true",
        "auth.sso.debug-endpoint=true",
        "app.base-url=" + SsoTestSupport.SITE,
        "auth.sso.entity-id=" + SsoTestSupport.ENTITY_ID,
        "auth.sso.acs-url=" + SsoTestSupport.ACS_URL,
        "auth.sso.metadata-uri=classpath:saml/test-idp-metadata.xml",
        "spring.datasource.url=jdbc:h2:mem:sso-dev-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "reminders.enabled=false",
        "file.upload.dir=target/it-uploads-sso-dev"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class SsoDevProfileIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private UserRepository userRepository;

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

    @Test
    void logoutSwitchedOn_endsTheQTrackerSession_andSaysTheMicrosoftOneStays() throws Exception {
        User user = person("dev-logout");
        MockHttpSession signedIn = signIn(user, UUID.randomUUID().toString());
        assertThat(signedIn.getAttribute("currentUser")).isNotNull();
        assertThat(mockMvc.perform(get("/").session(signedIn)).andReturn().getResponse().getContentAsString())
                .contains("href=\"/logout\"");

        mockMvc.perform(get("/logout").session(signedIn)).andExpect(redirectedUrl("/signed-out"));
        String page = mockMvc.perform(get("/signed-out")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Your Microsoft account stays signed in");
        mockMvc.perform(get("/controls").session(signedIn))
                .andExpect(redirectedUrlPattern("**" + SsoTestSupport.SIGN_ON_PATH));
    }

    /** /me shows each person what Microsoft sent about them at their own sign-in, and nothing about others. */
    @Test
    void theDebugEndpoint_showsOnlyTheCallersOwnClaims() throws Exception {
        User anna = person("dev-me-anna");
        User boris = person("dev-me-boris");
        String annaObjectId = UUID.randomUUID().toString();
        String borisObjectId = UUID.randomUUID().toString();
        MockHttpSession annaSession = signIn(anna, annaObjectId);
        MockHttpSession borisSession = signIn(boris, borisObjectId);

        String annaClaims = mockMvc.perform(get("/me").session(annaSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(annaClaims).contains("\"nameId\":\"" + anna.getMail() + "\"").contains(annaObjectId)
                .doesNotContain(boris.getMail()).doesNotContain(borisObjectId);
        assertThat(mockMvc.perform(get("/me").session(borisSession)).andReturn().getResponse().getContentAsString())
                .contains(borisObjectId).doesNotContain(annaObjectId);
        // Without a sign-in: to Microsoft, like every page
        mockMvc.perform(get("/me")).andExpect(redirectedUrlPattern("**" + SsoTestSupport.SIGN_ON_PATH));
    }

    private User person(String name) {
        User user = new User();
        user.setMail(name + "-" + UUID.randomUUID().toString().substring(0, 8) + "@corp.example.test");
        user.setDisplayName("Dev " + name);
        user.setAccessLevel(AccessLevel.PARTICIPANT);
        user.setAccessScope(AccessScope.OWN);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private MockHttpSession signIn(User user, String objectId) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(SsoTestSupport.SIGN_ON_PATH).session(session));
        MvcResult acs = mockMvc.perform(post(SsoTestSupport.ACS_PATH).session(session).param("SAMLResponse",
                SamlResponses.signed(SamlResponses.Person.of(user.getMail(), objectId),
                        SsoTestSupport.authnRequestId(session)))).andReturn();
        return (MockHttpSession) acs.getRequest().getSession(false);
    }
}
