package com.kpmg.qtracker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deactivate control (business decision of 2026-11-06): SoQM Admin Team removes a control for good, in any status.
 * <p>In one database transaction, with the control's row locked: the rule ({@link AccessPolicy#deactivationRefusal} -
 * SoQM Admin Team, and the typed Control ID matches), then one audit entry {@link #AUDIT_ACTION} with a snapshot of the
 * control as it was (who, when, its IDs, description, statuses, dates, the people of every Control role and Shared With,
 * its files) written before anything is deleted, then the control and all its rows ({@link ControlDependents}: steps,
 * history, comments are fields of its row, attachments, notifications, e-mails and reminders, its audit entries). The
 * audit entry is not tied to the control's id (control_id is empty), so it stays when the control's own entries go.
 * A failure anywhere rolls all of it back.
 * <p>The attachment files (Details and Documentation) are deleted from disk right after the commit - a file system is
 * not part of the transaction; a file that cannot be deleted is reported, the control is gone all the same - and the
 * control's folder with them when nothing else is left in it.
 */
@Service
@Slf4j
public class ControlDeactivation {

    public static final String AUDIT_ACTION = "CONTROL_DEACTIVATED";

    public enum Outcome { DEACTIVATED, NOT_FOUND, FORBIDDEN, ID_MISMATCH }

    /**
     * @param rows         rows deleted per table ("controls" first)
     * @param files        attachment files deleted from disk
     * @param fileProblems files that could not be deleted
     */
    public record Result(Outcome outcome, String message, String controlId, Map<String, Integer> rows, int files,
                         List<String> fileProblems) {

        static Result refused(Outcome outcome, String message, String controlId) {
            return new Result(outcome, message, controlId, Map.of(), 0, List.of());
        }

        public boolean deactivated() {
            return outcome == Outcome.DEACTIVATED;
        }
    }

    private final ControlRepository controlRepository;
    private final ControlRowLock rowLock;
    private final ControlAssignmentService assignmentService;
    private final ControlRenameService renameService;
    private final FileStorageService fileStorageService;
    private final AdminAuditLogRepository auditLogRepository;
    private final UserService userService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final StatusDisplayMapper statusDisplayMapper;
    @PersistenceContext
    private EntityManager entityManager;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public ControlDeactivation(ControlRepository controlRepository, ControlRowLock rowLock,
                               ControlAssignmentService assignmentService, ControlRenameService renameService,
                               FileStorageService fileStorageService, AdminAuditLogRepository auditLogRepository,
                               UserService userService, JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                               Clock clock, StatusDisplayMapper statusDisplayMapper) {
        this.controlRepository = controlRepository;
        this.rowLock = rowLock;
        this.assignmentService = assignmentService;
        this.renameService = renameService;
        this.fileStorageService = fileStorageService;
        this.auditLogRepository = auditLogRepository;
        this.userService = userService;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.statusDisplayMapper = statusDisplayMapper;
    }

    private record FileToDelete(String name, List<String> folders) {
    }

    /**
     * @param typedControlId what the user typed in the dialog to confirm
     * @param actor          the signed-in user
     */
    public Result deactivate(Long id, String typedControlId, User actor) {
        List<FileToDelete> files = new ArrayList<>();
        List<String> folders = new ArrayList<>();
        Result result = transaction.execute(status -> deleteRows(id, typedControlId, actor, files, folders));
        if (result == null || !result.deactivated()) {
            return result;
        }
        List<String> problems = new ArrayList<>();
        int deleted = 0;
        for (FileToDelete file : files) {
            try {
                fileStorageService.deleteFile(file.name(), file.folders());
                deleted++;
            } catch (IOException | RuntimeException e) {
                problems.add(file.name());
            }
        }
        for (String folder : folders) {
            try {
                fileStorageService.deleteFolderIfEmpty(folder);
            } catch (IOException | RuntimeException e) {
                log.warn("Deactivate control: the folder of control id {} could not be removed", id);
            }
        }
        if (!problems.isEmpty()) {
            log.warn("Deactivate control: {} attachment file(s) of control id {} could not be deleted", problems.size(), id);
        }
        return new Result(Outcome.DEACTIVATED, result.message(), result.controlId(), result.rows(), deleted, problems);
    }

    private Result deleteRows(Long id, String typedControlId, User actor, List<FileToDelete> files,
                              List<String> folders) {
        Control found = id == null ? null : controlRepository.findById(id).orElse(null);
        if (found == null) {
            return Result.refused(Outcome.NOT_FOUND, "Control not found", null);
        }
        Control control = rowLock.lockedFresh(found);
        String controlId = control.getControlId();
        var refusal = AccessPolicy.deactivationRefusal(AccessPolicy.Subject.of(actor), controlId, typedControlId);
        if (refusal.isPresent()) {
            Outcome outcome = AccessPolicy.canDeactivateControl(AccessPolicy.Subject.of(actor))
                    ? Outcome.ID_MISMATCH : Outcome.FORBIDDEN;
            return Result.refused(outcome, refusal.get(), controlId);
        }

        // The files and folders, while the audit entries naming the control's earlier IDs (its folders) are there
        List<String> controlFolders = renameService.attachmentFolders(control);
        folders.addAll(controlFolders);
        for (String name : fileNames(control)) {
            files.add(new FileToDelete(name, controlFolders));
        }

        Map<String, Integer> rows = ControlDependents.count(jdbc, List.of(control.getId()));
        AdminAuditLog entry = new AdminAuditLog();
        entry.setAdminEmail(actor.getMail());
        entry.setAdminName(actor.getDisplayName());
        entry.setActionType(AUDIT_ACTION);
        entry.setControlId(null);
        entry.setControlControlId(controlId);
        String status = control.getPerformanceStatus() == null || control.getPerformanceStatus().isBlank()
                ? "DRAFT" : control.getPerformanceStatus();
        entry.setActionDescription("Deactivated control " + (controlId == null ? "#" + control.getId() : controlId)
                + " (" + statusDisplayMapper.display(status) + ")");
        entry.setChangedFields("[\"control\"]");
        entry.setNewValues(snapshot(control, actor, files));
        entry.setCreatedAt(LocalDateTime.now(clock));
        auditLogRepository.saveAndFlush(entry);
        // Nothing loaded through JPA may be written after the rows are gone: written now, then forgotten
        entityManager.flush();
        entityManager.clear();

        ControlDependents.delete(jdbc, List.of(control.getId()));
        return new Result(Outcome.DEACTIVATED, "Control " + controlId + " has been deactivated", controlId, rows,
                files.size(), List.of());
    }

    /** The control's file names: its attachment rows and the two lists on its row, each name once. */
    private Set<String> fileNames(Control control) {
        Set<String> names = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT file_name FROM control_attachments WHERE control_id = ?", String.class, control.getId()));
        for (String list : new String[]{control.getAttachmentDetailsPath(), control.getAttachmentDocumentsPath()}) {
            if (list == null) {
                continue;
            }
            for (String name : list.split(";")) {
                if (!name.isBlank()) {
                    names.add(name.trim());
                }
            }
        }
        return names;
    }

    /** The control as it was, as JSON for the audit entry (admin_audit_log.new_values). */
    private String snapshot(Control control, User actor, List<FileToDelete> files) {
        ControlAssignmentDTO assignment = assignmentService.getAssignmentByControlId(control.getId());
        if (assignment == null) {
            assignment = new ControlAssignmentDTO();
        }
        List<String> everyone = new ArrayList<>();
        for (List<String> people : List.of(nonNull(assignment.getFacilitator()), nonNull(assignment.getControlOperator()),
                nonNull(assignment.getSoqmLead()), nonNull(assignment.getProcessOwner()),
                nonNull(assignment.getControlSharedWith()))) {
            everyone.addAll(people);
        }
        Map<String, String> names = userService.displayNamesByEmail(everyone);

        Map<String, Object> roles = new LinkedHashMap<>();
        roles.put(AccessPolicy.Slot.FACILITATOR.getLabel(), people(assignment.getFacilitator(), names));
        roles.put(AccessPolicy.Slot.CONTROL_OPERATOR.getLabel(), people(assignment.getControlOperator(), names));
        roles.put(AccessPolicy.Slot.SOQM_LEAD.getLabel(), people(assignment.getSoqmLead(), names));
        roles.put(AccessPolicy.Slot.PROCESS_OWNER.getLabel(), people(assignment.getProcessOwner(), names));
        roles.put(AccessPolicy.Slot.SHARED_WITH.getLabel(), people(assignment.getControlSharedWith(), names));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", control.getId());
        snapshot.put("controlId", control.getControlId());
        snapshot.put("description", control.getControlDescription());
        snapshot.put("component", control.getComponent());
        snapshot.put("frequency", control.getControlFrequency());
        snapshot.put("performanceStatus", control.getPerformanceStatus());
        snapshot.put("controlStatus", control.getControlStatus());
        snapshot.put("soqmYear", control.getSoqmYear());
        snapshot.put("operationDate", assignment.getControlOperationDate());
        snapshot.put("deadline", assignment.getControlOperationDeadline());
        snapshot.put("createdAt", control.getCreatedAt());
        snapshot.put("createdBy", control.getCreatedBy() != null ? control.getCreatedBy().getMail() : control.getCreatedByEmail());
        snapshot.put("roles", roles);
        snapshot.put("files", files.stream().map(FileToDelete::name).toList());
        snapshot.put("deactivatedBy", actor.getMail());
        snapshot.put("deactivatedByName", actor.getDisplayName());
        snapshot.put("deactivatedAt", LocalDateTime.now(clock));
        try {
            return json.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The snapshot of the control could not be written", e);
        }
    }

    private static List<String> nonNull(List<String> list) {
        return list == null ? List.of() : list;
    }

    private static List<Map<String, String>> people(List<String> mails, Map<String, String> names) {
        List<Map<String, String>> people = new ArrayList<>();
        for (String mail : nonNull(mails)) {
            if (mail == null || mail.isBlank()) {
                continue;
            }
            Map<String, String> person = new LinkedHashMap<>();
            person.put("email", mail.trim());
            String name = names.get(mail.trim().toLowerCase(Locale.ROOT));
            if (name != null) {
                person.put("name", name);
            }
            people.add(person);
        }
        return people;
    }

}
