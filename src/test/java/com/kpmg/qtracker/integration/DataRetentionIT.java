package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.config.DataRetentionCommand;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.ControlRenameService;
import com.kpmg.qtracker.service.DataRetention;
import com.kpmg.qtracker.service.FileStorageService;
import com.kpmg.qtracker.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data retention, seven years from creation (business decision of 2026-11-05): a control older than that goes with
 * everything of it - fields, attachments and their files (in the folder of its ID and of an earlier ID), workflow
 * steps and history, notifications, e-mails, audit entries -, a younger control and one without a creation date stay,
 * a next cycle stays and only loses its link. Older audit entries, notifications, e-mails and deleted-user records go
 * too. A dry run deletes nothing; fewer than seven years is refused; one audit entry names what went.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class DataRetentionIT {

    @Autowired
    private DataRetention dataRetention;
    @Autowired
    private DataRetentionCommand command;
    @Autowired
    private ControlRepository controlRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminAuditLogRepository auditRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ControlRenameService controlRenameService;
    @Autowired
    private FileStorageService fileStorageService;
    @Autowired
    private AdminAuditService auditService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private Clock clock;
    @Value("${file.upload.dir}")
    private String uploadDir;

    private final List<Long> controls = new ArrayList<>();
    private final List<Path> folders = new ArrayList<>();
    private User user;
    private String s;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        s = UUID.randomUUID().toString().substring(0, 8);
        now = LocalDateTime.now(clock);
        user = new User();
        TestUsers.withRole(user, "SOQM_TEAM");
        user.setMail("dr-" + s + "@example.test");
        user.setDisplayName("Retention " + s);
        user.setEnabled(true);
        user = userRepository.save(user);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Long id : controls) {
            for (String table : List.of("control_attachments", "workflow_steps", "workflow_history", "notifications",
                    "email_outbox", "admin_audit_log")) {
                jdbc.update("DELETE FROM " + table + " WHERE control_id = ?", id);
            }
            jdbc.update("UPDATE controls SET created_from_control_id = NULL WHERE created_from_control_id = ?", id);
        }
        for (Long id : controls) {
            jdbc.update("DELETE FROM controls WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM admin_audit_log WHERE admin_email = ? OR action_description LIKE ?",
                user.getMail(), "%" + s + "%");
        jdbc.update("DELETE FROM admin_audit_log WHERE action_type = ?", DataRetention.AUDIT_ACTION);
        jdbc.update("DELETE FROM deleted_users WHERE mail LIKE ?", "%" + s + "%");
        userRepository.deleteById(user.getId());
        for (Path folder : folders) {
            if (Files.isDirectory(folder)) {
                try (var files = Files.list(folder)) {
                    for (Path file : files.toList()) {
                        Files.deleteIfExists(file);
                    }
                }
                Files.deleteIfExists(folder);
            }
        }
    }

    // ------------------------------------------------------------------ the purge

    @Test
    void aDryRun_listsTheExpiredControlAndItsRows_andDeletesNothing() throws Exception {
        Long old = controlWithEverything("OLD", now.minusYears(7).minusDays(1));
        Long young = controlWithEverything("YOUNG", now.minusYears(7).plusDays(1));

        DataRetention.Report report = dataRetention.run(false, "tester");

        assertThat(report.refused()).isFalse();
        assertThat(report.applied()).isFalse();
        assertThat(report.controls()).extracting(DataRetention.Expired::id).contains(old).doesNotContain(young);
        assertThat(report.rows().get("workflow_history")).isGreaterThanOrEqualTo(1);
        assertThat(report.files()).isGreaterThanOrEqualTo(2);
        assertThat(report.render()).contains("DRY RUN, nothing deleted", "DR-OLD-" + s, "Run again with --apply");
        assertThat(controlRepository.existsById(old)).isTrue();
        assertThat(rows("workflow_history", old)).isEqualTo(1);
        assertThat(Files.exists(file("DR-OLD-" + s, "evidence.pdf"))).isTrue();
        assertThat(auditRepository.findAll()).noneMatch(entry -> DataRetention.AUDIT_ACTION.equals(entry.getActionType()));
    }

    @Test
    void apply_deletesTheExpiredControlWithEverythingOfIt_andKeepsTheYoungAndTheUndated() throws Exception {
        Long old = controlWithEverything("OLD", now.minusYears(7).minusDays(1));
        Long young = controlWithEverything("YOUNG", now.minusYears(7).plusDays(1));
        Long undated = controlWithEverything("UNDATED", null);
        // The next cycle made from the expired control, itself recent
        Long next = control("NEXT", now.minusMonths(2));
        jdbc.update("UPDATE controls SET created_from_control_id = ? WHERE id = ?", old, next);
        // Records older than seven years that belong to no control, and younger ones
        audit(null, now.minusYears(8), "old admin action " + s);
        audit(null, now.minusYears(1), "recent admin action " + s);
        deletedUser("gone-old", now.minusYears(8));
        deletedUser("gone-recent", now.minusYears(1));

        DataRetention.Report report = dataRetention.run(true, "tester");

        assertThat(report.applied()).isTrue();
        assertThat(report.fileProblems()).isEmpty();
        assertThat(report.controls()).extracting(DataRetention.Expired::controlId).contains("DR-OLD-" + s)
                .doesNotContain("DR-YOUNG-" + s, "DR-UNDATED-" + s, "DR-NEXT-" + s);

        // The expired control and everything of it
        assertThat(controlRepository.existsById(old)).isFalse();
        for (String table : List.of("control_attachments", "workflow_steps", "workflow_history", "notifications",
                "email_outbox", "admin_audit_log")) {
            assertThat(rows(table, old)).as(table).isZero();
        }
        assertThat(Files.exists(file("DR-OLD-" + s, "evidence.pdf"))).isFalse();
        assertThat(Files.exists(file("DR-OLD-" + s + "-EARLIER", "earlier.pdf"))).as("file under the earlier ID").isFalse();

        // The young one, the undated one and the next cycle stay whole
        for (Long kept : List.of(young, undated)) {
            assertThat(controlRepository.existsById(kept)).isTrue();
            for (String table : List.of("control_attachments", "workflow_steps", "workflow_history", "notifications",
                    "email_outbox")) {
                assertThat(rows(table, kept)).as(table + " of " + kept).isEqualTo(1);
            }
        }
        assertThat(Files.exists(file("DR-YOUNG-" + s, "evidence.pdf"))).isTrue();
        assertThat(controlRepository.findById(next)).hasValueSatisfying(
                control -> assertThat(control.getCreatedFromControlId()).isNull());
        assertThat(report.undated()).isGreaterThanOrEqualTo(1);

        // Older records of no control go, younger ones stay
        assertThat(count("SELECT COUNT(*) FROM admin_audit_log WHERE action_description = ?", "old admin action " + s)).isZero();
        assertThat(count("SELECT COUNT(*) FROM admin_audit_log WHERE action_description = ?", "recent admin action " + s)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM deleted_users WHERE mail = ?", "gone-old-" + s + "@example.test")).isZero();
        assertThat(count("SELECT COUNT(*) FROM deleted_users WHERE mail = ?", "gone-recent-" + s + "@example.test")).isEqualTo(1);

        // One audit entry names what went
        List<AdminAuditLog> purge = auditRepository.findAll().stream()
                .filter(entry -> DataRetention.AUDIT_ACTION.equals(entry.getActionType())).toList();
        assertThat(purge).singleElement().satisfies(entry -> {
            assertThat(entry.getAdminEmail()).isEqualTo("system");
            assertThat(entry.getAdminName()).isEqualTo("Data retention (tester)");
            assertThat(entry.getNewValues()).contains("DR-OLD-" + s).doesNotContain("DR-YOUNG-" + s);
        });

        // Run again: nothing more of these
        DataRetention.Report again = dataRetention.run(true, "tester");
        assertThat(again.controls()).extracting(DataRetention.Expired::id).doesNotContain(old, young, undated, next);
        assertThat(controlRepository.existsById(young)).isTrue();
    }

    @Test
    void fewerThanSevenYears_isRefused_andDeletesNothing() throws Exception {
        Long old = controlWithEverything("OLD", now.minusYears(7).minusDays(1));
        DataRetention sixYears = new DataRetention(controlRepository, controlRenameService, fileStorageService,
                auditService, jdbc, transactionManager, clock, 6);

        DataRetention.Report report = sixYears.run(true, "tester");

        assertThat(report.refused()).isTrue();
        assertThat(report.render()).contains("REFUSED, nothing deleted", "retention.years is 6");
        assertThat(controlRepository.existsById(old)).isTrue();
        assertThat(Files.exists(file("DR-OLD-" + s, "evidence.pdf"))).isTrue();
    }

    @Test
    void theCommand_printsTheReport_andDeletesOnlyWithApply(CapturedOutput output) throws Exception {
        Long old = controlWithEverything("OLD", now.minusYears(7).minusDays(3));

        command.run(new DefaultApplicationArguments("--purge-expired"));
        assertThat(command.getExitCode()).isZero();
        assertThat(output.getOut()).contains("Data retention: DRY RUN, nothing deleted", "DR-OLD-" + s);
        assertThat(controlRepository.existsById(old)).isTrue();

        command.run(new DefaultApplicationArguments("--purge-expired", "--apply"));
        assertThat(command.getExitCode()).isZero();
        assertThat(output.getOut()).contains("Data retention: APPLIED");
        assertThat(controlRepository.existsById(old)).isFalse();

        assertThat(DataRetentionCommand.requested("--purge-expired")).isTrue();
        assertThat(DataRetentionCommand.requested("--import-users=x.csv")).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private Long control(String name, LocalDateTime createdAt) {
        Control control = new Control();
        control.setControlId("DR-" + name + "-" + s);
        control.setControlFrequency("Monthly");
        control.setControlStatus("ACTIVE");
        control.setPerformanceStatus("COMPLETED");
        Long id = controlRepository.save(control).getId();
        controls.add(id);
        jdbc.update("UPDATE controls SET created_at = ? WHERE id = ?",
                createdAt == null ? null : Timestamp.valueOf(createdAt), id);
        return id;
    }

    /** A control with one row in every table that has a control_id, and an attachment file. */
    private Long controlWithEverything(String name, LocalDateTime createdAt) throws Exception {
        Long id = control(name, createdAt);
        String controlId = "DR-" + name + "-" + s;
        LocalDateTime at = createdAt == null ? now.minusDays(1) : createdAt;
        jdbc.update("INSERT INTO control_attachments (control_id, tab, file_name) VALUES (?, 'DETAILS', 'evidence.pdf')", id);
        writeFile(controlId, "evidence.pdf");
        jdbc.update("INSERT INTO workflow_steps (control_id) VALUES (?)", id);
        jdbc.update("INSERT INTO workflow_history (control_id, on_behalf, performed_by_email, created_at) VALUES (?, FALSE, ?, ?)",
                id, user.getMail(), Timestamp.valueOf(at));
        jdbc.update("INSERT INTO notifications (user_id, control_id, type, title, message, is_read, created_at)"
                + " VALUES (?, ?, 'REMINDER', 't', 'm', FALSE, ?)", user.getId(), id, Timestamp.valueOf(at));
        jdbc.update("INSERT INTO email_outbox (control_id, template_key, recipient_user_id, recipient_email, subject,"
                        + " body_html, importance, dedupe_key, status, attempts, created_at)"
                        + " VALUES (?, 'T3', ?, ?, 's', '<p>b</p>', 'NORMAL', ?, 'SENT', 0, ?)",
                id, user.getId(), user.getMail(), "dr-" + s + "-" + id, Timestamp.valueOf(at));
        if ("OLD".equals(name)) {
            // The control had another ID before (Rename ID): its audit entry names it, a file stayed in its folder
            jdbc.update("INSERT INTO admin_audit_log (admin_email, action_type, control_id, control_control_id,"
                    + " action_description, created_at) VALUES (?, 'ATTACHMENT_ADDED', ?, ?, ?, ?)",
                    user.getMail(), id, controlId + "-EARLIER", "upload under the earlier ID " + s, Timestamp.valueOf(at));
            jdbc.update("INSERT INTO control_attachments (control_id, tab, file_name) VALUES (?, 'DOCUMENTS', 'earlier.pdf')", id);
            writeFile(controlId + "-EARLIER", "earlier.pdf");
        } else {
            audit(id, at, "edit of " + controlId);
        }
        return id;
    }

    private void audit(Long controlId, LocalDateTime at, String description) {
        jdbc.update("INSERT INTO admin_audit_log (admin_email, action_type, control_id, action_description, created_at)"
                + " VALUES (?, 'EDIT', ?, ?, ?)", user.getMail(), controlId, description, Timestamp.valueOf(at));
    }

    private void deletedUser(String name, LocalDateTime deletedAt) {
        jdbc.update("INSERT INTO deleted_users (user_id, mail, deleted_at, deleted_by_email) VALUES (?, ?, ?, ?)",
                900000L + Math.abs(name.hashCode() % 1000), name + "-" + s + "@example.test",
                Timestamp.valueOf(deletedAt), user.getMail());
    }

    private Path file(String folder, String name) {
        return Paths.get(uploadDir).toAbsolutePath().normalize().resolve(folder).resolve(name);
    }

    private void writeFile(String folder, String name) throws Exception {
        Path path = file(folder, name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "evidence");
        folders.add(path.getParent());
    }

    private int rows(String table, Long controlId) {
        return count("SELECT COUNT(*) FROM " + table + " WHERE control_id = ?", controlId);
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
}
