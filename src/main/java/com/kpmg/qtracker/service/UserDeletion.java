package com.kpmg.qtracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.DeletedUser;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.DeletedUserRepository;
import com.kpmg.qtracker.repository.NotificationRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.util.EmailList;
import com.kpmg.qtracker.util.RoleDisplayMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deleting a user from the Admin Panel (business decision of 2026-10-13): SoQM Admin Team only
 * ({@link AccessPolicy#userDeletionRefusal}). The users row goes for good, with the Entra object id, the password
 * and the person's in-app notifications. Everything that records what the person did stays as it is, their name
 * and e-mail included (not anonymised: 7-year retention, specification 22): admin_audit_log, workflow_history,
 * workflow_steps, control_attachments (who uploaded), the e-mails of the letters, and the people of the controls
 * (their e-mail stays in the fields and Shared With, shown "e-mail (deleted user)"). A control they created keeps
 * their name and e-mail as text (controls.created_by_email/name, V12) and loses only the link to the row.
 *
 * <p>Two steps, both checked by the server: {@link #check} answers what the deletion would leave (the controls not
 * completed where the person is in a field, in Shared With or the creator, up to {@link #LISTED}) and gives a
 * one-time token for this target and this admin; {@link #delete} needs the typed e-mail, a reason and that token,
 * and, when such controls exist, the admin's confirmation that they stay with the deleted person.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDeletion {

    public static final String AUDIT_ACTION = "USER_DELETED";
    /** The controls a confirmation lists by ID; the total is given too. */
    public static final int LISTED = 50;
    public static final int MAX_REASON = 500;
    static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);

    public static final String NOT_FOUND = "User not found";
    public static final String EMAIL_MISMATCH = "Type the user's e-mail exactly to confirm";
    public static final String REASON_REQUIRED = "Give a reason for deleting the user";
    public static final String REASON_TOO_LONG = "The reason is longer than " + MAX_REASON + " characters";
    public static final String TOKEN_INVALID = "The confirmation is out of date: start the deletion again";
    public static final String ACKNOWLEDGEMENT_REQUIRED =
            "Confirm that the controls listed stay with the deleted person";

    private final UserRepository userRepository;
    private final ControlRepository controlRepository;
    private final ControlAssignmentRepository assignmentRepository;
    private final NotificationRepository notificationRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final DeletedUserRepository deletedUserRepository;
    private final ObjectMapper objectMapper;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Step> steps = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    /** A refused request: the HTTP status the API answers and the message. */
    public static final class Refused extends RuntimeException {
        private final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** One control not completed that keeps the person, with where they stand on it. */
    public record Affected(Long id, String controlId, String status, List<String> places) {
    }

    /** What {@link #check} answers: the person, the controls (first {@link #LISTED}), the total and the token. */
    public record Check(Long userId, String email, String name, String role, List<Affected> controls, int total,
                        boolean secondConfirmation, String token) {
    }

    /** What {@link #delete} did. */
    public record Done(Long userId, String email, int unfinishedControls, AdminAuditLog audit) {
    }

    private record Step(Long targetId, Long actorId, int total, Instant expires) {
    }

    /** The first step: refusals, then the controls the person stays on, and a token for the second. */
    @Transactional(readOnly = true)
    public Check check(Long targetId, User actor) {
        User target = refuseOrTarget(targetId, actor);
        List<Affected> affected = affected(target);
        String token = newToken();
        steps.put(token, new Step(target.getId(), actor.getId(), affected.size(), clock.instant().plus(TOKEN_LIFETIME)));
        return new Check(target.getId(), target.getMail(), target.getDisplayName(), roleOf(target),
                affected.subList(0, Math.min(LISTED, affected.size())), affected.size(), !affected.isEmpty(), token);
    }

    /**
     * The deletion. Everything is checked before anything changes: the refusals again, the typed e-mail, the reason,
     * the token of {@link #check} for this target and admin (used once), and the acknowledgement when controls keep
     * the person (also when some appeared after the check).
     */
    @Transactional
    public Done delete(Long targetId, User actor, String typedEmail, String reason, String token, boolean acknowledged) {
        User target = refuseOrTarget(targetId, actor);
        if (typedEmail == null || !typedEmail.trim().equalsIgnoreCase(String.valueOf(target.getMail()).trim())) {
            throw new Refused(400, EMAIL_MISMATCH);
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new Refused(400, REASON_REQUIRED);
        }
        if (why.length() > MAX_REASON) {
            throw new Refused(400, REASON_TOO_LONG);
        }
        Step step = token == null ? null : steps.get(token);
        if (step == null || !step.targetId().equals(target.getId()) || !step.actorId().equals(actor.getId())
                || clock.instant().isAfter(step.expires())) {
            throw new Refused(409, TOKEN_INVALID);
        }
        List<Affected> affected = affected(target);
        if (!affected.isEmpty() && !acknowledged) {
            throw new Refused(409, ACKNOWLEDGEMENT_REQUIRED);
        }
        steps.remove(token);

        // Controls they created keep them as text; the link to the row goes (the foreign key)
        for (Control control : controlRepository.findByCreatedById(target.getId())) {
            if (control.getCreatedByEmail() == null || control.getCreatedByEmail().isBlank()) {
                control.setCreatedByEmail(target.getMail());
                control.setCreatedByName(target.getDisplayName());
            }
            control.setCreatedBy(null);
            controlRepository.save(control);
        }
        // Their own in-app notifications
        List<Notification> notifications = notificationRepository.findByUserIdOrderByCreatedAtDesc(target.getId());
        notificationRepository.deleteAll(notifications);

        DeletedUser gone = new DeletedUser();
        gone.setUserId(target.getId());
        gone.setMail(target.getMail());
        gone.setDisplayName(target.getDisplayName());
        gone.setRole(roleOf(target));
        gone.setDeletedAt(LocalDateTime.now(clock.withZone(Notification.ZONE)));
        gone.setDeletedByEmail(actor.getMail());
        deletedUserRepository.save(gone);

        AdminAuditLog audit = auditLogRepository.save(auditEntry(target, actor, why, affected, !affected.isEmpty()));
        userRepository.delete(target);
        userRepository.flush();
        log.info("User deleted: user={} by={} unfinishedControls={}", target.getId(), actor.getId(), affected.size());
        return new Done(target.getId(), target.getMail(), affected.size(), audit);
    }

    private User refuseOrTarget(Long targetId, User actor) {
        if (actor == null) {
            throw new Refused(401, "User not authenticated");
        }
        AccessPolicy.Subject subject = AccessPolicy.Subject.of(actor);
        if (!AccessPolicy.isSoqm(subject)) {
            throw new Refused(403, AccessPolicy.DELETE_NOT_ALLOWED);
        }
        User target = targetId == null ? null : userRepository.findById(targetId).orElse(null);
        if (target == null) {
            throw new Refused(404, NOT_FOUND);
        }
        Optional<String> refusal = AccessPolicy.userDeletionRefusal(subject, actor.getId(),
                AccessPolicy.Subject.of(target), target.getId(), userRepository.countActiveByAccessLevel(AccessLevel.SOQM));
        if (refusal.isPresent()) {
            throw new Refused(409, refusal.get());
        }
        return target;
    }

    /**
     * The controls not completed where the person is a Facilitator, Control Operator, Process Owner, SoQM Team /
     * Delegate, in Shared With or the creator, by Control ID; one read of the controls and one of the assignments.
     */
    List<Affected> affected(User target) {
        String mail = String.valueOf(target.getMail()).trim().toLowerCase(Locale.ROOT);
        Map<Long, ControlAssignment> assignments = new HashMap<>();
        assignmentRepository.findAll().forEach(assignment -> assignments.put(assignment.getControlId(), assignment));
        List<Affected> affected = new ArrayList<>();
        for (Control control : controlRepository.findAll()) {
            if (DeadlineOverdue.isCompleted(control.getPerformanceStatus())) {
                continue;
            }
            List<String> places = new ArrayList<>();
            ControlAssignment assignment = assignments.get(control.getId());
            if (assignment != null) {
                add(places, "Facilitator", assignment.getFacilitator(), mail);
                add(places, "Control Operator", assignment.getControlOperator(), mail);
                add(places, "Process Owner", assignment.getProcessOwner(), mail);
                add(places, AccessPolicy.Slot.SOQM_LEAD.getLabel(), assignment.getSoqmLead(), mail);
                add(places, "Shared With", assignment.getControlSharedWith(), mail);
            }
            if (control.getCreatedBy() != null && target.getId().equals(control.getCreatedBy().getId())) {
                places.add("Creator");
            }
            if (!places.isEmpty()) {
                affected.add(new Affected(control.getId(), control.getControlId(), control.getPerformanceStatus(),
                        List.copyOf(places)));
            }
        }
        affected.sort(Comparator.comparing(a -> String.valueOf(a.controlId()).toLowerCase(Locale.ROOT)));
        return affected;
    }

    private static void add(List<String> places, String place, String stored, String mail) {
        if (EmailList.parse(stored).stream().anyMatch(person -> person.trim().equalsIgnoreCase(mail))) {
            places.add(place);
        }
    }

    private AdminAuditLog auditEntry(User target, User actor, String reason, List<Affected> affected,
                                     boolean secondConfirmation) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("id", target.getId());
        before.put("email", target.getMail());
        before.put("name", target.getDisplayName());
        before.put("role", roleOf(target));
        before.put("status", Boolean.TRUE.equals(target.getEnabled()) ? "Active" : "Inactive");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("Reason", reason);
        after.put("Unfinished controls", affected.size());
        after.put("Control IDs", affected.stream().map(Affected::controlId).toList());
        after.put("Second confirmation", secondConfirmation);
        AdminAuditLog entry = new AdminAuditLog();
        entry.setAdminEmail(actor.getMail());
        entry.setAdminName(actor.getDisplayName());
        entry.setActionType(AUDIT_ACTION);
        entry.setActionDescription("Deleted user for " + target.getMail());
        entry.setChangedFields("user");
        try {
            entry.setPreviousValues(objectMapper.writeValueAsString(before));
            entry.setNewValues(objectMapper.writeValueAsString(after));
        } catch (Exception e) {
            entry.setPreviousValues(before.toString());
            entry.setNewValues(after.toString());
        }
        return entry;
    }

    private static String roleOf(User user) {
        return RoleDisplayMapper.summary(AccessPolicy.Profile.of(user));
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        steps.entrySet().removeIf(entry -> clock.instant().isAfter(entry.getValue().expires()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Tests move the clock (token expiry). */
    void useClock(Clock clock) {
        this.clock = clock;
    }
}
