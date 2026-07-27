package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.UserDTO;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final AdminAuditService adminAuditService;

    @GetMapping("/users")
    public List<UserDTO> getAllUsers() {
        return userService.getAllUsers().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @GetMapping("/users/{email}")
    public ResponseEntity<UserDTO> getUserByEmail(@PathVariable String email) {
        return userService.getUserByEmail(email)
                .map(user -> ResponseEntity.ok(convertToDTO(user)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/users/{id}/access")
    public ResponseEntity<?> updateUserAccess(@PathVariable Long id,
                                              @RequestParam String role,
                                              @RequestParam(required = false) String secondaryRole,
                                              @RequestParam(defaultValue = "false") boolean adminAccess,
                                              @RequestParam(defaultValue = "false") boolean enabled,
                                              HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!userService.hasAdminAccess(currentUser)) {
            return ResponseEntity.status(403).body("Forbidden");
        }

        try {
            User before = userService.getUserById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

            User updated = userService.updateUserAccess(id, role, secondaryRole, adminAccess, enabled, currentUser.getId());
            String description = buildAccessUpdateDescription(before, updated);

            adminAuditService.logActionWithChanges(
                    currentUser.getMail(),
                    currentUser.getDisplayName(),
                    "USER_ACCESS_UPDATE",
                    null,
                description,
                        "role,secondaryRole,adminAccess,enabled",
                "role=" + before.getRole()
                        + ", secondaryRole=" + before.getSecondaryRole()
                    + ", adminAccess=" + Boolean.TRUE.equals(before.getAdminAccess())
                    + ", enabled=" + Boolean.TRUE.equals(before.getEnabled()),
                    "role=" + updated.getRole()
                            + ", secondaryRole=" + updated.getSecondaryRole()
                            + ", adminAccess=" + Boolean.TRUE.equals(updated.getAdminAccess())
                            + ", enabled=" + Boolean.TRUE.equals(updated.getEnabled())
            );

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", updated.getId());
            payload.put("mail", updated.getMail());
            payload.put("role", updated.getRole());
            payload.put("secondaryRole", updated.getSecondaryRole());
            payload.put("adminAccess", Boolean.TRUE.equals(updated.getAdminAccess()));
            payload.put("enabled", Boolean.TRUE.equals(updated.getEnabled()));
            return ResponseEntity.ok(payload);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestParam String email,
                                        @RequestParam(required = false) String displayName,
                                        @RequestParam String role,
                                        @RequestParam(defaultValue = "false") boolean adminAccess,
                                        @RequestParam(defaultValue = "true") boolean enabled,
                                        HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!userService.hasAdminAccess(currentUser)) {
            return ResponseEntity.status(403).body("Forbidden");
        }

        try {
            User created = userService.createUser(email, displayName, role, adminAccess, enabled);

            adminAuditService.logActionWithChanges(
                    currentUser.getMail(),
                    currentUser.getDisplayName(),
                    "USER_CREATE",
                    null,
                    "Created user " + created.getMail(),
                "mail,displayName,role,adminAccess,enabled",
                    "-",
                    "mail=" + created.getMail()
                    + ", displayName=" + created.getDisplayName()
                            + ", role=" + created.getRole()
                            + ", adminAccess=" + Boolean.TRUE.equals(created.getAdminAccess())
                            + ", enabled=" + Boolean.TRUE.equals(created.getEnabled())
            );

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", created.getId());
            payload.put("mail", created.getMail());
            payload.put("displayName", created.getDisplayName());
            payload.put("role", created.getRole());
            payload.put("adminAccess", Boolean.TRUE.equals(created.getAdminAccess()));
            payload.put("enabled", Boolean.TRUE.equals(created.getEnabled()));
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
        if (!userService.hasAdminAccess(currentUser)) {
            return ResponseEntity.status(403).body("Forbidden");
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


    private UserDTO convertToDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setDisplayName(user.getDisplayName());
        dto.setMail(user.getMail());
        dto.setTitle(user.getRole());
        dto.setRole(user.getRole());
        return dto;
    }

    private String buildAccessUpdateDescription(User before, User after) {
        List<String> parts = new ArrayList<>();

        String beforeRole = before.getRole() == null ? "-" : before.getRole();
        String afterRole = after.getRole() == null ? "-" : after.getRole();
        if (!beforeRole.equals(afterRole)) {
            parts.add("Changed role from " + beforeRole + " to " + afterRole);
        }

        String beforeSecondaryRole = before.getSecondaryRole() == null ? "-" : before.getSecondaryRole();
        String afterSecondaryRole = after.getSecondaryRole() == null ? "-" : after.getSecondaryRole();
        if (!beforeSecondaryRole.equals(afterSecondaryRole)) {
            parts.add("Changed additional role from " + beforeSecondaryRole + " to " + afterSecondaryRole);
        }

        boolean beforeAdminAccess = Boolean.TRUE.equals(before.getAdminAccess());
        boolean afterAdminAccess = Boolean.TRUE.equals(after.getAdminAccess());
        if (beforeAdminAccess != afterAdminAccess) {
            parts.add("Changed admin access from " + yesNo(beforeAdminAccess) + " to " + yesNo(afterAdminAccess));
        }

        boolean beforeEnabled = Boolean.TRUE.equals(before.getEnabled());
        boolean afterEnabled = Boolean.TRUE.equals(after.getEnabled());
        if (beforeEnabled != afterEnabled) {
            parts.add("Changed status from " + activeInactive(beforeEnabled) + " to " + activeInactive(afterEnabled));
        }

        if (parts.isEmpty()) {
            return "No access fields changed for " + after.getMail();
        }

        return String.join("; ", parts) + " for " + after.getMail();
    }

    private String yesNo(boolean value) {
        return value ? "YES" : "NO";
    }

    private String activeInactive(boolean value) {
        return value ? "ACTIVE" : "INACTIVE";
    }
}
