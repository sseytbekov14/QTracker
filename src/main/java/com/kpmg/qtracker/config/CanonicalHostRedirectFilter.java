package com.kpmg.qtracker.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Sends every request to the one site address (app.canonical-host) before anything else runs, SAML included:
 * a sign-in started on another name of the same server (https://kzqtracker/) comes back from Entra to the
 * Reply URL on the full name, where the session of the short name is not sent, and fails on /sign-in-failed.
 * Off when the property is empty; requests to localhost are never redirected.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CanonicalHostRedirectFilter extends OncePerRequestFilter {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1");

    private final String canonicalHost;

    public CanonicalHostRedirectFilter(@Value("${app.canonical-host:}") String canonicalHost) {
        this.canonicalHost = canonicalHost == null ? "" : canonicalHost.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (canonicalHost.isEmpty()) {
            return true;
        }
        String host = request.getServerName() == null ? "" : request.getServerName().toLowerCase(Locale.ROOT);
        return host.equals(canonicalHost) || LOCAL_HOSTS.contains(host);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String query = request.getQueryString();
        String location = "https://" + canonicalHost + request.getRequestURI() + (query == null ? "" : "?" + query);
        response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
        response.setHeader(HttpHeaders.LOCATION, location);
    }
}
