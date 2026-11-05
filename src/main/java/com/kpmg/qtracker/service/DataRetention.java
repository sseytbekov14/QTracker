package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.repository.ControlRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Data retention (business decision of 2026-11-05): QTracker keeps its data seven years and then deletes it. A
 * control goes seven years after it was created ({@code controls.created_at}), with everything of it: its fields
 * (Details and Assignment are in the same row), its attachments and their files, its workflow steps and history, its
 * notifications, its e-mails and its audit entries. A next cycle made from it stays (its own creation date counts) and
 * only loses the link to it. Besides, every audit entry, in-app notification and e-mail older than seven years goes,
 * and so does the record of a user deleted more than seven years ago. A control without a creation date is never
 * deleted (it is reported). Active users are never deleted here.
 * <p>A dry run by default ({@link #run}); with apply the database part is one transaction and one audit entry names
 * the deleted Control IDs; the files go after it commits (a file that cannot be deleted is reported, not fatal).
 * Fewer than {@link #MIN_YEARS} years is refused, so a wrong setting cannot delete young data.
 */
@Service
@Slf4j
public class DataRetention {

    /** The retention of the business; retention.years may be more, never less. */
    public static final int MIN_YEARS = 7;
    public static final String AUDIT_ACTION = "DATA_RETENTION_PURGE";
    static final String ACTOR_MAIL = "system";

    private final ControlRepository controlRepository;
    private final ControlRenameService controlRenameService;
    private final FileStorageService fileStorageService;
    private final AdminAuditService auditService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int years;

    public DataRetention(ControlRepository controlRepository, ControlRenameService controlRenameService,
                         FileStorageService fileStorageService, AdminAuditService auditService, JdbcTemplate jdbc,
                         PlatformTransactionManager transactionManager, Clock clock,
                         @Value("${retention.years:7}") int years) {
        this.controlRepository = controlRepository;
        this.controlRenameService = controlRenameService;
        this.fileStorageService = fileStorageService;
        this.auditService = auditService;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.years = years;
    }

    /** A control past its retention. */
    public record Expired(Long id, String controlId, LocalDateTime createdAt) {
    }

    /**
     * @param controls     the controls past their retention, oldest first
     * @param rows         how many rows each table loses (or would lose), by table
     * @param files        how many attachment files go (or would go)
     * @param fileProblems files that could not be deleted (apply only)
     * @param undated      controls without a creation date, never deleted
     */
    public record Report(boolean applied, String refusal, int years, LocalDateTime cutoff, List<Expired> controls,
                         Map<String, Integer> rows, int files, List<String> fileProblems, int undated) {

        public boolean refused() {
            return refusal != null;
        }

        public String render() {
            String nl = System.lineSeparator();
            StringBuilder out = new StringBuilder("Data retention: ");
            if (refused()) {
                return out.append("REFUSED, nothing deleted: ").append(refusal).append(nl).toString();
            }
            out.append(applied ? "APPLIED" : "DRY RUN, nothing deleted").append(nl);
            out.append("Kept ").append(years).append(" years: everything created before ").append(cutoff).append(nl);
            String verb = applied ? "deleted" : "would be deleted";
            out.append("Controls ").append(verb).append(": ").append(controls.size()).append(nl);
            for (Expired control : controls) {
                out.append("  ").append(control.controlId()).append(" (id ").append(control.id())
                        .append(", created ").append(control.createdAt()).append(")").append(nl);
            }
            rows.forEach((table, count) -> out.append(table).append(": ").append(count).append(" rows ").append(verb)
                    .append(nl));
            out.append("Attachment files ").append(verb).append(": ").append(files).append(nl);
            for (String problem : fileProblems) {
                out.append("  not deleted: ").append(problem).append(nl);
            }
            if (undated > 0) {
                out.append("Controls without a creation date, kept: ").append(undated).append(nl);
            }
            if (!applied) {
                out.append("Run again with --apply to delete it.").append(nl);
            }
            return out.toString();
        }
    }

    public int years() {
        return years;
    }

    /**
     * @param actor who runs it (the operating-system user, or "scheduler"), for the audit trail
     */
    public Report run(boolean apply, String actor) {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusYears(Math.max(years, 0));
        if (years < MIN_YEARS) {
            return new Report(apply, "retention.years is " + years + ", the business keeps data " + MIN_YEARS
                    + " years: fewer is refused", years, cutoff, List.of(), Map.of(), 0, List.of(), 0);
        }
        // Read, and for a run that deletes, delete, in one transaction; the files only after it commits
        List<FileToDelete> files = new ArrayList<>();
        Report report = transaction.execute(status -> collectAndDelete(apply, cutoff, files, actor));
        if (!apply || report == null) {
            return report;
        }
        List<String> problems = new ArrayList<>();
        for (FileToDelete file : files) {
            try {
                fileStorageService.deleteFile(file.name(), file.folders());
            } catch (IOException | RuntimeException e) {
                problems.add(file.name() + " (" + e.getMessage() + ")");
            }
        }
        if (!problems.isEmpty()) {
            log.warn("Data retention: {} attachment file(s) could not be deleted: {}", problems.size(), problems);
        }
        return new Report(true, null, years, cutoff, report.controls(), report.rows(), files.size(), problems,
                report.undated());
    }

    private record FileToDelete(String name, List<String> folders) {
    }

    private Report collectAndDelete(boolean apply, LocalDateTime cutoff, List<FileToDelete> files, String actor) {
        Timestamp before = Timestamp.valueOf(cutoff);
        List<Control> expired = controlRepository.findAll().stream()
                .filter(control -> control.getCreatedAt() != null && control.getCreatedAt().isBefore(cutoff))
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();
        int undated = (int) controlRepository.findAll().stream().filter(control -> control.getCreatedAt() == null).count();
        List<Long> ids = expired.stream().map(Control::getId).toList();

        // The files, while the audit entries that name a control's earlier IDs (its folders) are still there
        for (Control control : expired) {
            List<String> folders = controlRenameService.attachmentFolders(control);
            for (String name : jdbc.queryForList("SELECT file_name FROM control_attachments WHERE control_id = ?",
                    String.class, control.getId())) {
                files.add(new FileToDelete(name, folders));
            }
        }

        Map<String, Integer> rows = new LinkedHashMap<>();
        String in = ids.isEmpty() ? null : ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        // Rows of the expired controls, whatever their own age; then what is older than the cutoff
        count(rows, "controls", in == null ? 0 : ids.size());
        count(rows, "control_attachments", rowsOf("control_attachments", in));
        count(rows, "workflow_steps", rowsOf("workflow_steps", in));
        count(rows, "workflow_history", rowsOf("workflow_history", in));
        count(rows, "notifications", rowsOf("notifications", in) + olderThan("notifications", "created_at", before, in));
        count(rows, "email_outbox", rowsOf("email_outbox", in) + olderThan("email_outbox", "created_at", before, in));
        count(rows, "admin_audit_log", rowsOf("admin_audit_log", in)
                + olderThan("admin_audit_log", "created_at", before, in));
        count(rows, "deleted_users", jdbc.queryForObject("SELECT COUNT(*) FROM deleted_users WHERE deleted_at < ?",
                Integer.class, before));

        List<Expired> listed = expired.stream()
                .map(control -> new Expired(control.getId(), control.getControlId(), control.getCreatedAt())).toList();
        if (!apply) {
            return new Report(false, null, years, cutoff, listed, rows, files.size(), List.of(), undated);
        }

        if (in != null) {
            for (String table : List.of("control_attachments", "workflow_steps", "workflow_history", "notifications",
                    "email_outbox", "admin_audit_log")) {
                jdbc.update("DELETE FROM " + table + " WHERE control_id IN (" + in + ")");
            }
            // A next cycle keeps its row and loses only the link to its source (V14 says ON DELETE SET NULL; done
            // here too, so it holds whatever the schema); Other Related Controls links go with the control (CASCADE)
            jdbc.update("UPDATE controls SET created_from_control_id = NULL WHERE created_from_control_id IN (" + in + ")");
            jdbc.update("DELETE FROM controls WHERE id IN (" + in + ")");
        }
        jdbc.update("DELETE FROM notifications WHERE created_at < ?", before);
        jdbc.update("DELETE FROM email_outbox WHERE created_at < ?", before);
        jdbc.update("DELETE FROM admin_audit_log WHERE created_at < ?", before);
        jdbc.update("DELETE FROM deleted_users WHERE deleted_at < ?", before);

        Report report = new Report(true, null, years, cutoff, listed, rows, files.size(), List.of(), undated);
        auditService.logActionWithChanges(ACTOR_MAIL, "Data retention (" + actor + ")", AUDIT_ACTION, null,
                "Data older than " + years + " years deleted: " + listed.size() + " control(s), "
                        + rows.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                        .collect(Collectors.joining(", ")) + ", " + files.size() + " file(s)",
                "Data retention",
                "created before " + cutoff,
                listed.isEmpty() ? "no control" : listed.stream().map(Expired::controlId).collect(Collectors.joining(", ")));
        return report;
    }

    private static void count(Map<String, Integer> rows, String table, Integer count) {
        rows.put(table, count == null ? 0 : count);
    }

    private int rowsOf(String table, String in) {
        if (in == null) {
            return 0;
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE control_id IN (" + in + ")",
                Integer.class);
        return count == null ? 0 : count;
    }

    /** Rows older than the cutoff that are not rows of the expired controls (those are counted already). */
    private int olderThan(String table, String column, Timestamp before, String in) {
        String notTheirs = in == null ? "" : " AND (control_id IS NULL OR control_id NOT IN (" + in + "))";
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " < ?" + notTheirs,
                Integer.class, before);
        return count == null ? 0 : count;
    }
}
