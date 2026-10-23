package com.kpmg.qtracker.security;

import com.kpmg.qtracker.entity.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Too many requests of one kind in the window answer 429 RATE_LIMITED. Counted: the password form's sign-in
 * (POST /login), writes to /api/**, the user list (GET /api/users) and the heavy calls (attachments, the Excel of
 * the list). Not counted: Microsoft's answers to /login/saml2/** (signed, one per sign-in) and everything else.
 *
 * <p>Whose requests count together: behind the TCP port forward every client has the same address, so the address
 * alone would put all people in one bucket. A sign-in counts per name typed and address together; the rest per
 * signed-in user of the session, and per address only without one (those are refused as not signed in anyway).
 *
 * <p>The limits and the window come from the environment: RATE_LIMIT_WINDOW_SECONDS (60), RATE_LIMIT_LOGIN (20),
 * RATE_LIMIT_API_WRITE (120), RATE_LIMIT_USER_LIST (120), RATE_LIMIT_HEAVY (20).
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    /** Forgotten windows are dropped when there are more keys than this. */
    private static final int SWEEP_ABOVE = 10_000;

    private final Map<String, SlidingWindow> counters = new ConcurrentHashMap<>();
    private final long windowMillis;
    private final int loginLimit;
    private final int apiWriteLimit;
    private final int userListLimit;
    private final int heavyLimit;
    private final LongSupplier clock;

    @Autowired
    public RateLimitingFilter(@Value("${rate-limit.window-seconds:60}") long windowSeconds,
                              @Value("${rate-limit.login:20}") int loginLimit,
                              @Value("${rate-limit.api-write:120}") int apiWriteLimit,
                              @Value("${rate-limit.user-list:120}") int userListLimit,
                              @Value("${rate-limit.heavy:20}") int heavyLimit) {
        this(windowSeconds, loginLimit, apiWriteLimit, userListLimit, heavyLimit, System::currentTimeMillis);
    }

    RateLimitingFilter(long windowSeconds, int loginLimit, int apiWriteLimit, int userListLimit, int heavyLimit,
                       LongSupplier clock) {
        if (windowSeconds < 1 || loginLimit < 1 || apiWriteLimit < 1 || userListLimit < 1 || heavyLimit < 1) {
            throw new IllegalStateException("RATE_LIMIT_* must be positive numbers");
        }
        this.windowMillis = windowSeconds * 1000L;
        this.loginLimit = loginLimit;
        this.apiWriteLimit = apiWriteLimit;
        this.userListLimit = userListLimit;
        this.heavyLimit = heavyLimit;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return kind(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Kind kind = kind(request);
        String key = kind.name() + "|" + request.getMethod() + "|" + (kind == Kind.LOGIN ? signInKey(request) : who(request));

        long now = clock.getAsLong();
        sweep(now);
        SlidingWindow window = counters.computeIfAbsent(key, k -> new SlidingWindow());
        if (!window.tryAcquire(now, limit(kind), windowMillis)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private enum Kind { LOGIN, HEAVY, USER_LIST, API_WRITE }

    /** What a request counts as, or null when it is not counted. */
    private static Kind kind(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return null;
        }
        path = path.substring(request.getContextPath().length());
        String method = request.getMethod();
        boolean write = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
        if (path.equals("/login") && "POST".equalsIgnoreCase(method)) {
            return Kind.LOGIN;
        }
        if (path.startsWith("/api/users") && "GET".equalsIgnoreCase(method)) {
            return Kind.USER_LIST;
        }
        if (path.startsWith("/api/") && write) {
            return path.startsWith("/api/attachments") || path.startsWith("/api/controls/export")
                    ? Kind.HEAVY : Kind.API_WRITE;
        }
        return null;
    }

    private int limit(Kind kind) {
        return switch (kind) {
            case LOGIN -> loginLimit;
            case HEAVY -> heavyLimit;
            case USER_LIST -> userListLimit;
            case API_WRITE -> apiWriteLimit;
        };
    }

    /** The name typed in the form (as the form reads it: no spaces, any case) and the address. */
    private static String signInKey(HttpServletRequest request) {
        String typed = request.getParameter("username");
        String name = typed == null ? "" : typed.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return "name:" + name + "|addr:" + request.getRemoteAddr();
    }

    /** The signed-in user of the session, else the address. */
    private static String who(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("currentUser") instanceof User user && user.getId() != null) {
            return "user:" + user.getId();
        }
        return "addr:" + request.getRemoteAddr();
    }

    private void sweep(long now) {
        if (counters.size() > SWEEP_ABOVE) {
            counters.entrySet().removeIf(entry -> entry.getValue().idleSince(now, windowMillis));
        }
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

        synchronized boolean idleSince(long now, long windowMillis) {
            return events.isEmpty() || events.peekLast() < now - windowMillis;
        }
    }
}
