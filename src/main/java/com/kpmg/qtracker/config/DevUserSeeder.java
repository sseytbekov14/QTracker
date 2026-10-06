package com.kpmg.qtracker.config;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Test accounts for dev and stage, one or two per kind of access. Only missing accounts are created,
 * so changes made in the Admin Panel stay; the V2 seed accounts get their access from the V6 backfill,
 * except admin@qtracker.local, which is always made SoQM Team.
 */
@Component
@Profile({"dev", "stage"})
@RequiredArgsConstructor
public class DevUserSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        seed("fac1@qtracker.local", "Facilitator 1", "FACILITATOR", AccessLevel.PARTICIPANT, AccessScope.OWN);
        seed("fac2@qtracker.local", "Facilitator 2", "FACILITATOR", AccessLevel.PARTICIPANT, AccessScope.OWN);

        seed("op1@qtracker.local", "Control Operator 1", "CONTROL_OPERATOR", AccessLevel.PARTICIPANT, AccessScope.OWN);
        seed("op2@qtracker.local", "Control Operator 2", "CONTROL_OPERATOR", AccessLevel.PARTICIPANT, AccessScope.OWN);
        seed("admin@qtracker.local", "Admin User", "ADMIN", AccessLevel.SOQM, AccessScope.ALL);
        makeSoqmTeam("admin@qtracker.local");

        seed("soqm1@qtracker.local", "SoQM Team 1", "SOQM_TEAM", AccessLevel.SOQM, AccessScope.ALL);
        seed("soqm2@qtracker.local", "SoQM Team 2", "SOQM_TEAM", AccessLevel.SOQM, AccessScope.ALL);

        seed("po1@qtracker.local", "Process Owner 1", "PROCESS_OWNER", AccessLevel.PARTICIPANT, AccessScope.OWN);
        seed("po2@qtracker.local", "Process Owner 2", "PROCESS_OWNER", AccessLevel.PARTICIPANT, AccessScope.OWN);

        // The access kinds that have no old role (there is no Master any more)
        seed("kdn1@qtracker.local", "KDN 1", "KDN", AccessLevel.READ_ONLY, AccessScope.KDN);
        seed("ro1@qtracker.local", "Read Only 1", null, AccessLevel.READ_ONLY, AccessScope.OWN);
        seed("roall1@qtracker.local", "Read Only All 1", null, AccessLevel.READ_ONLY, AccessScope.ALL);
        seed("alledit1@qtracker.local", "All Controls Edit 1", null, AccessLevel.PARTICIPANT, AccessScope.ALL);
    }

    /**
     * The dev admin account is SoQM Team (the only role with the Admin Panel), also in a database where it was
     * created earlier as a participant with the old admin flag.
     */
    private void makeSoqmTeam(String mail) {
        userRepository.findByMail(mail)
                .filter(user -> user.getAccessLevel() != AccessLevel.SOQM)
                .ifPresent(user -> {
                    user.setAccessLevel(AccessLevel.SOQM);
                    user.setAccessScope(AccessScope.ALL);
                    user.setAdminAccess(true);
                    userRepository.save(user);
                });
    }

    private void seed(String mail, String displayName, String role, AccessLevel level, AccessScope scope) {
        if (userRepository.existsByMail(mail)) {
            return;
        }

        User user = new User();
        user.setMail(mail);
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setAccessLevel(level);
        user.setAccessScope(scope);
        user.setAdminAccess(level == AccessLevel.SOQM);
        user.setEnabled(true);
        user.setPassword(passwordEncoder.encode("aaa"));

        userRepository.save(user);
    }
}
