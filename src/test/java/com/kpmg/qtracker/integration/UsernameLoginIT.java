package com.kpmg.qtracker.integration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Signing in with a username (auth.username-login.enabled, on in dev and test): the username or the e-mail,
 * in any case and with stray spaces, and the temporary password; a disabled account and a wrong password
 * are refused. Without the setting only the e-mail signs in. Made-up people only.
 */
class UsernameLoginIT extends UsernameLoginSupport {

    @Test
    void username_andTheTemporaryPassword_signIn() throws Exception {
        assertThat(signIn("tsoqm", PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void caseAndSpaces_doNotCount_forTheUsernameAndTheEmail() throws Exception {
        assertThat(signIn("  TUser ", PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(signIn("TUSER@QTRACKER.LOCAL", PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void wrongPassword_isRefused() throws Exception {
        MvcResult result = signIn("tkdn", "not-the-password");

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(signInPage(result)).contains("Invalid username or password");
    }

    @Test
    void disabledAccount_isRefused_withItsMessage() throws Exception {
        MvcResult result = signIn("tgone", PASSWORD);

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(signInPage(result)).contains("Your account is disabled");
    }

    @Test
    void loginPage_asksForTheUsername() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">Username</label>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Enter your username or email")));
    }

    @Test
    void adminPanel_opensForSoqmTeamOnly() throws Exception {
        mockMvc.perform(get("/admin/users").session(session("tsoqm")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-users"));
        mockMvc.perform(get("/admin/users").session(session("tuser")))
                .andExpect(redirectedUrl("/"));
        mockMvc.perform(get("/admin/users").session(session("tkdn")))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void username_ofAnAccountOnAnotherDomain_signsIn_whenItIsTheOnlyOne() throws Exception {
        MockHttpSession session = session(" TFirm ");

        mockMvc.perform(get("/admin/users").session(session)).andExpect(status().isOk());
        assertThat(session.getAttribute("currentUser")).extracting("mail").isEqualTo("tfirm@firm.test");
    }

    @Test
    void theImportedDomain_comesFirst() throws Exception {
        MockHttpSession session = session("tboth");

        assertThat(session.getAttribute("currentUser")).extracting("mail").isEqualTo("tboth@qtracker.local");
    }

    @Test
    void sameUsernameOnTwoOtherDomains_isNotGuessed_theEmailSignsIn() throws Exception {
        assertThat(signIn("ttwice", PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(signIn("ttwice@other.test", PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void wildcardsOfTheSearch_areTakenLiterally() throws Exception {
        assertThat(signIn("tax", PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(signIn("t_x", PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(signIn("t%", PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/login?error");
    }
}
