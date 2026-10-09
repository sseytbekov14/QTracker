package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.QtrackerApplication;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.security.LoginNameResolver;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.userimport.UserImport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The user import end to end on a made-up people file: a dry run writes nothing but the logins file, --apply
 * creates the accounts with the temporary password and an audit entry each, the same file again changes
 * nothing, and the imported people sign in with their login. Refused outside dev and test and without two
 * active SoQM Team members.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=" + UserImportIT.DB,
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "reminders.enabled=false",
        "controls.auto-create.enabled=false",
        "file.upload.dir=target/it-uploads-user-import"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
@ExtendWith(OutputCaptureExtension.class)
class UserImportIT {

    static final String DB = "jdbc:h2:mem:user-import-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    // Line 1 is the header
    private static final String PEOPLE = """
            Name,User,Role,Access,Status\r
            Anna,"Testova, Anna",SoQM Team,SoQM Team,Active\r
            Boris,"Primerov, Boris Ivan",SoQM Team,SoQM Team,Active\r
            Mark,"Smith, Mark",User,"My Controls, Edit",Active\r
            Maria,"Smith, Maria",User,"All Controls, Read Only",Active\r
            Kim,"O'Neil-Dow, Kim",KDN,"All KDN Controls, Read only",Active\r
            Gone,"Leaver, Gone",User,"My Controls, Edit",Inactive\r
            svc-qt-robot,svc-qt-robot,User,"My Controls, Edit",Active\r
            Dot,"., Dot",KDN,"All KDN Controls, Read only",Active\r
            Old,"Master, Old",Master,"My Controls, Edit",Active\r
            Mark,"Smith, Mark",User,"My Controls, Edit",Active\r
            """;
    private static final List<String> NAMES = List.of("Testova", "Primerov", "Smith", "O'Neil", "Leaver", "Oldrole", "Anna", "Boris", "Maria");

    @Autowired
    private UserImport userImport;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminAuditLogRepository auditLogRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path folder;

    @BeforeEach
    void emptyDatabase() {
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void dryRun_reportsEveryLine_writesOnlyTheLoginsFile() throws IOException {
        UserImport.Report report = userImport.run(file(PEOPLE), false, "tester", TODAY);

        assertThat(report.refused()).isFalse();
        assertThat(report.encoding()).isEqualTo("UTF-8");
        assertThat(report.count(UserImport.Outcome.CREATE)).isEqualTo(6);
        assertThat(logins(report, UserImport.Outcome.CREATE)).containsExactly(
                "atestova", "bprimerov", "msmith2", "msmith", "koneildow", "gleaver");
        assertThat(report.lines(UserImport.Outcome.SERVICE_ACCOUNT)).extracting(UserImport.Line::line).containsExactly(8);
        Map<Integer, String> errors = report.lines(UserImport.Outcome.ERROR).stream()
                .collect(Collectors.toMap(UserImport.Line::line, UserImport.Line::reason));
        assertThat(errors).containsOnlyKeys(9, 10, 11);
        assertThat(errors.get(9)).contains("the surname is \".\"");
        assertThat(errors.get(10)).contains("unknown Role \"Master\"");
        assertThat(errors.get(11)).isEqualTo("the same User as line 4");

        String console = report.render();
        assertThat(console).contains("DRY RUN, nothing written")
                .contains("would create: 6 (SoQM Team 2, User 3, KDN 1, inactive 1)")
                .contains("service accounts: 1, errors: 3")
                .contains("line   2  a******a")
                .contains("line   8  skipped: service account")
                .contains("Run again with --apply");
        NAMES.forEach(name -> assertThat(console).doesNotContain(name));
        assertThat(console).doesNotContain("atestova").doesNotContain("@qtracker.local");

        assertThat(userRepository.count()).isZero();
        assertThat(auditLogRepository.count()).isZero();
        Path loginsFile = folder.resolve("import_logins_2026-10-08.csv");
        assertThat(report.loginsFile()).isEqualTo(loginsFile);
        assertThat(Files.readString(loginsFile, StandardCharsets.UTF_8))
                .contains("2,\"Testova, Anna\",atestova,\"would create\"")
                .contains("4,\"Smith, Mark\",msmith2,\"would create\"")
                .doesNotContain("svc-qt-robot");
    }

    @Test
    void apply_createsTheAccounts_withTheTemporaryPassword_andAnAuditEntryEach() throws IOException {
        UserImport.Report report = userImport.run(file(PEOPLE), true, "tester", TODAY);

        assertThat(report.render()).contains("APPLIED").contains("created: 6");
        Map<String, User> users = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getMail, Function.identity()));
        assertThat(users).containsOnlyKeys("atestova@qtracker.local", "bprimerov@qtracker.local",
                "msmith2@qtracker.local", "msmith@qtracker.local", "koneildow@qtracker.local", "gleaver@qtracker.local");

