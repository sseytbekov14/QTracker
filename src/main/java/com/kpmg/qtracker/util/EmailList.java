package com.kpmg.qtracker.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * E-mail lists as the assignment columns store them (controls.facilitator, control_operator,
 * soqm_team, process_owner, control_shared_with): addresses separated by commas or semicolons,
 * with or without spaces. Everything that reads such a list goes through here.
 */
public final class EmailList {
    private static final Pattern SEPARATOR = Pattern.compile("[,;]");

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
        List<String> emails = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String part : SEPARATOR.split(raw)) {
            String email = part.trim();
            if (!email.isEmpty() && seen.add(email.toLowerCase(Locale.ROOT))) {
                emails.add(email);
            }
        }
        return Collections.unmodifiableList(emails);
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
