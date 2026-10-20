package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.QtrackerApplication;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.userimport.UserEmailUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * --update-user-emails on made-up people: the imported address (login@qtracker.local) becomes the corporate one.
 * Dry run by default, all or nothing, idempotent, also for people who signed in before, audited before and after,
 * no address in the console.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=" + UserEmailUpdateIT.DB,
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "reminders.enabled=false",
        "file.upload.dir=target/it-uploads-email-update"
})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class UserEmailUpdateIT {

    static final String DB = "jdbc:h2:mem:email-update-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    @Autowired
    private UserEmailUpdate update;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminAuditLogRepository auditLogRepository;

    @TempDir
    private Path folder;

    private User anna;
    private User boris;
    private User clara;

    @BeforeEach
    void people() {
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
        anna = user("atestova@qtracker.local", "Testova Anna");
        boris = user("bprimerov@qtracker.local", "Primerov  Boris");
        // Clara has signed in before (with the password, on stage) and is linked to a Microsoft account
        clara = user("csmith@qtracker.local", "Smith Clara");
        clara.setLastLoginAt(LocalDateTime.of(2026, 10, 1, 9, 0));
        clara.setEntraOid("oid-clara");
        clara = userRepository.save(clara);
    }

    @Test
    void aDryRun_changesNothing_andTheConsoleNamesNoAddress(CapturedOutput output) throws Exception {
        UserEmailUpdate.Report report = update.run(file("""
                User,Email\r
                atestova,Anna.Testova@corp.example.test\r
                primerov boris,boris.primerov@corp.example.test\r
                """), false, "tester", TODAY);

        assertThat(report.refused()).isFalse();
        assertThat(report.applied()).isFalse();
        assertThat(report.count(UserEmailUpdate.Outcome.CHANGE)).isEqualTo(2);
        assertThat(userRepository.findById(anna.getId()).orElseThrow().getMail()).isEqualTo("atestova@qtracker.local");
        assertThat(auditLogRepository.count()).isZero();

        System.out.print(report.render());
        assertThat(output.getOut()).contains("DRY RUN").contains("line 2: user " + anna.getId())
                .doesNotContain("@corp.example.test").doesNotContain("@qtracker.local").doesNotContain("Testova");
        assertThat(Files.readString(report.listFile(), StandardCharsets.UTF_8))
                .contains("anna.testova@corp.example.test").contains("atestova@qtracker.local");
    }

    @Test
    void apply_byLoginAndByName_alsoForSomeoneWhoSignedIn_audited_andAgainChangesNothing() throws Exception {
        Path file = file("""
                User,Email\r
                atestova,anna.testova@corp.example.test\r
                PRIMEROV BORIS,boris.primerov@corp.example.test\r
                csmith,clara.smith@corp.example.test\r
                """);

        UserEmailUpdate.Report applied = update.run(file, true, "tester", TODAY);

        assertThat(applied.applied()).isTrue();
        assertThat(userRepository.findById(anna.getId()).orElseThrow().getMail()).isEqualTo("anna.testova@corp.example.test");
        assertThat(userRepository.findById(boris.getId()).orElseThrow().getMail()).isEqualTo("boris.primerov@corp.example.test");
        User claraAfter = userRepository.findById(clara.getId()).orElseThrow();
        assertThat(claraAfter.getMail()).isEqualTo("clara.smith@corp.example.test");
        assertThat(claraAfter.getEntraOid()).isEqualTo("oid-clara");
        List<AdminAuditLog> audit = auditLogRepository.findByActionTypeOrderByCreatedAtDesc("USER_EMAIL_UPDATE");
        assertThat(audit).hasSize(3).anySatisfy(log -> {
            assertThat(log.getPreviousValues()).isEqualTo("mail=csmith@qtracker.local");
            assertThat(log.getNewValues()).isEqualTo("mail=clara.smith@corp.example.test");
            assertThat(log.getAdminName()).isEqualTo("E-mail update (tester)");
        });

        UserEmailUpdate.Report again = update.run(file, true, "tester", TODAY);
        assertThat(again.refused()).isFalse();
        assertThat(again.count(UserEmailUpdate.Outcome.UNCHANGED)).isEqualTo(3);
        assertThat(auditLogRepository.count()).isEqualTo(3);
    }

    @Test
    void duplicates_theServiceDomain_someoneElsesAddress_andUnknownPeople_refuseTheWholeFile() throws Exception {
        assertRefused("""
                User,Email\r
                atestova,anna@corp.example.test\r
                Testova Anna,anna.second@corp.example.test\r
                """, "the same user as line 2");
        assertRefused("""
                User,Email\r
                atestova,shared@corp.example.test\r
                bprimerov,shared@corp.example.test\r
                """, "the same Email as line 2");
        assertRefused("""
                User,Email\r
                atestova,anna.new@qtracker.local\r
                bprimerov,boris@corp.example.test\r
                """, "service domain");
        User erik = user("erik@corp.example.test", "Erik Moved");
        assertRefused("""
                User,Email\r
                atestova,Erik@corp.example.test\r
                """, "already belongs to user " + erik.getId());
        assertRefused("""
                User,Email\r
                nobody,nobody@corp.example.test\r
                """, "no user with this login or name");
        user("dsmith@qtracker.local", "Smith Clara");
        assertRefused("""
                User,Email\r
                Smith Clara,clara@corp.example.test\r
                """, "2 users have this name");
        assertRefused("""
                User,Email\r
                atestova,not-an-address\r
                """, "not an e-mail address");
    }

    @Test
    void theCommand_isADryRunUnlessApplied(CapturedOutput output) throws Exception {
        Path file = file("""
                User,Email\r
                atestova,anna.testova@corp.example.test\r
                """);

        assertThat(command("--update-user-emails", file.toString())).isZero();
        assertThat(userRepository.findById(anna.getId()).orElseThrow().getMail()).isEqualTo("atestova@qtracker.local");
        assertThat(command("--update-user-emails=" + file, "--apply")).isZero();
        assertThat(userRepository.findById(anna.getId()).orElseThrow().getMail()).isEqualTo("anna.testova@corp.example.test");
        assertThat(command("--update-user-emails")).isEqualTo(1);
        assertThat(output.getOut()).contains("DRY RUN").contains("APPLIED").contains("give the file")
                .doesNotContain("anna.testova@corp.example.test");
    }

    private void assertRefused(String csv, String reason) throws Exception {
        List<String> before = userRepository.findAll().stream().map(User::getMail).sorted().toList();
        UserEmailUpdate.Report report = update.run(file(csv), true, "tester", TODAY);

        assertThat(report.refused()).as(reason).isTrue();
        assertThat(report.lines()).as(reason).anySatisfy(line -> assertThat(line.reason()).contains(reason));
        assertThat(userRepository.findAll().stream().map(User::getMail).sorted().toList()).isEqualTo(before);
        assertThat(auditLogRepository.count()).isZero();
    }

    private Path file(String csv) throws Exception {
        Path file = folder.resolve("emails.csv");
        Files.writeString(file, csv, StandardCharsets.UTF_8);
        return file;
    }

    private User user(String mail, String name) {
        User user = new User();
        user.setMail(mail);
        user.setDisplayName(name);
        user.setAccessLevel(AccessLevel.PARTICIPANT);
        user.setAccessScope(AccessScope.OWN);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    // The command's own context: no web server, the schema and rows of this test's database kept
    private static int command(String... options) {
        List<String> args = new java.util.ArrayList<>(List.of(options));
        args.addAll(List.of(
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + DB,
                "--spring.jpa.hibernate.ddl-auto=none",
                "--reminders.enabled=false",
                "--file.upload.dir=target/it-uploads-email-update"));
        try (ConfigurableApplicationContext context = QtrackerApplication.runCommand(args.toArray(String[]::new))) {
            return SpringApplication.exit(context);
        }
    }
}
