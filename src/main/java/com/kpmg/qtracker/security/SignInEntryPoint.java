package com.kpmg.qtracker.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * What a request without a sign-in gets. /api/** answers 401 with a JSON message the pages show without
 * leaving the page (js/session.js; a redirect to Microsoft would be useless to a script and lose what was
 * typed). A page goes to the sign-in: the Microsoft one with SSO, the form otherwise; the page asked for is
 * remembered and opened after the sign-in.
 */
public class SignInEntryPoint implements AuthenticationEntryPoint {

    public static final String CODE = "SESSION_EXPIRED";
    public static final String MESSAGE = "Session expired, reload the page";

    private final AuthenticationEntryPoint pages;
    private final ObjectMapper objectMapper;

    public SignInEntryPoint(String signInUrl, ObjectMapper objectMapper) {
        this.pages = new LoginUrlAuthenticationEntryPoint(signInUrl);
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException, jakarta.servlet.ServletException {
        if (!isApi(request)) {
            pages.commence(request, response, exception);
            return;
        }
        String correlationId = MDC.get("correlationId");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                new ErrorResponse(CODE, MESSAGE, correlationId == null ? "N/A" : correlationId));
    }

    public static boolean isApi(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/api/");
    }
}
