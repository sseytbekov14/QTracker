package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private static final String DEFAULT_NEW_USER_PASSWORD = "aaa";
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

    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);
    }

    public User saveUser(User user) {
        return userRepository.save(user);
    }

    public boolean userExists(String email) {
        return userRepository.existsByMail(email);
    }

    /** The access part of the Admin Panel save (level, scope, admin access, status); see {@link #updateUser}. */
    public User updateUserAccess(Long targetUserId,
                                 String level,
                                 String scope,
                                 Boolean adminAccess,
                                 Boolean enabled,
                                 Long actingUserId) {
        return updateUser(targetUserId, null, null, level, scope, adminAccess, enabled, actingUserId);
    }

    /**
     * The Admin Panel save of one user. Every value is checked before anything changes, so a refused save
     * changes nothing. A null e-mail or name keeps the stored one; the e-mail changes only before the first
     * login. A blank level or scope keeps the stored one; SoQM always gets scope ALL. Admins cannot
     * deactivate themselves, remove their own admin access or make themselves read-only (they would lose
     * the Admin Panel changes). The old role columns are not touched.
     */
    public User updateUser(Long targetUserId,
                           String email,
                           String displayName,
                           String level,
                           String scope,
                           Boolean adminAccess,
                           Boolean enabled,
                           Long actingUserId) {
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String nextMail = targetUser.getMail();
        if (email != null) {
            String normalizedEmail = normalizeEmail(email);
            String currentEmail = targetUser.getMail() == null ? "" : targetUser.getMail().trim().toLowerCase(Locale.ROOT);
            if (!normalizedEmail.equals(currentEmail)) {
                if (targetUser.getLastLoginAt() != null) {
                    throw new IllegalArgumentException("Email can be changed only before the first login");
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

        AccessLevel nextLevel = level == null || level.isBlank()
                ? storedLevel(targetUser)
                : AccessLevel.tryFrom(level)
                        .orElseThrow(() -> new IllegalArgumentException("Unsupported access level: " + level));
        AccessScope nextScope;
        if (scope == null || scope.isBlank()) {
            nextScope = nextLevel == AccessLevel.SOQM ? AccessScope.ALL : storedScope(targetUser);
        } else {
            nextScope = resolveScope(nextLevel, scope);
        }

        boolean nextAdminAccess = adminAccess != null ? adminAccess : Boolean.TRUE.equals(targetUser.getAdminAccess());
        boolean nextEnabled = enabled != null ? enabled : Boolean.TRUE.equals(targetUser.getEnabled());
        boolean selfUpdate = actingUserId != null && actingUserId.equals(targetUser.getId());
        if (selfUpdate && !nextEnabled) {
            throw new IllegalArgumentException("You cannot deactivate your own account");
        }
        if (selfUpdate && !nextAdminAccess) {
            throw new IllegalArgumentException("You cannot remove your own admin access");
        }
        if (selfUpdate && nextLevel == AccessLevel.READ_ONLY) {
            throw new IllegalArgumentException("You cannot make your own access read-only");
        }

        targetUser.setMail(nextMail);
        targetUser.setDisplayName(nextName);
        targetUser.setAccessLevel(nextLevel);
        targetUser.setAccessScope(nextScope);
        targetUser.setAdminAccess(nextAdminAccess);
        targetUser.setEnabled(nextEnabled);
        return userRepository.save(targetUser);
    }

    /**
     * A new user with an access level and scope; no old role. SoQM always sees every control, so its
     * scope is ALL (a missing scope of anyone else is OWN).
     */
    public User createUser(String email,
                           String displayName,
                           String level,
                           String scope,
                           Boolean adminAccess,
                           Boolean enabled) {
        if (level == null || level.isBlank()) {
            throw new IllegalArgumentException("Access level is required");
        }
        AccessLevel accessLevel = AccessLevel.tryFrom(level)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported access level: " + level));
        AccessScope accessScope = resolveScope(accessLevel, scope);
        return userRepository.save(newUser(email, displayName, accessLevel, accessScope, adminAccess, enabled));
    }

    /** The scope for a level: SoQM only ALL; a blank scope means ALL for SoQM and OWN for everyone else. */
    public static AccessScope resolveScope(AccessLevel level, String scope) {
        if (scope == null || scope.isBlank()) {
            return level == AccessLevel.SOQM ? AccessScope.ALL : AccessScope.OWN;
        }
        AccessScope accessScope = AccessScope.tryFrom(scope)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported scope: " + scope));
        if (level == AccessLevel.SOQM && accessScope != AccessScope.ALL) {
            throw new IllegalArgumentException("SoQM always sees all controls: scope must be ALL");
        }
        return accessScope;
    }

    private static AccessLevel storedLevel(User user) {
        return user.getAccessLevel() != null ? user.getAccessLevel() : AccessLevel.READ_ONLY;
    }

    private static AccessScope storedScope(User user) {
        return user.getAccessScope() != null ? user.getAccessScope() : AccessScope.OWN;
    }

    private User newUser(String email, String displayName, AccessLevel level, AccessScope scope,
                         Boolean adminAccess, Boolean enabled) {
        String normalizedEmail = normalizeEmail(email);
        if (userRepository.existsByMail(normalizedEmail)) {
            throw new IllegalArgumentException("User with this email already exists");
        }

        User user = new User();
        user.setMail(normalizedEmail);
        user.setDisplayName(resolveDisplayName(displayName, normalizedEmail));
        user.setAccessLevel(level);
        user.setAccessScope(scope);
        user.setAdminAccess(adminAccess != null && adminAccess);
        user.setEnabled(enabled == null || enabled);
        user.setPassword(passwordEncoder.encode(DEFAULT_NEW_USER_PASSWORD));
        return user;
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

        String normalizedEmail = normalizeEmail(email);

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
