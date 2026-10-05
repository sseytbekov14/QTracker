package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.config.DevUserSeeder;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.support.TestUsers;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.UserRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit entry of an Admin Panel access change keeps the values before the change. Runs with
 * open-in-view on, as the application does (the test profile turns it off): then the service changes
 * the very entity the controller loaded before the update.
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.datasource.url=jdbc:h2:mem:user-access-audit-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.open-in-view=true",
        "reminders.enabled=false",
        "file.upload.dir=target/it-uploads"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class UserAccessAuditIT {

    private static final String PASSWORD = "Test#123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminAuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private DevUserSeeder devUserSeeder;

    @Test
    void accessUpdate_auditRecordsTheValuesBeforeAndAfter() throws Exception {
        User admin = saveUser("audit-admin-" + suffix() + "@example.test", "PROCESS_OWNER");
        admin.setAdminAccess(true);
        userRepository.save(admin);
        User target = saveUser("audit-target-" + suffix() + "@example.test", "FACILITATOR");

        mockMvc.perform(post("/api/users/" + target.getId() + "/access").with(csrf().asHeader()).session(login(admin.getMail()))
                        .param("level", "READ_ONLY")
                        .param("scope", "ALL")
                        .param("adminAccess", "true")
                        .param("enabled", "false"))
                .andExpect(status().isOk());

        AdminAuditLog log = auditLogRepository.findByAdminEmailOrderByCreatedAtDesc(admin.getMail()).stream()
                .filter(entry -> "USER_ACCESS_UPDATE".equals(entry.getActionType()))
                .findFirst().orElseThrow();
        assertThat(log.getActionDescription()).isEqualTo(
                "Changed level from PARTICIPANT to READ_ONLY; Changed scope from OWN to ALL; "
                        + "Changed admin access from NO to YES; Changed status from ACTIVE to INACTIVE for " + target.getMail());
        assertThat(log.getChangedFields()).isEqualTo("level,scope,adminAccess,enabled");
        assertThat(log.getPreviousValues()).isEqualTo("level=PARTICIPANT, scope=OWN, adminAccess=false, enabled=true");
        assertThat(log.getNewValues()).isEqualTo("level=READ_ONLY, scope=ALL, adminAccess=true, enabled=false");
    }

    private User saveUser(String mail, String role) {
        User user = new User();
        user.setMail(mail);
        user.setDisplayName(mail);
        TestUsers.withRole(user, role);
        user.setEnabled(true);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        return userRepository.save(user);
    }

    private MockHttpSession login(String mail) throws Exception {
        MvcResult login = mockMvc.perform(post("/login").with(csrf())
                        .param("username", mail)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
