package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.UserDTO;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.security.AuthMode;
import com.kpmg.qtracker.security.LoginNameResolver;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.AdminAuditTrail;
import com.kpmg.qtracker.service.UserService;
import com.kpmg.qtracker.util.RoleDisplayMapper;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {
    /** With SSO an address of the user import's service domain cannot sign in (SsoAdmission). */
    static final String CORPORATE_EMAIL_REQUIRED =
            "Use the person's corporate e-mail: with SSO they sign in with it at Microsoft";

    private final UserService userService;
    private final AdminAuditService adminAuditService;
    private final AuthMode authMode;
    private final LoginNameResolver loginNames;
    private final com.kpmg.qtracker.service.UserDeletion userDeletion;

    /** Dates as the Admin Panel shows them. */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    // Every user: SoQM and admins only, like the assignment pickers
    @GetMapping("/users")
    public ResponseEntity<List<UserDTO>> getAllUsers(HttpSession session) {
        if (!AccessPolicy.canListAllUsers(AccessPolicy.Subject.of((User) session.getAttribute("currentUser")))) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(userService.getAllUsers().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList()));
    }

    /**
     * The Admin Panel save of one user, in one request: the role (with Visibility and Access for a User),
     * the status and, when sent, the name and the e-mail (the e-mail only before the first login). Nothing
     * changes when a value is refused ({@link UserService#updateUser}). A save that changes something writes
     * one USER_ACCESS_UPDATE entry with the changed fields before and after; the answer carries the user and
     * that entry for the page.
     */
    @PostMapping("/users/{id}/access")
    public ResponseEntity<?> updateUserAccess(@PathVariable Long id,
                                              @RequestParam(required = false) String role,
                                              @RequestParam(required = false) String visibility,
                                              @RequestParam(required = false) String access,
                                              @RequestParam(defaultValue = "false") boolean enabled,
                                              @RequestParam(required = false) String email,
                                              @RequestParam(required = false) String displayName,
                                              HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!AccessPolicy.canManageUsers(AccessPolicy.Subject.of(currentUser))) {
            return ResponseEntity.status(403).body("Forbidden");
        }

        // A service address kept as it is (an imported person not moved yet) does not stop other changes
        if (email != null && serviceAddressWithSso(email) && userService.getUserById(id)
                .map(user -> !email.trim().equalsIgnoreCase(String.valueOf(user.getMail()).trim())).orElse(true)) {
            return ResponseEntity.badRequest().body(CORPORATE_EMAIL_REQUIRED);
        }
        try {
            // The service changes the same managed entity, so the values before the change are copied first
            User before = snapshot(userService.getUserById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found")));

            User updated = userService.updateUser(id, email, displayName, role, visibility, access, enabled,
                    currentUser.getId());

            AdminAuditLog audit = null;
            Changes changes = changes(before, updated);
            if (!changes.fields().isEmpty()) {
                audit = adminAuditService.logActionWithChanges(
                        currentUser.getMail(),
                        currentUser.getDisplayName(),
                        "USER_ACCESS_UPDATE",
                        null,
                        String.join("; ", changes.descriptions()) + " for " + updated.getMail(),
                        String.join(",", changes.fields()),
                        String.join(", ", changes.before()),
                        String.join(", ", changes.after())
                );
            }

            Map<String, Object> payload = userPayload(updated);
            payload.put("changed", !changes.fields().isEmpty());
            payload.put("audit", auditPayload(audit));
            return ResponseEntity.ok(payload);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
    }

    /**
     * Deleting a user, step 1 (SoQM Admin Team only, UserDeletion): the refusals (oneself, the last active SoQM Admin
     * Team member), the controls not completed that keep the person (first 50 with the person's place on each, the
     * total) and the token of step 2.
     */
    @PostMapping("/users/{id}/delete-check")
    public ResponseEntity<?> checkUserDeletion(@PathVariable Long id, HttpSession session) {
        try {
            com.kpmg.qtracker.service.UserDeletion.Check check =
                    userDeletion.check(id, (User) session.getAttribute("currentUser"));
            return ResponseEntity.ok(check);
        } catch (com.kpmg.qtracker.service.UserDeletion.Refused refused) {
            return ResponseEntity.status(refused.status()).body(refused.getMessage());
        }
    }

    /** What step 2 of a deletion sends: the typed e-mail, the reason, the token of step 1, the acknowledgement. */
    public record DeletionRequest(String email, String reason, String token, boolean acknowledged) {
    }

    /**
     * Deleting a user, step 2: the users row goes; history, logs and the controls' people stay (UserDeletion). The
     * answer carries the Audit Trail line. 404 for a user already deleted.
     */
    @PostMapping("/users/{id}/delete")
    public ResponseEntity<?> deleteUser(@PathVariable Long id, @RequestBody DeletionRequest request,
                                        HttpSession session) {
        try {
            com.kpmg.qtracker.service.UserDeletion.Done done = userDeletion.delete(id,
                    (User) session.getAttribute("currentUser"), request.email(), request.reason(), request.token(),
                    request.acknowledged());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("deleted", true);
            payload.put("id", done.userId());
            payload.put("mail", done.email());
            payload.put("unfinishedControls", done.unfinishedControls());
            payload.put("audit", auditPayload(done.audit()));
            return ResponseEntity.ok(payload);
        } catch (com.kpmg.qtracker.service.UserDeletion.Refused refused) {
            return ResponseEntity.status(refused.status()).body(refused.getMessage());
        }
    }

    /** A new user with a role; a User without Visibility or Access starts at My controls and Read Only. */
    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestParam String email,
                                        @RequestParam(required = false) String displayName,
                                        @RequestParam(required = false) String role,
                                        @RequestParam(required = false) String visibility,
                                        @RequestParam(required = false) String access,
                                        @RequestParam(defaultValue = "true") boolean enabled,
                                        HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!AccessPolicy.canManageUsers(AccessPolicy.Subject.of(currentUser))) {
            return ResponseEntity.status(403).body("Forbidden");
        }

        if (serviceAddressWithSso(email)) {
            return ResponseEntity.badRequest().body(CORPORATE_EMAIL_REQUIRED);
        }
        try {
            // With SSO no password: nobody signs in with one
            User created = userService.createUser(email, displayName, role, visibility, access, enabled,
                    authMode.password());

            AdminAuditLog audit = adminAuditService.logActionWithChanges(
                    currentUser.getMail(),
                    currentUser.getDisplayName(),
                    "USER_CREATE",
                    null,
                    "Created user " + created.getMail(),
                "mail,displayName,role,enabled",
                    "-",
                    "mail=" + created.getMail()
                    + ", displayName=" + created.getDisplayName()
                            + ", " + accessValues(created)
            );

            Map<String, Object> payload = userPayload(created);
            payload.put("audit", auditPayload(audit));
            return ResponseEntity.ok(payload);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
    }

    @PutMapping("/admin/users/{id}/email")
    public ResponseEntity<?> updateUserEmail(@PathVariable Long id,
                                             @RequestParam String email,
                                             HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!AccessPolicy.canManageUsers(AccessPolicy.Subject.of(currentUser))) {
            return ResponseEntity.status(403).body("Forbidden");
        }

        if (serviceAddressWithSso(email)) {
            return ResponseEntity.badRequest().body(CORPORATE_EMAIL_REQUIRED);
        }
        try {
            User before = userService.getUserById(id)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));
            String oldEmail = before.getMail();

            User updated = userService.updateUserEmail(id, email);
            String newEmail = updated.getMail();

            adminAuditService.logActionWithChanges(
                    currentUser.getMail(),
                    currentUser.getDisplayName(),
                    "USER_EMAIL_UPDATE",
                    null,
                    "Changed email from " + oldEmail + " to " + newEmail + " for user id " + updated.getId(),
                    "mail",
                    "mail=" + oldEmail,
                    "mail=" + newEmail
            );

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", updated.getId());
            payload.put("mail", updated.getMail());
            payload.put("lastLoginAt", updated.getLastLoginAt());
            return ResponseEntity.ok(payload);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
    }


    private boolean serviceAddressWithSso(String email) {
        return authMode.sso() && loginNames.isServiceAddress(email);
    }

    private static User snapshot(User user) {
        User copy = new User();
        copy.setId(user.getId());
        copy.setMail(user.getMail());
        copy.setDisplayName(user.getDisplayName());
        copy.setAccessLevel(user.getAccessLevel());
        copy.setAccessScope(user.getAccessScope());
        copy.setEnabled(user.getEnabled());
        return copy;
    }

    /** "role=User · My controls · Read Only, enabled=true" for the audit log. */
    private static String accessValues(User user) {
        return "role=" + RoleDisplayMapper.access(user)
                + ", enabled=" + Boolean.TRUE.equals(user.getEnabled());
    }

    /** What one save changed: field names, the audit sentences, and "field=value" before and after. */
    private record Changes(List<String> fields, List<String> descriptions, List<String> before, List<String> after) {
        void add(String field, String description, Object from, Object to) {
            fields.add(field);
            descriptions.add(description);
            before.add(field + "=" + from);
            after.add(field + "=" + to);
        }
    }

    /**
     * The changes in the words of the Admin Panel. A new role is one change from the whole access before to
     * the whole access after ("User · My controls · Edit" to "SoQM Team"); a User who stays User has their
     * Visibility and Access changes listed one by one.
     */
    private static Changes changes(User before, User after) {
        Changes changes = new Changes(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        if (!Objects.equals(before.getMail(), after.getMail())) {
            changes.add("mail", "Changed email from " + before.getMail() + " to " + after.getMail(),
                    before.getMail(), after.getMail());
        }
        if (!Objects.equals(before.getDisplayName(), after.getDisplayName())) {
            changes.add("displayName", "Changed name from " + before.getDisplayName() + " to " + after.getDisplayName(),
                    before.getDisplayName(), after.getDisplayName());
        }
        AccessPolicy.Profile from = AccessPolicy.Profile.of(before);
        AccessPolicy.Profile to = AccessPolicy.Profile.of(after);
        if (from.role() != to.role()) {
            String fromText = RoleDisplayMapper.summary(from);
            String toText = RoleDisplayMapper.summary(to);
            changes.add("role", "Changed role from " + fromText + " to " + toText, fromText, toText);
        } else {
            if (from.visibility() != to.visibility()) {
                String fromText = from.visibility().getDisplayName();
                String toText = to.visibility().getDisplayName();
                changes.add("visibility", "Changed visibility from " + fromText + " to " + toText, fromText, toText);
            }
            if (from.access() != to.access()) {
                String fromText = from.access().getDisplayName();
                String toText = to.access().getDisplayName();
                changes.add("access", "Changed access from " + fromText + " to " + toText, fromText, toText);
            }
        }
        boolean beforeEnabled = Boolean.TRUE.equals(before.getEnabled());
        boolean afterEnabled = Boolean.TRUE.equals(after.getEnabled());
        if (beforeEnabled != afterEnabled) {
            changes.add("enabled", "Changed status from " + activeInactive(beforeEnabled) + " to "
                    + activeInactive(afterEnabled), activeInactive(beforeEnabled), activeInactive(afterEnabled));
        }
        return changes;
    }

    /** One user as the Admin Panel row shows it. */
    private static Map<String, Object> userPayload(User user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", user.getId());
        payload.put("mail", user.getMail());
        payload.put("displayName", user.getDisplayName());
        AccessPolicy.Profile profile = AccessPolicy.Profile.of(user);
        payload.put("role", profile.role());
        payload.put("visibility", profile.visibility());
        payload.put("access", profile.access());
        payload.put("summary", RoleDisplayMapper.summary(profile));
        payload.put("enabled", Boolean.TRUE.equals(user.getEnabled()));
        payload.put("lastLoginAt", user.getLastLoginAt() != null ? user.getLastLoginAt().format(DATE_TIME) : null);
        return payload;
    }

    /** The new Audit Trail line, as the Admin Panel shows it (null when the entry could not be saved). */
    private static Map<String, Object> auditPayload(AdminAuditLog log) {
        if (log == null) {
            return null;
        }
        AdminAuditTrail.Entry entry = AdminAuditTrail.entry(log);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("group", entry.group().name());
        payload.put("createdAt", entry.createdAt() != null ? entry.createdAt().format(DATE_TIME) : "-");
        payload.put("changedBy", entry.changedBy());
        payload.put("action", entry.action());
        payload.put("target", entry.target());
        return payload;
    }

    private UserDTO convertToDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setDisplayName(user.getDisplayName());
        dto.setMail(user.getMail());
        dto.setTitle(RoleDisplayMapper.access(user));
        dto.setRole(AccessPolicy.Profile.of(user).role().name());
        return dto;
    }

    private static String activeInactive(boolean value) {
        return value ? "Active" : "Inactive";
    }
}
