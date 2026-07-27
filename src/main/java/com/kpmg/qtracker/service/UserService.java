package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private static final String DEFAULT_NEW_USER_PASSWORD = "aaa";
    private static final List<String> ALLOWED_ROLES = List.of(
            "FACILITATOR",
            "CONTROL_OPERATOR",
            "PROCESS_OWNER",
            "SOQM_TEAM",
            "KDN"
    );
        private static final Set<String> ALLOWED_SECONDARY_ROLES = Set.of(
            "FACILITATOR",
                "CONTROL_OPERATOR",
                "PROCESS_OWNER"
        );

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public List<User> getUsersByRole(String role) {
        String normalizedRole = normalizeRole(role);
        return userRepository.findByRoleIgnoreCaseOrSecondaryRoleIgnoreCase(normalizedRole, normalizedRole);
    }

    public Optional<User> getUserByEmail(String email) {
        return userRepository.findByMail(email); // Используем findByMail
    }

    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);
    }

    public User saveUser(User user) {
        return userRepository.save(user);
    }

    public boolean userExists(String email) {
        return userRepository.existsByMail(email);
    }

    public List<String> getAllowedRoles() {
        return ALLOWED_ROLES;
    }

    public boolean hasAdminAccess(User user) {
        return user != null && Boolean.TRUE.equals(user.getAdminAccess());
    }

    public User updateUserAccess(Long targetUserId,
                                 String role,
                                 String secondaryRole,
                                 Boolean adminAccess,
                                 Boolean enabled,
                                 Long actingUserId) {
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Set<String> selectedRoles = normalizeSelectedRoles(role, secondaryRole);
        String normalizedRole = selectedRoles.stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Role is required"));
        String normalizedSecondaryRole = selectedRoles.stream().skip(1).findFirst().orElse(null);

        if (normalizedSecondaryRole != null && !ALLOWED_SECONDARY_ROLES.contains(normalizedSecondaryRole)) {
            throw new IllegalArgumentException("Additional role can only be FACILITATOR, CONTROL_OPERATOR or PROCESS_OWNER");
        }

        boolean nextAdminAccess = adminAccess != null ? adminAccess : Boolean.TRUE.equals(targetUser.getAdminAccess());
        boolean nextEnabled = enabled != null ? enabled : Boolean.TRUE.equals(targetUser.getEnabled());
        boolean selfUpdate = actingUserId != null && actingUserId.equals(targetUser.getId());
        if (selfUpdate && (!nextAdminAccess || !nextEnabled)) {
            throw new IllegalArgumentException("You cannot disable your own account or remove your own admin access");
        }

        targetUser.setRole(normalizedRole);
        targetUser.setSecondaryRole(normalizedSecondaryRole);
        targetUser.setAdminAccess(nextAdminAccess);
        targetUser.setEnabled(nextEnabled);
        return userRepository.save(targetUser);
    }

    public User createUser(String email,
                           String displayName,
                           String role,
                           Boolean adminAccess,
                           Boolean enabled) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (!normalizedEmail.contains("@") || normalizedEmail.startsWith("@") || normalizedEmail.endsWith("@")) {
            throw new IllegalArgumentException("Email format is invalid");
        }
        if (userRepository.existsByMail(normalizedEmail)) {
            throw new IllegalArgumentException("User with this email already exists");
        }

        String normalizedRole = normalizeRole(role);
        if (!ALLOWED_ROLES.contains(normalizedRole)) {
            throw new IllegalArgumentException("Unsupported role: " + role);
        }

        User user = new User();
        user.setMail(normalizedEmail);
        user.setDisplayName(resolveDisplayName(displayName, normalizedEmail));
        user.setRole(normalizedRole);
        user.setAdminAccess(adminAccess != null && adminAccess);
        user.setEnabled(enabled == null || enabled);
        user.setPassword(passwordEncoder.encode(DEFAULT_NEW_USER_PASSWORD));
        return userRepository.save(user);
    }

    private String resolveDisplayName(String displayName, String fallbackEmail) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        return buildDisplayNameFromEmail(fallbackEmail);
    }

    public User updateUserEmail(Long targetUserId, String email) {
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (targetUser.getLastLoginAt() != null) {
            throw new IllegalArgumentException("Email can be changed only before the first login");
        }

        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (!normalizedEmail.contains("@") || normalizedEmail.startsWith("@") || normalizedEmail.endsWith("@")) {
            throw new IllegalArgumentException("Email format is invalid");
        }

        String currentEmail = targetUser.getMail() == null ? "" : targetUser.getMail().trim().toLowerCase(Locale.ROOT);
        if (normalizedEmail.equals(currentEmail)) {
            return targetUser;
        }

        if (userRepository.existsByMail(normalizedEmail)) {
            throw new IllegalArgumentException("User with this email already exists");
        }

        targetUser.setMail(normalizedEmail);
        return userRepository.save(targetUser);
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

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("Role is required");
        }
        String normalized = role.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase();
        if (!ALLOWED_ROLES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported role: " + role);
        }
        return normalized;
    }

    private Set<String> normalizeSelectedRoles(String primaryRole, String secondaryRole) {
        LinkedHashSet<String> normalizedRoles = new LinkedHashSet<>();

        addNormalizedRoles(normalizedRoles, primaryRole, true);
        addNormalizedRoles(normalizedRoles, secondaryRole, false);

        if (normalizedRoles.isEmpty()) {
            throw new IllegalArgumentException("Role is required");
        }
        if (normalizedRoles.size() > 2) {
            throw new IllegalArgumentException("A user can have at most 2 roles");
        }

        return normalizedRoles;
    }

    private void addNormalizedRoles(Set<String> collector, String source, boolean required) {
        if (source == null || source.isBlank()) {
            if (required) {
                throw new IllegalArgumentException("Role is required");
            }
            return;
        }

        Arrays.stream(source.split("[,;]"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(this::normalizeRole)
                .forEach(collector::add);
    }
}