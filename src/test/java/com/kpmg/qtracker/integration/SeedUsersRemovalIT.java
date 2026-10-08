package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.QtrackerApplication;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.userimport.SeedUsersRemoval;
import com.kpmg.qtracker.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Removing the Flyway seed accounts on the clean-slate path: only for the local QTracker database and only
 * while it has no control. The test database is H2, so the command refuses it; the service is given the
 * address of the local database to show what it does there.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=" + SeedUsersRemovalIT.DB,
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "reminders.enabled=false",
        "controls.auto-create.enabled=false",
        "file.upload.dir=target/it-uploads-seed-removal"
})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SeedUsersRemovalIT {

    static final String DB = "jdbc:h2:mem:seed-removal-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    private static final String LOCAL = "jdbc:postgresql://localhost:5432/QTracker";

    @Autowired
    private SeedUsersRemoval removal;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ControlRepository controlRepository;
    @Autowired
    private AdminAuditLogRepository auditLogRepository;

    @BeforeEach
    void freshDatabase() {
        auditLogRepository.deleteAll();
        controlRepository.deleteAll();
        userRepository.deleteAll();
        userRepository.save(TestUsers.user("soqm1@qtracker.local", "SOQM_TEAM"));
        userRepository.save(TestUsers.user("admin@qtracker.local", "ADMIN"));
        userRepository.save(TestUsers.user("po2@qtracker.local", "PROCESS_OWNER"));
        userRepository.save(TestUsers.user("jimported@qtracker.local", AccessLevel.SOQM, AccessScope.ALL, true));
    }

    @Test
    void dryRun_listsTheSeedAccounts_andWritesNothing() {
        SeedUsersRemoval.Report report = removal.run(false, LOCAL, "tester");

        assertThat(report.refused()).isFalse();
        assertThat(report.removed()).containsExactly("admin@qtracker.local", "soqm1@qtracker.local", "po2@qtracker.local");
        assertThat(report.otherUsers()).isEqualTo(1);
        assertThat(report.render()).contains("DRY RUN, nothing written").contains("would remove: 3")
                .contains("--apply");
        assertThat(userRepository.count()).isEqualTo(4);
        assertThat(auditLogRepository.count()).isZero();
    }

    @Test
    void apply_removesTheSeedAccountsOnly_withAnAuditEntryEach() {
        SeedUsersRemoval.Report report = removal.run(true, LOCAL, "tester");

        assertThat(report.render()).contains("APPLIED").contains("removed: 3").contains("other users kept: 1");
        assertThat(userRepository.findAll()).extracting(User::getMail).containsExactly("jimported@qtracker.local");
        List<AdminAuditLog> audit = auditLogRepository.findAll();
        assertThat(audit).hasSize(3).allSatisfy(entry -> {
            assertThat(entry.getActionType()).isEqualTo("USER_DELETE");
            assertThat(entry.getAdminName()).isEqualTo("Seed users removal (tester)");
        });
        assertThat(removal.run(true, LOCAL, "tester").removed()).isEmpty();
    }

    @Test
    void anotherDatabase_isRefused() {
        SeedUsersRemoval.Report report = removal.run(true, "jdbc:postgresql://db.example.test:5432/QTracker", "tester");

        assertThat(report.refused()).isTrue();
        assertThat(report.render()).contains("REFUSED, nothing written").contains("not on this computer");
        assertThat(userRepository.count()).isEqualTo(4);
    }

    @Test
    void aDatabaseWithAControl_isRefused() {
        Control control = new Control();
        control.setControlId("SEED-C1");
        control.setControlStatus("ACTIVE");
        control.setPerformanceStatus("DRAFT");
        control.setControlDescription("Made-up control");
        control.setCreatedAt(LocalDateTime.of(2026, 10, 1, 9, 0));
        controlRepository.save(control);

        SeedUsersRemoval.Report report = removal.run(true, LOCAL, "tester");

        assertThat(report.refusal()).contains("holds 1 control");
        assertThat(userRepository.count()).isEqualTo(4);
    }

    @Test
    void fromTheCommandLine_theTestDatabaseIsNotTheLocalQTracker_soNothingIsRemoved(CapturedOutput output) {
        int exitCode;
        try (ConfigurableApplicationContext context = QtrackerApplication.runCommand(
                "--remove-seed-users", "--apply",
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + DB,
                "--spring.jpa.hibernate.ddl-auto=none",
                "--reminders.enabled=false",
                "--controls.auto-create.enabled=false",
                "--file.upload.dir=target/it-uploads-seed-removal")) {
            exitCode = SpringApplication.exit(context);
        }

        assertThat(exitCode).isEqualTo(1);
        assertThat(output.getOut()).contains("REFUSED, nothing written")
                .contains("not a PostgreSQL database on this computer: jdbc:h2:mem");
        assertThat(userRepository.count()).isEqualTo(4);
    }
}
