package com.kpmg.qtracker.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What the login form's first field means. With {@code auth.username-login.enabled} a username without "@"
 * is the address {@code <username>@<auth.username-domain>} (the address the user import gives people), or else
 * the only account with that part before "@" on any domain (DevAuthenticationProvider); an e-mail is taken as it
 * is. Case and spaces never count. Without the setting only an e-mail signs in.
 */
@Component
public class LoginNameResolver {

    public static final String DEFAULT_DOMAIN = "qtracker.local";

    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final boolean usernameLogin;
    private final String domain;

    public LoginNameResolver(@Value("${auth.username-login.enabled:false}") boolean usernameLogin,
                             @Value("${auth.username-domain:" + DEFAULT_DOMAIN + "}") String domain) {
        this.usernameLogin = usernameLogin;
        this.domain = normalize(domain);
    }

    /** The address to sign in with: lower case without spaces, the domain added to a username. */
    public String toMail(String typed) {
        String login = normalize(typed);
        if (usernameLogin && !login.isEmpty() && !login.contains("@")) {
            return login + "@" + domain;
        }
        return login;
    }

    /** The username typed (lower case, no spaces), when the form takes usernames and no "@" was typed. */
    public Optional<String> username(String typed) {
        String login = normalize(typed);
        return usernameLogin && !login.isEmpty() && !login.contains("@") ? Optional.of(login) : Optional.empty();
    }

    /** The address of a username, also when the login form does not take usernames (the user import). */
    public String mailOf(String username) {
        return normalize(username) + "@" + domain;
    }

    public boolean usernameLogin() {
        return usernameLogin;
    }

    public String domain() {
        return domain;
    }

    private static String normalize(String value) {
        return value == null ? "" : SPACES.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
