package com.kpmg.qtracker.security;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
public class UserEnabledGuardFilter extends OncePerRequestFilter {

    private static final String DISABLED_MESSAGE = "Your account is disabled. Please contact the system administrator.";

    private final UserRepository userRepository;

    public UserEnabledGuardFilter(@Autowired(required = false) UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        return path.startsWith("/images/")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/webjars/")
                || path.equals("/favicon.ico")
                || path.equals("/favicon.svg")
                || path.equals("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String email = resolveCurrentUserEmail(request);
        if (email == null || email.isBlank() || userRepository == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<User> dbUserOpt = userRepository.findByMail(email.trim());
        boolean isEnabled = dbUserOpt.map(user -> Boolean.TRUE.equals(user.getEnabled())).orElse(false);
        if (!isEnabled) {
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.removeAttribute("currentUser");
                session.removeAttribute("userRole");
                session.removeAttribute("SPRING_SECURITY_CONTEXT");
                session.setAttribute("SPRING_SECURITY_LAST_EXCEPTION", new DisabledException(DISABLED_MESSAGE));
            }

            if (isApiRequest(request)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Account is disabled");
                return;
            }

            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            request.getRequestDispatcher("/login?error").forward(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveCurrentUserEmail(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof UserPrincipal userPrincipal) {
                return userPrincipal.getEmail();
            }
            if (principal instanceof UserDetails userDetails) {
                return userDetails.getUsername();
            }
            if (principal instanceof String principalString && !"anonymousUser".equals(principalString)) {
                return principalString;
            }
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object currentUser = session.getAttribute("currentUser");
        if (currentUser instanceof User user) {
            return user.getMail();
        }
        return null;
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/api/");
    }
}
