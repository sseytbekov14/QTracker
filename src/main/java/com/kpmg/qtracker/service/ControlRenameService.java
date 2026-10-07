package com.kpmg.qtracker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renaming a Control ID. The ID also decides whether the control is a KDN control
 * ({@link AccessPolicy#isKdnControl}), so a rename that makes it one or stops it being one changes who sees
 * it: every KDN user gains or loses access. Such a rename needs a comment and is not kept without its audit
 * entry. Every rename gets an audit entry, shown in the control's changelog.
 * <p>
 * The control's attachments lie in a folder named after its Control ID, so a rename moves that folder too.
 * Files a rename could not move (or renames from before the move existed) are still found through the
 * earlier IDs in the rename audit entries: {@link #attachmentFolders}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ControlRenameService {

    public static final String AUDIT_ACTION = "RENAME_ID";
    static final String FIELD_CONTROL_ID = "Control ID";
    static final String FIELD_KDN = "KDN control";
    static final String FIELD_COMMENT = "Comment";
    static final String FIELD_GAINING = "KDN users gaining access";
    static final String FIELD_LOSING = "KDN users losing access";

    private final ControlRepository controlRepository;
    private final ControlAssignmentService controlAssignmentService;
    private final ControlPermissionService controlPermissionService;
    private final UserRepository userRepository;
    private final AdminAuditService adminAuditService;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** The KDN users who see the control only before the rename (losing) or only after it (gaining). */
    public record KdnAccessChange(boolean becomesKdn, List<String> gaining, List<String> losing) {
    }

    /**
     * What a rename to the new ID would do to KDN access, for the confirmation in the Rename ID dialog:
     * kdnChange false when the KDN mark stays (no confirmation, no comment needed).
     */
    public record RenamePreview(boolean kdnChange, boolean becomesKdn, List<String> gaining, List<String> losing,
                                String title, String explanation) {
    }

    /** The preview of renaming the control to the new ID (trimmed); nothing is saved. */
    public RenamePreview preview(Control control, String newControlId) {
        String newId = newControlId == null ? "" : newControlId.trim();
        if (newId.isEmpty() || !AccessPolicy.renameChangesKdn(control.getControlId(), newId)) {
            return new RenamePreview(false, AccessPolicy.isKdnControl(newId), List.of(), List.of(), null, null);
        }
        KdnAccessChange change = kdnAccessChange(control, AccessPolicy.isKdnControl(newId));
        String title = change.becomesKdn()
                ? "This control will become a KDN control"
                : "This control will stop being a KDN control";
        String explanation = change.becomesKdn()
                ? "The new ID starts with KDN, so every KDN user will see this control (read only). "
                        + (change.gaining().isEmpty() ? "No active KDN user gains access now."
                        : count(change.gaining().size()) + " will gain access.")
                : "The new ID does not start with KDN, so KDN users will no longer see this control. "
                        + (change.losing().isEmpty() ? "No active KDN user loses access now."
                        : count(change.losing().size()) + " will lose access.");
        return new RenamePreview(true, change.becomesKdn(), change.gaining(), change.losing(), title,
                explanation + " A comment is required and the change is recorded in the audit log.");
    }

    private static String count(int users) {
        return users == 1 ? "1 KDN user" : users + " KDN users";
    }

    /**
     * Renames the Control ID (trimmed). IllegalArgumentException with the reason when it cannot be done: no
     * ID, an ID in use, or no comment where the KDN mark changes.
     */
    @Transactional
    public Control rename(Long id, String newControlId, String comment, User actor) {
        Control control = controlRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Control not found with id: " + id));
        String newId = newControlId == null ? "" : newControlId.trim();
        if (newId.isEmpty()) {
            throw new IllegalArgumentException("Control ID cannot be empty");
        }
        String oldId = control.getControlId();
        if (newId.equals(oldId)) {
            return control;
        }
        if (controlRepository.existsByControlId(newId)) {
            throw new IllegalArgumentException("Control ID '" + newId + "' already exists. Please choose a different ID.");
        }
        String note = comment == null ? "" : comment.trim();
        KdnAccessChange change = null;
        if (AccessPolicy.renameChangesKdn(oldId, newId)) {
            change = kdnAccessChange(control, AccessPolicy.isKdnControl(newId));
            if (note.isEmpty()) {
                throw new IllegalArgumentException("A comment is required: " + describe(change));
            }
        }

        control.setControlId(newId);
        Control saved = controlRepository.save(control);
        AdminAuditLog audit = recordAudit(saved, actor, oldId, newId, note, change);
        if (audit == null && change != null) {
            // Who gains or loses access must stay traceable: no audit entry, no rename
            throw new IllegalStateException("The rename could not be recorded in the audit log and was not saved");
        }
        log.info("Control {} renamed from {} to {} by {}{}", saved.getId(), oldId, newId,
                actor != null ? actor.getMail() : "unknown", change != null ? " (" + describe(change) + ")" : "");
        afterCommit(() -> moveAttachments(saved.getId(), oldId, newId));
        return saved;
    }

    /**
     * The folders that may hold the control's attachments, in the order to look: the folder of its Control ID,
     * then those of its earlier IDs (newest first) from the rename audit entries.
     */
    public List<String> attachmentFolders(Control control) {
        LinkedHashSet<String> folders = new LinkedHashSet<>();
        String current = FileStorageService.controlFolder(control.getControlId(), control.getId());
        if (current != null) {
            folders.add(current);
        }
        if (control.getId() != null) {
            for (AdminAuditLog entry : adminAuditLogRepository
                    .findByControlIdAndActionTypeOrderByCreatedAtDesc(control.getId(), AUDIT_ACTION)) {
                String earlier = earlierControlId(entry);
                if (earlier != null) {
                    folders.add(earlier);
                }
            }
        }
        return new ArrayList<>(folders);
    }

    private String earlierControlId(AdminAuditLog entry) {
        if (entry.getPreviousValues() == null || entry.getPreviousValues().isBlank()) {
            return null;
        }
        try {
            JsonNode value = objectMapper.readTree(entry.getPreviousValues()).get(FIELD_CONTROL_ID);
            return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
        } catch (JsonProcessingException | RuntimeException e) {
            return null;
        }
    }

    /** The files go with the ID; a failure leaves them in the old folder, where downloads still find them. */
    private void moveAttachments(Long id, String oldId, String newId) {
        String from = FileStorageService.controlFolder(oldId, id);
        String to = FileStorageService.controlFolder(newId, id);
        try {
            int moved = fileStorageService.moveControlFolder(from, to);
            if (moved > 0) {
                log.info("Control {}: {} attachment(s) moved with the rename from {} to {}", id, moved, oldId, newId);
            }
        } catch (Exception e) {
            log.warn("Control {}: attachments stay in the folder of its earlier ID {} ({})", id, oldId, e.toString());
        }
    }

    /** Runs the action once the transaction has committed, so a rename rolled back moves no files. */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * The users whose view of the control the new KDN mark changes, by {@link AccessPolicy#canView}: every
     * active KDN user, since KDN users see every KDN control, whether or not they are on it.
     */
    public KdnAccessChange kdnAccessChange(Control control, boolean becomesKdn) {
        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
        if (assignment == null) {
            assignment = new ControlAssignmentDTO();
        }
        List<String> gaining = new ArrayList<>();
        List<String> losing = new ArrayList<>();
        List<User> users = new ArrayList<>(userRepository.findAll());
        users.sort(Comparator.comparing(user -> user.getMail() == null ? "" : user.getMail().toLowerCase(Locale.ROOT)));
        for (User user : users) {
            AccessPolicy.ControlFacts facts = controlPermissionService.facts(control, user, assignment);
            if (facts == null) {
                continue;
            }
            AccessPolicy.Subject subject = AccessPolicy.Subject.of(user);
            boolean before = AccessPolicy.canView(subject, facts);
            boolean after = AccessPolicy.canView(subject, facts.withKdn(becomesKdn));
            if (!before && after) {
                gaining.add(user.getMail());
            } else if (before && !after) {
                losing.add(user.getMail());
            }
        }
        return new KdnAccessChange(becomesKdn, gaining, losing);
    }

    static String describe(KdnAccessChange change) {
        StringBuilder text = new StringBuilder(change.becomesKdn()
                ? "the new Control ID makes this a KDN control"
                : "the new Control ID makes this control no longer a KDN control");
        if (!change.gaining().isEmpty()) {
            text.append("; KDN users who will see it: ").append(String.join(", ", change.gaining()));
        }
        if (!change.losing().isEmpty()) {
            text.append("; KDN users who will no longer see it: ").append(String.join(", ", change.losing()));
        }
        if (change.gaining().isEmpty() && change.losing().isEmpty()) {
            text.append("; no KDN user's access changes");
        }
        return text.toString();
    }

    private AdminAuditLog recordAudit(Control control, User actor, String oldId, String newId, String comment,
                                      KdnAccessChange change) {
        List<String> fields = new ArrayList<>(List.of(FIELD_CONTROL_ID));
        Map<String, Object> previous = new LinkedHashMap<>();
        Map<String, Object> next = new LinkedHashMap<>();
        previous.put(FIELD_CONTROL_ID, oldId);
        next.put(FIELD_CONTROL_ID, newId);
        if (change != null) {
            fields.add(FIELD_KDN);
            previous.put(FIELD_KDN, change.becomesKdn() ? "No" : "Yes");
            next.put(FIELD_KDN, change.becomesKdn() ? "Yes" : "No");
            // Who gains or loses access is in the entry, and shown in the changelog when there is anyone
            next.put(FIELD_GAINING, change.gaining());
            next.put(FIELD_LOSING, change.losing());
            if (!change.gaining().isEmpty()) {
                fields.add(FIELD_GAINING);
            }
            if (!change.losing().isEmpty()) {
                fields.add(FIELD_LOSING);
            }
        }
        if (!comment.isEmpty()) {
            fields.add(FIELD_COMMENT);
            next.put(FIELD_COMMENT, comment);
        }
        String description = "Control ID renamed: " + oldId + " -> " + newId
                + (change != null ? " (" + describe(change) + ")" : "");
        try {
            return adminAuditService.logActionWithChanges(
                    actor != null ? actor.getMail() : null, actor != null ? actor.getDisplayName() : null,
                    AUDIT_ACTION, control, description, objectMapper.writeValueAsString(fields),
                    objectMapper.writeValueAsString(previous), objectMapper.writeValueAsString(next));
        } catch (JsonProcessingException e) {
            log.error("Could not write the audit entry of a Control ID rename on control {}", control.getId(), e);
            return null;
        }
    }
}
