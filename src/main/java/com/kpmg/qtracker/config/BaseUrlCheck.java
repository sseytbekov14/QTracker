package com.kpmg.qtracker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * On stage and prod, a warning at start when app.base-url (APP_BASE_URL) cannot be the address of the links in the
 * e-mails ("Go to Control" = app.base-url + /view-control/{id}): empty, or a localhost address. The application starts
 * all the same.
 */
@Component
@Profile({"stage", "prod"})
public class BaseUrlCheck {

    private static final Logger log = LoggerFactory.getLogger(BaseUrlCheck.class);
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "0.0.0.0", "[::1]", "::1");

    private final String baseUrl;

    public BaseUrlCheck(@Value("${app.base-url:}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfUnusable() {
        problem(baseUrl).ifPresent(problem -> log.warn("app.base-url (APP_BASE_URL) is \"{}\": {}; the links of the"
                + " e-mails lead nowhere", baseUrl == null ? "" : baseUrl, problem));
    }

    /** Why the address cannot be the links' one, or empty when it can. */
    static Optional<String> problem(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Optional.of("empty");
        }
        String host;
        try {
            host = URI.create(baseUrl.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return Optional.of("not an address");
        }
        if (host == null) {
            return Optional.of("not an absolute address");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (LOCAL_HOSTS.contains(lower) || lower.endsWith(".localhost")) {
            return Optional.of("points to localhost");
        }
        return Optional.empty();
    }
}
