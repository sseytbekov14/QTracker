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
@Profile("dev")
@RequiredArgsConstructor
public class DevUserSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        seed("fac1", "fac1@qtracker.local", "Facilitator 1", "FACILITATOR", "Facilitator");
        seed("fac2", "fac2@qtracker.local", "Facilitator 2", "FACILITATOR", "Facilitator");

        seed("op1", "op1@qtracker.local", "Control Operator 1", "CONTROL_OPERATOR", "Control Operator");
        seed("op2", "op2@qtracker.local", "Control Operator 2", "CONTROL_OPERATOR", "Control Operator");

        seed("soqm1", "soqm1@qtracker.local", "SoQM Lead 1", "SOQM_LEAD", "SoQM Lead");
        seed("soqm2", "soqm2@qtracker.local", "SoQM Lead 2", "SOQM_LEAD", "SoQM Lead");

        seed("po1", "po1@qtracker.local", "Process Owner 1", "PROCESS_OWNER", "Process Owner");
        seed("po2", "po2@qtracker.local", "Process Owner 2", "PROCESS_OWNER", "Process Owner");
    }

    private void seed(String username, String mail, String displayName, String role, String title) {
        if (userRepository.existsByUsername(username) || userRepository.existsByMail(mail)) {
            return;
        }

        User user = new User();
        user.setUsername(username);
        user.setMail(mail);
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setEnabled(true);
        user.setTitle(title);
        user.setPassword(passwordEncoder.encode("aaa"));

        userRepository.save(user);
    }
}
