package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String username,
                        @RequestParam String password,
                        HttpSession session,
                        Model model) {

        Optional<User> userOpt = userService.getUserByUsername(username);
        if (userOpt.isEmpty()) {
            userOpt = userService.getUserByEmail(username);
        }

        if (userOpt.isPresent()) {
            User user = userOpt.get();

            boolean passwordMatches = false;
            String storedPassword = user.getPassword();
            if (storedPassword != null) {
                try {
                    passwordMatches = passwordEncoder.matches(password, storedPassword);
                } catch (IllegalArgumentException ignored) {
                    passwordMatches = storedPassword.equals(password);
                }
            }

            if (passwordMatches && Boolean.TRUE.equals(user.getEnabled())) {
                session.setAttribute("currentUser", user);
                session.setAttribute("userRole", user.getRole());

                System.out.println("LOGIN SUCCESS: User " + user.getMail() +
                        " with role: " + user.getRole());

                return "redirect:/";
            }
        }
        model.addAttribute("error", "Invalid username or password");
        return "login";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.removeAttribute("currentUser");
        return "redirect:/login";
    }
}
