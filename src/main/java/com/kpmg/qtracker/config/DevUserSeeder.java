package com.kpmg.qtracker.config;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile({"dev", "stage"})
@RequiredArgsConstructor
public class DevUserSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        seed("fac1@qtracker.local", "Facilitator 1", "FACILITATOR");
        seed("fac2@qtracker.local", "Facilitator 2", "FACILITATOR");

        seed("op1@qtracker.local", "Control Operator 1", "CONTROL_OPERATOR");
        seed("op2@qtracker.local", "Control Operator 2", "CONTROL_OPERATOR");
        seed("admin@qtracker.local", "Admin User", "ADMIN");

        seed("soqm1@qtracker.local", "SoQM Team 1", "SOQM_TEAM");
        seed("soqm2@qtracker.local", "SoQM Team 2", "SOQM_TEAM");

        seed("po1@qtracker.local", "Process Owner 1", "PROCESS_OWNER");
        seed("po2@qtracker.local", "Process Owner 2", "PROCESS_OWNER");
    }

    private void seed(String mail, String displayName, String role) {
        if (userRepository.existsByMail(mail)) {
            return;
        }

        User user = new User();
        user.setMail(mail);
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setEnabled(true);
        user.setPassword(passwordEncoder.encode("aaa"));

        userRepository.save(user);
    }
}
