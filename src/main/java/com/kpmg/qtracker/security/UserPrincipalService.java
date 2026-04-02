package com.kpmg.qtracker.security;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserPrincipalService {

    private final UserRepository userRepository;

    public Optional<UserRecord> loadUserByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }

        String login = email.trim();
        return userRepository.findByMail(login)
                .or(() -> userRepository.findByUsername(login))
                .filter(user -> isBcryptHash(user.getPassword()))
                .map(this::toUserRecord);
    }

    public Optional<UserPrincipal> loadByEmail(String email) {
        return loadUserByEmail(email)
                .map(userRecord -> new UserPrincipal(userRecord.id(), userRecord.email(), userRecord.roles()));
    }

    private UserRecord toUserRecord(User user) {
        return new UserRecord(
                user.getId(),
                user.getMail(),
                user.getPassword(),
                mapRoles(user.getRole())
        );
    }

    private Set<String> mapRoles(String roleField) {
        if (roleField == null || roleField.isBlank()) {
            return Set.of();
        }

        Set<String> roles = new LinkedHashSet<>();
        Arrays.stream(roleField.split("[,;]"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(this::normalizeRole)
                .forEach(roles::add);
        return roles;
    }

    private String normalizeRole(String role) {
        return role.replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }

    private boolean isBcryptHash(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.startsWith("$2a$")
                || value.startsWith("$2b$")
                || value.startsWith("$2y$");
    }

    public record UserRecord(Long id, String email, String password, Set<String> roles) {
    }
}
