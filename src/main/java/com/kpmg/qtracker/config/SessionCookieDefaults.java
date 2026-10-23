package com.kpmg.qtracker.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The session cookie with SSO: SameSite=None, Secure, HttpOnly. Microsoft posts the answer to the Assertion
 * Consumer Service from its own site; with SameSite=Lax (the browsers' default) that POST comes without the
 * session cookie, so QTracker would not find the request it sent nor the page to come back to. None needs Secure
 * (HTTPS only), HttpOnly keeps it from scripts. Only defaults: a value given in
 * SERVER_SERVLET_SESSION_COOKIE_SAME_SITE, SERVER_SERVLET_SESSION_COOKIE_SECURE or
 * SERVER_SERVLET_SESSION_COOKIE_HTTP_ONLY (or the same properties anywhere else) wins; an empty one counts as not
 * given. Without SSO nothing is added. Registered in META-INF/spring.factories.
 */
public class SessionCookieDefaults implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE = "qtrackerSsoSessionCookieDefaults";
    static final String SAME_SITE = "server.servlet.session.cookie.same-site";
    static final String SECURE = "server.servlet.session.cookie.secure";
    static final String HTTP_ONLY = "server.servlet.session.cookie.http-only";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = defaults(environment);
        if (!defaults.isEmpty()) {
            // Only keys nobody gave a value: first, so an empty variable does not hide the default
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, defaults));
        }
    }

    /** The cookie settings SSO needs and nobody has set. */
    static Map<String, Object> defaults(Environment environment) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        if (!"true".equalsIgnoreCase(String.valueOf(environment.getProperty("auth.sso.enabled")).trim())) {
            return defaults;
        }
        putIfUnset(environment, defaults, SAME_SITE, "none");
        putIfUnset(environment, defaults, SECURE, "true");
        putIfUnset(environment, defaults, HTTP_ONLY, "true");
        return defaults;
    }

    private static void putIfUnset(Environment environment, Map<String, Object> defaults, String key, String value) {
        String current = environment.getProperty(key);
        if (current == null || current.isBlank()) {
            defaults.put(key, value);
        }
    }

    @Override
    public int getOrder() {
        // After the configuration files are read (ConfigDataEnvironmentPostProcessor)
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