        User soqm = users.get("atestova@qtracker.local");
        assertThat(soqm.getDisplayName()).isEqualTo("Testova, Anna");
        assertThat(soqm.getAccessLevel()).isEqualTo(AccessLevel.SOQM);
        assertThat(soqm.getAccessScope()).isEqualTo(AccessScope.ALL);
        assertThat(soqm.getAdminAccess()).isTrue();
        assertThat(soqm.getRole()).isNull();
        assertThat(passwordEncoder.matches("aaa", soqm.getPassword())).isTrue();
        assertThat(users.get("msmith2@qtracker.local").getAccessLevel()).isEqualTo(AccessLevel.PARTICIPANT);
        assertThat(users.get("msmith2@qtracker.local").getAccessScope()).isEqualTo(AccessScope.OWN);
        assertThat(users.get("msmith@qtracker.local").getAccessLevel()).isEqualTo(AccessLevel.READ_ONLY);
        assertThat(users.get("msmith@qtracker.local").getAccessScope()).isEqualTo(AccessScope.ALL);
        assertThat(users.get("koneildow@qtracker.local").getAccessScope()).isEqualTo(AccessScope.KDN);
        assertThat(users.get("koneildow@qtracker.local").getAdminAccess()).isFalse();
        assertThat(users.get("gleaver@qtracker.local").getEnabled()).isFalse();

