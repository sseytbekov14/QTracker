package com.kpmg.qtracker.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * E-mail lists as the assignment columns store them (controls.facilitator, control_operator,
 * soqm_team, process_owner, control_shared_with): addresses separated by commas or semicolons,
 * with or without spaces. Everything that reads such a list goes through here.
 */
public final class EmailList {

    private EmailList() {
    }

    /**
     * Addresses in stored order, trimmed, without blanks; a repeated address (compared ignoring case)
     * is kept once, as first written.
     */
    public static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        // Cut at the separators by hand: a list page parses five columns of every control, and the regular expression
        // with a set per call was a tenth of the CPU of the load test (the same result as split("[,;]"))
        int end = nextSeparator(raw, 0);
        if (end == raw.length()) {
            String email = raw.trim();
            return email.isEmpty() ? Collections.emptyList() : Collections.singletonList(email);
        }
        List<String> emails = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int start = 0; start <= raw.length(); start = end + 1, end = nextSeparator(raw, start)) {
            String email = raw.substring(start, end).trim();
            if (!email.isEmpty() && seen.add(email.toLowerCase(Locale.ROOT))) {
                emails.add(email);
            }
        }
        return Collections.unmodifiableList(emails);
    }

    /** The index of the next ',' or ';' from {@code from}, or the length of the text when there is none. */
    private static int nextSeparator(String raw, int from) {
        for (int i = from; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ',' || c == ';') {
                return i;
            }
        }
        return raw.length();
    }

    /** Whether the stored list holds this exact address, ignoring case and surrounding spaces. */
    public static boolean contains(String raw, String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String wanted = email.trim();
        for (String candidate : parse(raw)) {
            if (candidate.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }
}
