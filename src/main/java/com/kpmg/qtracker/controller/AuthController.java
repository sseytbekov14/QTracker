package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.security.AuthMode;
import com.kpmg.qtracker.security.LoginNameResolver;
import com.kpmg.qtracker.security.SsoSignIn;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
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

    private final LoginNameResolver loginNames;
    private final AuthMode authMode;

    /** The password form; with SSO there is none and the address leads to the Microsoft sign-in instead. */
    @GetMapping("/login")
    public String loginPage(HttpSession session, Model model, @RequestParam(required = false) String error) {
        if (!authMode.password()) {
            return "redirect:/";
        }
        model.addAttribute("usernameLogin", loginNames.usernameLogin());
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

    /**
     * Microsoft signed the person in but QTracker has no active account for them: no details (which account,
     * why) on the page, they are in the log and the audit trail. No way back to the Dashboard: it would only
     * come back here.
     */
    @GetMapping(SsoSignIn.ACCESS_NOT_GRANTED_PATH)
    public String accessNotGranted(Model model, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        model.addAttribute("title", "Access is not granted");
        model.addAttribute("text", "Access is not granted, contact SoQM Team.");
        model.addAttribute("retry", false);
        return "sign-in-problem";
    }

    /** The answer of Microsoft could not be used (expired, from another tab, ...): trying again usually helps. */
    @GetMapping(SsoSignIn.SIGN_IN_FAILED_PATH)
    public String signInFailed(Model model, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        model.addAttribute("title", "Sign-in did not complete");
        model.addAttribute("text", "The sign-in with Microsoft did not complete. Try again; if it happens again,"
                + " contact SoQM Team.");
        model.addAttribute("retry", true);
        return "sign-in-problem";
    }

    /**
     * The Logout link of the sidebar: ends the server session (and with it the Spring Security sign-in) and
     * forgets the CSRF token, like the POST /logout of Spring Security; an old session cookie then opens nothing.
     */
    @GetMapping("/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new CookieClearingLogoutHandler("XSRF-TOKEN").logout(request, response, authentication);
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return "redirect:/login";
    }
}
