package com.kpmg.qtracker.security;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
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
                .filter(user -> isBcryptHash(user.getPassword()))
                .map(this::toUserRecord);
    }

    public Optional<UserPrincipal> loadByEmail(String email) {
        return loadUserByEmail(email)
                .map(userRecord -> new UserPrincipal(userRecord.id(), userRecord.email(), userRecord.roles()));
    }

    /** Authorities: the access level (SOQM, PARTICIPANT, READ_ONLY) and ADMIN for the admin flag. */
    private UserRecord toUserRecord(User user) {
        Set<String> roles = new LinkedHashSet<>();
        roles.add(String.valueOf(user.getAccessLevel() != null ? user.getAccessLevel() : AccessLevel.READ_ONLY));
        if (Boolean.TRUE.equals(user.getAdminAccess())) {
            roles.add("ADMIN");
        }

        return new UserRecord(
                user.getId(),
                user.getMail(),
                user.getPassword(),
                Boolean.TRUE.equals(user.getEnabled()),
                roles
        );
    }

    private boolean isBcryptHash(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.startsWith("$2a$")
                || value.startsWith("$2b$")
                || value.startsWith("$2y$");
    }

    public record UserRecord(Long id, String email, String password, boolean enabled, Set<String> roles) {
    }
}
