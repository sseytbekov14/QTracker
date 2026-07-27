package com.kpmg.qtracker.controller;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class AuthController {

    private static final String MSG_BAD_CREDENTIALS = "Invalid username or password";
    private static final String MSG_DISABLED = "Your account is disabled. Please contact the system administrator.";
    private static final String MSG_LOCKED = "Too many failed login attempts. Try again later.";

    @GetMapping("/login")
    public String loginPage(HttpSession session, Model model, @RequestParam(required = false) String error) {
        if (error != null) {
            AuthenticationException exception = (AuthenticationException) session.getAttribute("SPRING_SECURITY_LAST_EXCEPTION");
            if (exception != null) {
                model.addAttribute("error", toUserMessage(exception));
            } else {
                model.addAttribute("error", MSG_BAD_CREDENTIALS);
            }
            session.removeAttribute("SPRING_SECURITY_LAST_EXCEPTION");
        }
        return "login";
    }

    private String toUserMessage(AuthenticationException exception) {
        if (exception instanceof DisabledException) {
            return MSG_DISABLED;
        }
        if (exception instanceof LockedException) {
            return MSG_LOCKED;
        }
        if (exception instanceof BadCredentialsException) {
            return MSG_BAD_CREDENTIALS;
        }
        return MSG_BAD_CREDENTIALS;
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.removeAttribute("currentUser");
        return "redirect:/login";
    }
}
