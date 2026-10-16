package com.kpmg.qtracker.security;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * How people sign in: with Microsoft Entra ID (SAML, {@code auth.sso.enabled}, env AUTH_SSO_ENABLED) or with the
 * password form ({@code auth.password.enabled}, env AUTH_PASSWORD_ENABLED; with it the username login of
 * AUTH_USERNAME_LOGIN_ENABLED). Exactly one of the two is on: with SSO the form and the username login do not
 * exist. The Logout link and /logout follow {@code auth.logout.enabled} (AUTH_LOGOUT_ENABLED): off unless set to
 * true, with SSO and with the password alike (business decision of 2026-10-16: no Logout in QTracker; with SSO the
 * Microsoft session stays and the next page signs in again).
 * The prod profile defaults to SSO, dev and stage to the password (application-*.yml); StartupGuard refuses
 * other combinations before anything starts.
 */
@Component
public class AuthMode {

    static final String SSO = "auth.sso.enabled";
    static final String PASSWORD = "auth.password.enabled";
    static final String LOGOUT = "auth.logout.enabled";

    private final boolean sso;
    private final boolean password;
    private final boolean logout;

    public AuthMode(Environment environment) {
        Optional<String> refusal = refusal(environment);
        if (refusal.isPresent()) {
            throw new IllegalStateException(refusal.get());
        }
        this.sso = flag(environment, SSO).orElse(false);
        this.password = flag(environment, PASSWORD).orElse(!sso);
        this.logout = flag(environment, LOGOUT).orElse(false);
    }

    /** Signed in with Microsoft Entra ID (SAML). */
    public boolean sso() {
        return sso;
    }

    /** The password form (and with it the username login) exists. */
    public boolean password() {
        return password;
    }

    /** The Logout link and /logout exist. */
    public boolean logout() {
        return logout;
    }

    /** Why the sign-in settings cannot work, or empty. */
    public static Optional<String> refusal(Environment environment) {
        for (String key : new String[]{SSO, PASSWORD, LOGOUT}) {
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank() && parse(value) == null) {
                return Optional.of(key + " must be true or false, not \"" + value.trim() + "\"");
            }
        }
        boolean sso = flag(environment, SSO).orElse(false);
        boolean password = flag(environment, PASSWORD).orElse(!sso);
        if (sso && password) {
            return Optional.of("AUTH_SSO_ENABLED and AUTH_PASSWORD_ENABLED are both true: with SSO the password"
                    + " form does not exist, set AUTH_PASSWORD_ENABLED=false (or AUTH_SSO_ENABLED=false to go back"
                    + " to the password)");
        }
        if (!sso && !password) {
            return Optional.of("AUTH_SSO_ENABLED and AUTH_PASSWORD_ENABLED are both false: nobody could sign in");
        }
        return Optional.empty();
    }

    private static Optional<Boolean> flag(Environment environment, String key) {
        String value = environment.getProperty(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.ofNullable(parse(value));
    }

    private static Boolean parse(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }
}
