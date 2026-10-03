package com.kpmg.qtracker.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.ErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A missing or wrong CSRF token is logged (method and path only, never the token); on /api/** it is answered
 * with a JSON message the pages can show. Everything else gets the default 403 handling.
 */
@Component
public class CsrfAccessDeniedHandler implements AccessDeniedHandler {

    public static final String CODE = "CSRF_INVALID";
    public static final String MESSAGE = "This page is out of date. Reload the page and try again.";

    private static final Logger logger = LoggerFactory.getLogger(CsrfAccessDeniedHandler.class);

    private final AccessDeniedHandler defaultHandler = new AccessDeniedHandlerImpl();
    private final ObjectMapper objectMapper;

    public CsrfAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException exception) throws IOException, ServletException {
        if (!(exception instanceof CsrfException)) {
            defaultHandler.handle(request, response, exception);
            return;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        logger.warn("CSRF token missing or invalid: {} {}", request.getMethod(), path);

        if (!path.startsWith("/api/") || response.isCommitted()) {
            defaultHandler.handle(request, response, exception);
            return;
        }

        String correlationId = MDC.get("correlationId");
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                new ErrorResponse(CODE, MESSAGE, correlationId == null ? "N/A" : correlationId));
    }
}
