package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessRight;
import com.kpmg.qtracker.enums.UserRole;
import com.kpmg.qtracker.enums.Visibility;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.util.EmailList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.kpmg.qtracker.repository.DeletedUserRepository deletedUserRepository;
    private final ControlAssignmentRepository assignmentRepository;
    /** The temporary password of new accounts (the Admin Panel and the user import of dev and test). */
    public static final String DEFAULT_NEW_USER_PASSWORD = "aaa";
    /** The display_name column length. */
    static final int MAX_NAME_LENGTH = 255;

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    /** Users an assignment field accepts, for its picker ({@link AccessPolicy#isOfferedFor}), by name. */
    public List<User> getUsersOfferedFor(AccessPolicy.Slot slot, boolean kdnControl) {
        return userRepository.findAll().stream()
                .filter(user -> AccessPolicy.isOfferedFor(AccessPolicy.Subject.of(user), slot, kdnControl))
                .sorted(Comparator.comparing(User::getDisplayName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    public Optional<User> getUserByEmail(String email) {
        return userRepository.findByMail(email); // Используем findByMail
    }

    /**
     * Display names by lower-case e-mail, in one query (no look-up per person); addresses without a user, or
     * a user without a name, are left out for the caller's fallback.
     */
    public Map<String, String> displayNamesByEmail(Collection<String> emails) {
        List<String> wanted = emails == null ? List.of() : emails.stream()
                .filter(email -> email != null && !email.isBlank())
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        Map<String, String> names = new HashMap<>();
        if (wanted.isEmpty()) {
            return names;
        }
        java.util.Set<String> users = new java.util.HashSet<>();
        for (User user : userRepository.findByMailLowerIn(wanted)) {
            if (user.getMail() != null) {
                users.add(user.getMail().trim().toLowerCase(Locale.ROOT));
            }
            if (user.getMail() != null && user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
                names.put(user.getMail().trim().toLowerCase(Locale.ROOT), user.getDisplayName().trim());
            }
        }
        // A person deleted from QTracker who still stands in a control: "e-mail (deleted user)"
        for (String mail : deletedAmong(wanted, users)) {
            names.put(mail, mail + com.kpmg.qtracker.entity.Control.DELETED_USER_MARK);
        }
        return names;
    }

    /**
     * Of these addresses, the ones of a user deleted from QTracker and not given to a user since (lower-case), in
     * at most two queries.
     */
    public java.util.Set<String> deletedEmails(Collection<String> emails) {
        List<String> wanted = emails == null ? List.of() : emails.stream()
                .filter(email -> email != null && !email.isBlank())
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        if (wanted.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Set<String> users = new java.util.HashSet<>();
        for (User user : userRepository.findByMailLowerIn(wanted)) {
            if (user.getMail() != null) {
                users.add(user.getMail().trim().toLowerCase(Locale.ROOT));
            }
        }
        return deletedAmong(wanted, users);
    }

    private java.util.Set<String> deletedAmong(List<String> wanted, java.util.Set<String> users) {
        List<String> missing = wanted.stream().filter(mail -> !users.contains(mail)).toList();
        java.util.Set<String> deleted = new java.util.TreeSet<>();
        if (missing.isEmpty() || deletedUserRepository == null) {
            return deleted;
        }
        deletedUserRepository.findByMailLowerIn(missing).forEach(gone ->
                deleted.add(gone.getMail().trim().toLowerCase(Locale.ROOT)));
        return deleted;
    }

    /** The users of these addresses by lower-case e-mail, in one query (no look-up per person). */
    public Map<String, User> usersByEmail(Collection<String> emails) {
        List<String> wanted = emails == null ? List.of() : emails.stream()
                .filter(email -> email != null && !email.isBlank())
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        Map<String, User> users = new HashMap<>();
        if (wanted.isEmpty()) {
            return users;
        }
        for (User user : userRepository.findByMailLowerIn(wanted)) {
            if (user.getMail() != null) {
                users.putIfAbsent(user.getMail().trim().toLowerCase(Locale.ROOT), user);
            }
        }
        return users;
    }

    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);
    }

    public User saveUser(User user) {
        return userRepository.save(user);
    }

    /**
     * The Admin Panel save of one user. Every value is checked before anything changes, so a refused save
     * changes nothing. A null e-mail or name keeps the stored one; the e-mail changes also after the first login
     * (2026-10-21), not one's own, and the person's places in the controls follow it ({@link #moveAssignments}).
     * A blank role keeps the stored one; for the role User a blank Visibility or Access keeps the
     * stored one, or, for someone becoming User, starts at My controls and Read Only. Every combination of
     * Visibility and Access is allowed. Refused: changing one's own role or deactivating oneself, and taking
     * the role or the account of the last active SoQM Team member (nobody could open the Admin Panel then).
     * The stored level, scope and admin_access follow the role ({@link AccessPolicy.Profile}); the old role
     * columns are not touched.
     */
    @Transactional
    public User updateUser(Long targetUserId,
                           String email,
                           String displayName,
                           String role,
                           String visibility,
                           String access,
                           Boolean enabled,
                           Long actingUserId) {
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String nextMail = targetUser.getMail();
        if (email != null) {
            String normalizedEmail = normalizeEmail(email);
            String currentEmail = targetUser.getMail() == null ? "" : targetUser.getMail().trim().toLowerCase(Locale.ROOT);
            if (!normalizedEmail.equals(currentEmail)) {
                // The session of the one who saves is known by the address: their own would end at once
                if (actingUserId != null && actingUserId.equals(targetUser.getId())) {
                    throw new IllegalArgumentException(OWN_EMAIL);
                }
                if (userRepository.existsByMail(normalizedEmail)) {
                    throw new IllegalArgumentException("User with this email already exists");
                }
                nextMail = normalizedEmail;
            }
        }

        String nextName = targetUser.getDisplayName();
        if (displayName != null) {
            String trimmed = displayName.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("Name is required");
            }
            if (trimmed.length() > MAX_NAME_LENGTH) {
                throw new IllegalArgumentException("Name is too long (" + MAX_NAME_LENGTH + " characters at most)");
            }
            nextName = trimmed;
        }

        AccessPolicy.Profile current = AccessPolicy.Profile.of(targetUser);
        AccessPolicy.Profile next = profile(role, visibility, access, current);
        boolean enabledNow = Boolean.TRUE.equals(targetUser.getEnabled());
        boolean nextEnabled = enabled != null ? enabled : enabledNow;

        boolean selfUpdate = actingUserId != null && actingUserId.equals(targetUser.getId());
        if (selfUpdate && next.role() != current.role()) {
            throw new IllegalArgumentException("You cannot change your own role");
        }
        if (selfUpdate && !nextEnabled) {
            throw new IllegalArgumentException("You cannot deactivate your own account");
        }
        boolean lastActiveSoqm = current.role() == UserRole.SOQM_TEAM && enabledNow
                && userRepository.countActiveByAccessLevel(AccessLevel.SOQM) <= 1;
        if (lastActiveSoqm && next.role() != UserRole.SOQM_TEAM) {
            throw new IllegalArgumentException(LAST_SOQM_ROLE);
        }
        if (lastActiveSoqm && !nextEnabled) {
            throw new IllegalArgumentException(LAST_SOQM_DEACTIVATE);
        }

        String previousMail = targetUser.getMail();
        targetUser.setMail(nextMail);
        targetUser.setDisplayName(nextName);
        apply(targetUser, next);
        targetUser.setEnabled(nextEnabled);
        User saved = userRepository.save(targetUser);
        if (!Objects.equals(previousMail, nextMail)) {
            moveAssignments(previousMail, nextMail);
        }
        return saved;
    }

    /**
     * A changed e-mail takes the person's places in the controls with it (2026-10-21): the four people fields and
     * Control Shared With hold addresses, so the old one would leave the controls with nobody the person is. What
     * records the past - workflow history and steps, the audit trail, the letters, who uploaded a file, the
     * creator's e-mail kept as text - stays as it was. Returns how many controls changed.
     */
    int moveAssignments(String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return 0;
        }
        Set<Long> candidates = new TreeSet<>();
        candidates.addAll(assignmentRepository.findControlIdsByFacilitator(from));
        candidates.addAll(assignmentRepository.findControlIdsByControlOperator(from));
        candidates.addAll(assignmentRepository.findControlIdsBySoqmLead(from));
        candidates.addAll(assignmentRepository.findControlIdsByProcessOwner(from));
        candidates.addAll(assignmentRepository.findControlIdsByControlSharedWith(from));
        int changed = 0;
        for (Long controlId : candidates) {
            ControlAssignment assignment = assignmentRepository.findByControlId(controlId).orElse(null);
            if (assignment == null) {
                continue;
            }
            boolean moved = false;
            String facilitator = replaced(assignment.getFacilitator(), from, to);
            String operator = replaced(assignment.getControlOperator(), from, to);
            String soqm = replaced(assignment.getSoqmLead(), from, to);
            String owner = replaced(assignment.getProcessOwner(), from, to);
            String shared = replaced(assignment.getControlSharedWith(), from, to);
            if (facilitator != null) { assignment.setFacilitator(facilitator); moved = true; }
            if (operator != null) { assignment.setControlOperator(operator); moved = true; }
            if (soqm != null) { assignment.setSoqmLead(soqm); moved = true; }
            if (owner != null) { assignment.setProcessOwner(owner); moved = true; }
            if (shared != null) { assignment.setControlSharedWith(shared); moved = true; }
            if (moved) {
                assignmentRepository.save(assignment);
                changed++;
            }
        }
        return changed;
    }

    /** The stored list with {@code from} (ignoring case) given as {@code to}, or null when it is not in the list. */
    private static String replaced(String stored, String from, String to) {
        if (!EmailList.contains(stored, from)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String mail : EmailList.parse(stored)) {
            String next = mail.equalsIgnoreCase(from.trim()) ? to : mail;
            if (seen.add(next.toLowerCase(Locale.ROOT))) {
                out.add(next);
            }
        }
        return String.join(",", out);
    }

    static final String OWN_EMAIL = "You cannot change your own e-mail: ask another admin";
    static final String LAST_SOQM_ROLE =
            "This is the last active SoQM Admin Team member: their role cannot change until someone else is SoQM Admin Team";
    static final String LAST_SOQM_DEACTIVATE =
            "This is the last active SoQM Admin Team member: they cannot be deactivated until someone else is SoQM Admin Team";

    /**
     * A new user with a role; no old role. A User without Visibility or Access starts at My controls and
     * Read Only.
     */
    public User createUser(String email,
                           String displayName,
                           String role,
                           String visibility,
                           String access,
                           Boolean enabled) {
        return createUser(email, displayName, role, visibility, access, enabled, true);
    }

    /**
     * The same, with or without the temporary password: with SSO nobody signs in with a password, so the account
     * gets none (Admin Panel, AuthMode).
     */
    public User createUser(String email,
                           String displayName,
                           String role,
                           String visibility,
                           String access,
                           Boolean enabled,
                           boolean withPassword) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("Role is required");
        }
        AccessPolicy.Profile profile = profile(role, visibility, access, null);
        return userRepository.save(newUser(email, displayName, profile, enabled, withPassword));
    }

    /**
     * The profile the Admin Panel asks for. A blank role keeps {@code current}; Visibility and Access belong
     * to the role User only (refused with another role); for a User a blank one keeps the current value,
     * or, for a new User or someone becoming User, starts at My controls and Read Only.
     */
    static AccessPolicy.Profile profile(String role, String visibility, String access, AccessPolicy.Profile current) {
        UserRole nextRole;
        if (role == null || role.isBlank()) {
            if (current == null) {
                throw new IllegalArgumentException("Role is required");
            }
            nextRole = current.role();
        } else {
            nextRole = UserRole.tryFrom(role)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown role: " + role));
        }
        boolean blankVisibility = visibility == null || visibility.isBlank();
        boolean blankAccess = access == null || access.isBlank();
        if (nextRole != UserRole.USER) {
            if (!blankVisibility || !blankAccess) {
                throw new IllegalArgumentException("Visibility and Access apply only to the role User");
            }
            return new AccessPolicy.Profile(nextRole, null, null);
        }
        boolean stayingUser = current != null && current.role() == UserRole.USER;
        Visibility nextVisibility = blankVisibility
                ? (stayingUser ? current.visibility() : null)
                : Visibility.tryFrom(visibility)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown visibility: " + visibility));
        AccessRight nextAccess = blankAccess
                ? (stayingUser ? current.access() : null)
                : AccessRight.tryFrom(access)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown access: " + access));
        return AccessPolicy.Profile.user(nextVisibility, nextAccess);
    }

    /** Stores a profile as level, scope and the admin_access flag that follows it. */
    private static void apply(User user, AccessPolicy.Profile profile) {
        Optional<String> refusal = AccessPolicy.levelScopeRefusal(profile.level(), profile.scope());
        if (refusal.isPresent()) {
            throw new IllegalStateException(refusal.get());
        }
        user.setAccessLevel(profile.level());
        user.setAccessScope(profile.scope());
        user.setAdminAccess(profile.adminAccess());
    }

    private User newUser(String email, String displayName, AccessPolicy.Profile profile, Boolean enabled,
                         boolean withPassword) {
        String normalizedEmail = normalizeEmail(email);
        if (userRepository.existsByMail(normalizedEmail)) {
            throw new IllegalArgumentException("User with this email already exists");
        }

        User user = new User();
        user.setMail(normalizedEmail);
        user.setDisplayName(resolveDisplayName(displayName, normalizedEmail));
        apply(user, profile);
        user.setEnabled(enabled == null || enabled);
        user.setPassword(withPassword ? passwordEncoder.encode(DEFAULT_NEW_USER_PASSWORD) : null);
        return user;
    }

    private String resolveDisplayName(String displayName, String fallbackEmail) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        return buildDisplayNameFromEmail(fallbackEmail);
    }

    /** The e-mail in lower case, or "Email is required" / "Email format is invalid". */
    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (!normalizedEmail.contains("@") || normalizedEmail.startsWith("@") || normalizedEmail.endsWith("@")) {
            throw new IllegalArgumentException("Email format is invalid");
        }
        return normalizedEmail;
    }

    private String buildDisplayNameFromEmail(String email) {
        String localPart = email;
        int atIndex = email.indexOf('@');
        if (atIndex > 0) {
            localPart = email.substring(0, atIndex);
        }
        String cleaned = localPart.replace('.', ' ').replace('_', ' ').replace('-', ' ').trim();
        if (cleaned.isBlank()) {
            return email;
        }
        String[] parts = cleaned.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.length() > 0 ? builder.toString() : email;
    }
}
