package com.kpmg.qtracker.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.ErrorResponse;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.AccessPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The one server check for read-only users: every request that changes data under /api/** (editing,
 * creating, workflow steps and returns, uploading and deleting files, renaming, the Admin Panel) is
 * refused with 403 before it reaches a controller. Their own notifications (/notifications/**) stay
 * theirs to mark as read. The session user is refreshed from the database on every request
 * ({@link UserEnabledGuardFilter}), so a level change applies at once.
 */
@Component
public class ReadOnlyAccessInterceptor implements HandlerInterceptor {

    public static final String CODE = "READ_ONLY";
    public static final String MESSAGE =
            "You have read-only access: you can view and download, but not change anything.";

    private static final Logger logger = LoggerFactory.getLogger(ReadOnlyAccessInterceptor.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final ObjectMapper objectMapper;

    public ReadOnlyAccessInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/")) {
            return true;
        }
        HttpSession session = request.getSession(false);
        Object current = session != null ? session.getAttribute("currentUser") : null;
        if (!(current instanceof User user) || AccessPolicy.mayWrite(AccessPolicy.Subject.of(user))) {
            // No session user: the controllers answer 401 themselves
            return true;
        }

        logger.warn("Read-only user {} refused: {} {}", user.getId(), request.getMethod(), path);
        String correlationId = MDC.get("correlationId");
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                new ErrorResponse(CODE, MESSAGE, correlationId == null ? "N/A" : correlationId));
        return false;
    }
}
