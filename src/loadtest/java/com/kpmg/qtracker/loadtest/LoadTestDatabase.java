package com.kpmg.qtracker.loadtest;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only database the loadtest profile may use: PostgreSQL on this computer (one host, localhost) whose name
 * ends with _loadtest. Anything else (another host, several hosts, another name, another kind of database) is
 * refused with the reason, before the application or the data generator connects.
 */
public final class LoadTestDatabase {

    public static final String NAME_SUFFIX = "_loadtest";

    private static final Pattern POSTGRES = Pattern.compile("^jdbc:postgresql://([^/?#]*)/([^?#;]*)(?:[?#;].*)?$");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private LoadTestDatabase() {
    }

    /** Why the loadtest profile must not use this connection, or empty when it is a local *_loadtest database. */
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
        if (host.isEmpty() || !LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            return Optional.of("the database is not on this computer: " + host);
        }
        return nameRefusal(matcher.group(2));
    }

    /** Why a database of this name is not a load test database, or empty when its name ends with _loadtest. */
    public static Optional<String> nameRefusal(String database) {
        String name = database == null ? "" : database.strip();
        if (name.length() <= NAME_SUFFIX.length() || !name.toLowerCase(Locale.ROOT).endsWith(NAME_SUFFIX)) {
            return Optional.of("the database name does not end with " + NAME_SUFFIX + ": " + name);
        }
        return Optional.empty();
    }

    // The kind of a URL (jdbc:h2:mem, jdbc:oracle, ...) without its address or credentials
    private static String kind(String url) {
        String[] parts = url.split(":", 4);
        return parts.length >= 3 ? parts[0] + ":" + parts[1] + ":" + parts[2].replaceAll("^//.*", "") : "unknown";
    }
}