        List<AdminAuditLog> audit = auditLogRepository.findAll();
        assertThat(audit).hasSize(6).allSatisfy(entry -> {
            assertThat(entry.getActionType()).isEqualTo("USER_CREATE");
            assertThat(entry.getAdminEmail()).isEqualTo("system");
            assertThat(entry.getAdminName()).isEqualTo("User import (tester)");
            assertThat(entry.getActionDescription()).startsWith("Created user ");
        });
        assertThat(Files.readString(report.loginsFile())).contains("atestova,\"created\"");
    }

    @Test
    void theSameFileAgain_changesNothing() throws IOException {
        Path people = file(PEOPLE);
        userImport.run(people, true, "tester", TODAY);
        Map<String, String> passwords = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getMail, User::getPassword));

        UserImport.Report again = userImport.run(people, true, "tester", TODAY);

        assertThat(again.count(UserImport.Outcome.CREATE)).isZero();
        assertThat(again.count(UserImport.Outcome.UNCHANGED)).isEqualTo(6);
        assertThat(again.render()).contains("created: 0, unchanged: 6");
        assertThat(userRepository.findAll().stream().collect(Collectors.toMap(User::getMail, User::getPassword)))
                .isEqualTo(passwords);
        assertThat(auditLogRepository.count()).isEqualTo(6);
    }

    @Test
    void anAccountChangedSinceTheImport_isLeftAsItIs() throws IOException {
        Path people = file(PEOPLE);
        userImport.run(people, true, "tester", TODAY);
        User maria = userRepository.findByMail("msmith@qtracker.local").orElseThrow();
        maria.setAccessLevel(AccessLevel.PARTICIPANT);
        maria.setEnabled(false);
        userRepository.save(maria);

        UserImport.Report again = userImport.run(people, true, "tester", TODAY);

        assertThat(again.lines(UserImport.Outcome.DIFFERS)).singleElement().satisfies(line -> {
            assertThat(line.line()).isEqualTo(5);
            assertThat(line.reason()).contains("has User / All controls / Edit, the file says User / All controls / Read Only")
                    .contains("inactive, the file says active");
        });
        User after = userRepository.findByMail("msmith@qtracker.local").orElseThrow();
        assertThat(after.getAccessLevel()).isEqualTo(AccessLevel.PARTICIPANT);
        assertThat(after.getEnabled()).isFalse();
    }

    @Test
    void theImportedPeople_signInWithTheirLogin_onlySoqmTeamOpensTheAdminPanel() throws Exception {
        userImport.run(file(PEOPLE), true, "tester", TODAY);

        mockMvc.perform(get("/admin/users").session(signIn(" ATestova ")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-users"));
        mockMvc.perform(get("/admin/users").session(signIn("msmith2")))
                .andExpect(redirectedUrl("/"));
        mockMvc.perform(get("/admin/users").session(signIn("koneildow@QTracker.local")))
                .andExpect(redirectedUrl("/"));
        MvcResult inactive = mockMvc.perform(post("/login").with(csrf())
                        .param("username", "gleaver")
                        .param("password", "aaa"))
                .andReturn();
        assertThat(inactive.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
    }

    @Test
    void withoutTwoActiveSoqmTeamMembers_theImportDoesNotRun() throws IOException {
        UserImport.Report report = userImport.run(file("""
                User,Role,Access,Status
                "Testova, Anna",SoQM Team,SoQM Team,Active
                "Primerov, Boris",SoQM Team,SoQM Team,Inactive
                "Smith, Mark",User,"My Controls, Edit",Active
                """), true, "tester", TODAY);

        assertThat(report.refused()).isTrue();
        assertThat(report.render()).contains("REFUSED, nothing written")
                .contains("at least 2 active SoQM Team members").contains("found 1");
        assertThat(userRepository.count()).isZero();
        assertThat(auditLogRepository.count()).isZero();
        assertThat(folder.resolve("import_logins_2026-10-08.csv")).doesNotExist();
    }

    @Test
    void outsideDevAndTest_theTemporaryPasswordIsRefused() throws IOException {
        UserRepository repository = mock(UserRepository.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        MockEnvironment stage = new MockEnvironment();
        stage.setActiveProfiles("stage");
        UserImport outsideTests = new UserImport(repository, passwordEncoder, audit,
                new LoginNameResolver(true, "qtracker.local"), stage);

        UserImport.Report report = outsideTests.run(file(PEOPLE), true, "tester", TODAY);

        assertThat(report.refused()).isTrue();
        assertThat(report.refusal()).contains("temporary password").contains("dev and test").contains("stage");
        verifyNoInteractions(repository, audit);
        assertThat(folder.resolve("import_logins_2026-10-08.csv")).doesNotExist();
    }

    @Test
    void fromTheCommandLine_dryRunByDefault_writesOnlyWithApply(CapturedOutput output) throws IOException {
        Path people = file(PEOPLE);

        assertThat(command("--import-users", people.toString())).isZero();
        assertThat(output.getOut()).contains("DRY RUN, nothing written").contains("would create: 6");
        assertThat(userRepository.count()).isZero();

        assertThat(command("--import-users=" + people, "--apply")).isZero();
        assertThat(output.getOut()).contains("APPLIED").contains("created: 6");
        assertThat(userRepository.count()).isEqualTo(6);

        assertThat(command("--import-users")).isEqualTo(1);
        assertThat(output.getOut()).contains("give the file");
        assertThat(command("--import-users", folder.resolve("missing.csv").toString())).isEqualTo(1);
        assertThat(output.getOut()).contains("the file cannot be read");
    }

    // The command's own context: no web server, the schema and rows of this test's database kept
    private static int command(String... options) {
        List<String> args = new java.util.ArrayList<>(List.of(options));
        args.addAll(List.of(
                "--spring.profiles.active=test,dev",
                "--spring.datasource.url=" + DB,
                "--spring.jpa.hibernate.ddl-auto=none",
                "--reminders.enabled=false",
                "--controls.auto-create.enabled=false",
                "--file.upload.dir=target/it-uploads-user-import"));
        try (ConfigurableApplicationContext context = QtrackerApplication.runCommand(args.toArray(String[]::new))) {
            return SpringApplication.exit(context);
        }
    }

    private MockHttpSession signIn(String login) throws Exception {
        MvcResult result = mockMvc.perform(post("/login").with(csrf())
                        .param("username", login)
                        .param("password", "aaa"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/");
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private Path file(String text) throws IOException {
        Path file = folder.resolve("people.csv");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private static List<String> logins(UserImport.Report report, UserImport.Outcome outcome) {
        return report.lines(outcome).stream().map(UserImport.Line::login).toList();
    }
}
