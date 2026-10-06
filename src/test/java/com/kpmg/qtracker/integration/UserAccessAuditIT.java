package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.config.DevUserSeeder;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Admin Panel save of one user (POST /api/users/{id}/access): admins only, no self lock-out, the e-mail only
 * before the first login, a refused save changes nothing, and every change is one audit entry with the values
 * before and after. Runs with
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
        User admin = saveAdmin();
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
                        + "Changed status from ACTIVE to INACTIVE for " + target.getMail());
        assertThat(log.getChangedFields()).isEqualTo("level,scope,enabled");
        assertThat(log.getPreviousValues()).isEqualTo("level=PARTICIPANT, scope=OWN, enabled=true");
        assertThat(log.getNewValues()).isEqualTo("level=READ_ONLY, scope=ALL, enabled=false");
    }

    @Test
    void oneSave_changesNameEmailAndAccess_writesOneEntryWithTheChangedFields_shownOnTheAuditTrail() throws Exception {
        User admin = saveAdmin();
        User target = saveUser("audit-new-" + suffix() + "@example.test", "FACILITATOR");
        String newMail = "audit-renamed-" + suffix() + "@example.test";
        MockHttpSession session = login(admin.getMail());

        mockMvc.perform(post("/api/users/" + target.getId() + "/access").with(csrf().asHeader()).session(session)
                        .param("email", newMail.toUpperCase())
                        .param("displayName", "Renamed User")
                        .param("level", "SOQM")
                        .param("scope", "ALL")
                        .param("adminAccess", "false")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mail").value(newMail))
                .andExpect(jsonPath("$.displayName").value("Renamed User"))
                .andExpect(jsonPath("$.level").value("SOQM"))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.audit.group").value("USER_ACCESS"))
                .andExpect(jsonPath("$.audit.target").value(newMail))
                .andExpect(jsonPath("$.audit.action").value("Changed email from " + target.getMail() + " to " + newMail
                        + "; Changed name from " + target.getDisplayName() + " to Renamed User"
                        + "; Changed level from PARTICIPANT to SOQM; Changed scope from OWN to ALL"
                        + "; Changed admin access from NO to YES"));

        List<AdminAuditLog> entries = accessEntries(admin);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getChangedFields()).isEqualTo("mail,displayName,level,scope,adminAccess");
        assertThat(entries.get(0).getPreviousValues()).isEqualTo("mail=" + target.getMail()
                + ", displayName=" + target.getDisplayName() + ", level=PARTICIPANT, scope=OWN, adminAccess=false");
        assertThat(entries.get(0).getNewValues()).isEqualTo("mail=" + newMail
                + ", displayName=Renamed User, level=SOQM, scope=ALL, adminAccess=true");

        // The entry is on the Audit Trail of the next page load as well
        String html = mockMvc.perform(get("/admin/users").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Changed level from PARTICIPANT to SOQM", newMail);
    }

    @Test
    void saveWithoutChanges_writesNoEntry() throws Exception {
        User admin = saveAdmin();
        User target = saveUser("audit-same-" + suffix() + "@example.test", "FACILITATOR");

        mockMvc.perform(post("/api/users/" + target.getId() + "/access").with(csrf().asHeader()).session(login(admin.getMail()))
                        .param("email", target.getMail())
                        .param("displayName", target.getDisplayName())
                        .param("level", "PARTICIPANT")
                        .param("scope", "OWN")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.audit").doesNotExist());
        assertThat(accessEntries(admin)).isEmpty();
    }

    @Test
    void userWithoutAdminAccess_isRefused_andNothingChanges() throws Exception {
        User participant = saveUser("audit-plain-" + suffix() + "@example.test", "PROCESS_OWNER");
        User target = saveUser("audit-victim-" + suffix() + "@example.test", "FACILITATOR");

        mockMvc.perform(post("/api/users/" + target.getId() + "/access").with(csrf().asHeader()).session(login(participant.getMail()))
                        .param("level", "SOQM")
                        .param("adminAccess", "true")
                        .param("enabled", "true"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/users").with(csrf().asHeader()).session(login(participant.getMail()))
                        .param("email", "audit-sneaky-" + suffix() + "@example.test")
                        .param("level", "SOQM"))
                .andExpect(status().isForbidden());

        User stored = userRepository.findById(target.getId()).orElseThrow();
        assertThat(stored.getAccessLevel()).isEqualTo(AccessLevel.PARTICIPANT);
        assertThat(stored.getAdminAccess()).isFalse();
        assertThat(accessEntries(participant)).isEmpty();
    }

    @Test
    void adminCannotLockThemselvesOut() throws Exception {
        User admin = saveAdmin();
        MockHttpSession session = login(admin.getMail());

        assertSelfSaveRefused(session, admin, "SOQM", "true", "false", "You cannot deactivate your own account");
        assertSelfSaveRefused(session, admin, "PARTICIPANT", "false", "true", "You cannot change your own role");
        assertSelfSaveRefused(session, admin, "READ_ONLY", "true", "true", "You cannot change your own role");

        User stored = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(stored.getEnabled()).isTrue();
        assertThat(stored.getAdminAccess()).isTrue();
        assertThat(stored.getAccessLevel()).isEqualTo(AccessLevel.SOQM);
        assertThat(accessEntries(admin)).isEmpty();
    }

    @Test
    void emailOfSomeoneWhoSignedIn_cannotChange_andTheRestOfTheSaveIsNotApplied() throws Exception {
        User admin = saveAdmin();
        User target = saveUser("audit-signed-" + suffix() + "@example.test", "FACILITATOR");
        target.setLastLoginAt(LocalDateTime.of(2026, 10, 1, 9, 30));
        userRepository.save(target);

        mockMvc.perform(post("/api/users/" + target.getId() + "/access").with(csrf().asHeader()).session(login(admin.getMail()))
                        .param("email", "audit-moved-" + suffix() + "@example.test")
                        .param("level", "SOQM")
                        .param("enabled", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Email can be changed only before the first login"));

        User stored = userRepository.findById(target.getId()).orElseThrow();
        assertThat(stored.getMail()).isEqualTo(target.getMail());
        assertThat(stored.getAccessLevel()).isEqualTo(AccessLevel.PARTICIPANT);
        assertThat(accessEntries(admin)).isEmpty();
    }

    private void assertSelfSaveRefused(MockHttpSession session, User admin, String level, String adminAccess,
                                       String enabled, String message) throws Exception {
        mockMvc.perform(post("/api/users/" + admin.getId() + "/access").with(csrf().asHeader()).session(session)
                        .param("level", level)
                        .param("scope", "ALL")
                        .param("adminAccess", adminAccess)
                        .param("enabled", enabled))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(message));
    }

    private List<AdminAuditLog> accessEntries(User admin) {
        return auditLogRepository.findByAdminEmailOrderByCreatedAtDesc(admin.getMail()).stream()
                .filter(entry -> entry.getActionType().startsWith("USER_"))
                .toList();
    }

    /** The Admin Panel belongs to SoQM Team. */
    private User saveAdmin() {
        return saveUser("audit-admin-" + suffix() + "@example.test", "SOQM_TEAM");
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
