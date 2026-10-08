package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.config.DevUserSeeder;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Made-up people of every role and a disabled one, all with the temporary password, for the sign-in tests
 * (UsernameLoginIT with usernames on, UsernameLoginOffIT without).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:username-login-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "reminders.enabled=false",
        "controls.auto-create.enabled=false",
        "file.upload.dir=target/it-uploads-username-login"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
abstract class UsernameLoginSupport {

    static final String PASSWORD = "aaa";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRepository userRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @MockitoBean
    DevUserSeeder devUserSeeder;

    @BeforeEach
    void people() {
        person("tsoqm@qtracker.local", "Soqm, Test", AccessLevel.SOQM, AccessScope.ALL, true);
        person("tuser@qtracker.local", "User, Test", AccessLevel.PARTICIPANT, AccessScope.OWN, true);
        person("tkdn@qtracker.local", "Kdn, Test", AccessLevel.READ_ONLY, AccessScope.KDN, true);
        person("tgone@qtracker.local", "Gone, Test", AccessLevel.PARTICIPANT, AccessScope.OWN, false);
    }

    private void person(String mail, String name, AccessLevel level, AccessScope scope, boolean enabled) {
        if (userRepository.existsByMail(mail)) {
            return;
        }
        User user = TestUsers.user(mail, level, scope, level == AccessLevel.SOQM);
        user.setDisplayName(name);
        user.setEnabled(enabled);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        userRepository.save(user);
    }

    MvcResult signIn(String login, String password) throws Exception {
        return mockMvc.perform(post("/login").with(csrf())
                        .param("username", login)
                        .param("password", password))
                .andExpect(status().is3xxRedirection())
                .andReturn();
    }

    MockHttpSession session(String login) throws Exception {
        MvcResult result = signIn(login, PASSWORD);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/");
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    String signInPage(MvcResult failed) throws Exception {
        MockHttpSession session = (MockHttpSession) failed.getRequest().getSession(false);
        return mockMvc.perform(get("/login").param("error", "").session(session))
                .andReturn().getResponse().getContentAsString();
    }
}
