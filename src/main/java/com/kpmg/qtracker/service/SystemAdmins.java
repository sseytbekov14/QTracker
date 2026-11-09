package com.kpmg.qtracker.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * System Admin Team (business decision of 2026-11-09): the people named by e-mail in the server's configuration
 * (SYSTEM_ADMIN_EMAILS, SystemAdminsConfig) and nowhere else - nobody gives or takes the role in the application. They
 * see everything SoQM Admin Team sees, the Admin Panel and its Audit Trail included, and change nothing
 * ({@link AccessPolicy.Subject#systemAdmin}). The list is read once when the application starts; a change needs a
 * restart. Their account must exist in QTracker (Admin Panel); while it is listed here nobody changes it there.
 */
public final class SystemAdmins {

    private static volatile Set<String> mails = Set.of();

    private SystemAdmins() {
    }

    /** Whether the e-mail is listed, in any case, spaces at the ends ignored. */
    public static boolean contains(String mail) {
        return mail != null && mails.contains(normalize(mail));
    }

    /** How many are listed (the start-up log says only this, never the addresses). */
    public static int size() {
        return mails.size();
    }

    /** The list from the configuration: comma, semicolon or space separated; blanks ignored. Tests set it too. */
    public static void replace(Collection<String> configured) {
        Set<String> next = new LinkedHashSet<>();
        if (configured != null) {
            for (String mail : configured) {
                if (mail != null && !mail.isBlank()) {
                    next.add(normalize(mail));
                }
            }
        }
        mails = Set.copyOf(next);
    }

    /** The list from one setting value, as {@link #replace(Collection)}. */
    public static void replace(String configured) {
        replace(configured == null ? null : java.util.Arrays.asList(configured.split("[,;\\s]+")));
    }

    private static String normalize(String mail) {
        return mail.trim().toLowerCase(Locale.ROOT);
    }
}
