package com.kpmg.qtracker.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without auth.username-login.enabled only the e-mail signs in, and the login page asks for it. */
@TestPropertySource(properties = {
        "auth.username-login.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:username-login-off-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class UsernameLoginOffIT extends UsernameLoginSupport {

    @Test
    void username_isRefused_theEmailSignsIn() throws Exception {
        assertThat(signIn("tuser", PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(signIn(" TUser@QTracker.local ", PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void loginPage_asksForTheEmail() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">Email</label>")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("username or email"))));
    }
}
