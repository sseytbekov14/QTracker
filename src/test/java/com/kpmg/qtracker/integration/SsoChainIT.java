package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * prod with SSO: its own chain, pages lead to the Microsoft sign-in, scripts get 401, the password form is gone,
 * the Entra application can read QTracker's metadata.
 */
class SsoChainIT extends SsoTestSupport {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void prodHasItsOwnChain_andNoPasswordProvider() {
        assertThat(context.containsBean("securityFilterChainProd")).isTrue();
        assertThat(context.containsBean("securityFilterChainDev")).isFalse();
        assertThat(context.containsBean("devAuthenticationProvider")).isFalse();
    }

    @Test
    void aPage_withoutASession_goesToTheMicrosoftSignIn() throws Exception {
        for (String path : List.of("/", "/controls", "/admin/users", "/view-control/1")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrlPattern("**" + SIGN_ON_PATH));
        }
    }

    @Test
    void theSignOnUrl_sendsAnAuthnRequestToTheIdentityProvider() throws Exception {
        MvcResult result = mockMvc.perform(get(SIGN_ON_PATH)).andExpect(status().isFound()).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).startsWith(IDP_SSO_URL + "?SAMLRequest=");
    }

    @Test
    void anApiCall_withoutASession_is401Json_notARedirect() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
                .andExpect(jsonPath("$.message").value("Session expired, reload the page"));
        mockMvc.perform(post("/api/users").with(csrf().asHeader()).param("email", "x@example.test"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theServiceProviderMetadata_namesTheIdentifierAndTheReplyUrl() throws Exception {
        String metadata = mockMvc.perform(get("/saml2/metadata/entra"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(metadata).contains("entityID=\"" + ENTITY_ID + "\"").contains("Location=\"" + ACS_URL + "\"");
    }

    @Test
    void thePasswordForm_isGone() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isFound()).andExpect(redirectedUrl("/"));

        User user = new User();
        user.setMail("sso-form-" + UUID.randomUUID().toString().substring(0, 6) + "@sso.test");
        user.setDisplayName("Sso Form");
        user.setAccessLevel(AccessLevel.SOQM);
        user.setAccessScope(AccessScope.ALL);
        user.setEnabled(true);
        user.setPassword(passwordEncoder.encode("Sso#2026"));
        userRepository.save(user);

        MvcResult signIn = mockMvc.perform(post("/login").with(csrf())
                .param("username", user.getMail()).param("password", "Sso#2026")).andReturn();
        assertThat(signIn.getResponse().getStatus()).isNotEqualTo(302);
        MockHttpSession session = (MockHttpSession) signIn.getRequest().getSession(false);
        if (session != null) {
            assertThat(session.getAttribute("currentUser")).isNull();
            mockMvc.perform(get("/").session(session)).andExpect(redirectedUrlPattern("**" + SIGN_ON_PATH));
        }
        assertThat(userRepository.findById(user.getId()).orElseThrow().getLastLoginAt()).isNull();
    }

    /** prod with SSO: no Logout link and no /logout; the sidebar says the Microsoft session stays instead. */
    @Test
    void withSso_thereIsNoLogout_andTheSidebarSaysWhy() throws Exception {
        User user = person("logout", AccessLevel.PARTICIPANT, AccessScope.OWN);
        MockHttpSession session = signIn("/", SamlResponses.Person.of(user.getMail(), newObjectId())).session();

        String dashboard = mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(dashboard).doesNotContain("href=\"/logout\"").contains("Signed in with Microsoft")
                .contains("the Microsoft session stays open");

        mockMvc.perform(get("/logout").session(session)).andExpect(status().isNotFound());
        int post = mockMvc.perform(post("/logout").session(session).with(csrf())).andReturn().getResponse().getStatus();
        assertThat(post).isIn(404, 405);
        mockMvc.perform(get("/controls").session(session)).andExpect(status().isOk());
    }

    @Test
    void theRefusalPage_hasNoDetails_andNoWayBack() throws Exception {
        String page = mockMvc.perform(get("/access-not-granted"))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Access is not granted, contact SoQM Team.").doesNotContain("Back to Dashboard")
                .doesNotContain("href=\"/\"");
    }
}
