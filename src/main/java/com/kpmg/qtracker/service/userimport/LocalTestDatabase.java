package com.kpmg.qtracker.service.userimport;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only database a clean-slate step may change: PostgreSQL on this computer, database QTracker. Anything
 * else (another host, several hosts, another database, another kind of database) is refused with the reason.
 */
public final class LocalTestDatabase {

    public static final String NAME = "QTracker";

    private static final Pattern POSTGRES = Pattern.compile("^jdbc:postgresql://([^/?#]*)/([^?#;]*)(?:[?#;].*)?$");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private LocalTestDatabase() {
    }

    /** Why this connection must not be reset, or empty when it is the local QTracker database. */
    public static Optional<String> refusal(String jdbcUrl) {
        String url = jdbcUrl == null ? "" : jdbcUrl.strip();
        Matcher matcher = POSTGRES.matcher(url);
        if (!matcher.matches()) {
            return Optional.of("not a PostgreSQL database on this computer: " + kind(url));
        }
        String hosts = matcher.group(1);
        if (hosts.contains(",")) {
            return Optional.of("several database hosts: " + hosts);
        }
        String host = hosts.replaceFirst(":\\d+$", "");
        if (host.isEmpty() || !LOCAL_HOSTS.contains(host.toLowerCase(java.util.Locale.ROOT))) {
            return Optional.of("the database is not on this computer: " + host);
        }
        String database = matcher.group(2);
        if (!NAME.equals(database)) {
            return Optional.of("the database is not " + NAME + ": " + database);
        }
        return Optional.empty();
    }

    // The kind of a URL (jdbc:h2:mem, jdbc:oracle, ...) without its address or credentials
    private static String kind(String url) {
        String[] parts = url.split(":", 4);
        return parts.length >= 3 ? parts[0] + ":" + parts[1] + ":" + parts[2].replaceAll("^//.*", "") : "unknown";
    }
}
