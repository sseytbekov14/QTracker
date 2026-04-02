package com.kpmg.qtracker.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final Map<String, SlidingWindow> counters = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }

        String method = request.getMethod();
        boolean loginAttempt = path.startsWith("/login") && "POST".equalsIgnoreCase(method);
        boolean enumerationRead = (path.startsWith("/api/users") || path.startsWith("/api/roles") || path.startsWith("/api/notifications"))
                && "GET".equalsIgnoreCase(method);
        boolean highRiskWrite = path.startsWith("/api/") && (
                "POST".equalsIgnoreCase(method)
                        || "PUT".equalsIgnoreCase(method)
                        || "PATCH".equalsIgnoreCase(method)
                        || "DELETE".equalsIgnoreCase(method));

        return !(loginAttempt || enumerationRead || highRiskWrite);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        int limit = resolveLimit(path);
        String key = request.getRemoteAddr() + "|" + bucket(path, request.getMethod());

        SlidingWindow window = counters.computeIfAbsent(key, k -> new SlidingWindow());
        long now = System.currentTimeMillis();
        boolean allowed = window.tryAcquire(now, limit, WINDOW_MILLIS);

        if (!allowed) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private int resolveLimit(String path) {
        if (path.startsWith("/login")) {
            return 20;
        }
        if (path.startsWith("/api/attachments") || path.startsWith("/api/controls/export")) {
            return 20;
        }
        if (path.startsWith("/api/users") || path.startsWith("/api/roles") || path.startsWith("/api/notifications")) {
            return 120;
        }
        return 120;
    }

    private String bucket(String path, String method) {
        if (path.startsWith("/login")) {
            return "login:" + method;
        }
        if (path.startsWith("/api/attachments")) {
            return "attachments:" + method;
        }
        if (path.startsWith("/api/controls/export")) {
            return "export:" + method;
        }
        if (path.startsWith("/api/users") || path.startsWith("/api/roles") || path.startsWith("/api/notifications")) {
            return "enumeration:" + method;
        }
        return "api:" + method;
    }

    private static final class SlidingWindow {
        private final ArrayDeque<Long> events = new ArrayDeque<>();

        synchronized boolean tryAcquire(long now, int limit, long windowMillis) {
            long border = now - windowMillis;
            while (!events.isEmpty() && events.peekFirst() < border) {
                events.pollFirst();
            }
            if (events.size() >= limit) {
                return false;
            }
            events.addLast(now);
            return true;
        }
    }
}
