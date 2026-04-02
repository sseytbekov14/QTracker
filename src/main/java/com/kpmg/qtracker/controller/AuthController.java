package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
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
    public String loginPage(HttpSession session, Model model, @RequestParam(required = false) String error) {
        if (error != null) {
            AuthenticationException exception = (AuthenticationException) session.getAttribute("SPRING_SECURITY_LAST_EXCEPTION");
            if (exception != null) {
                model.addAttribute("error", exception.getMessage());
            } else {
                model.addAttribute("error", "Invalid username or password");
            }
            session.removeAttribute("SPRING_SECURITY_LAST_EXCEPTION");
        }
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
                if (isBcryptHash(storedPassword)) {
                    passwordMatches = passwordEncoder.matches(password, storedPassword);
                } else {
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

    private boolean isBcryptHash(String value) {
        if (value == null) {
            return false;
        }
        return value.startsWith("$2a$")
                || value.startsWith("$2b$")
                || value.startsWith("$2y$");
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.removeAttribute("currentUser");
        return "redirect:/login";
    }
}
