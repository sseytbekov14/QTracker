package com.kpmg.qtracker.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A field of several values kept in one text column (Other Related Controls, IT Applications on the Details tab):
 * "SAP; KICS". Values are separated by ";", their surrounding spaces do not count, empty ones are dropped; a value
 * itself may not hold ";". Everything that reads, writes or shows such a field goes through here: the save, the
 * Changelog and the audit (the list before and after), the Excel.
 */
public final class ValueList {

    public static final String SEPARATOR = ";";
    /** Between two values when the list is written: in the column, the Changelog and the Excel. */
    public static final String JOINER = "; ";
    /** A value longer than this is refused. */
    public static final int MAX_VALUE_LENGTH = 255;

    private ValueList() {
    }

    /** The values in their stored order, trimmed, without empty ones; a repeat stays (see {@link #refusal}). */
    public static List<String> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        for (String part : stored.split(SEPARATOR, -1)) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return Collections.unmodifiableList(values);
    }

    /** "SAP; KICS": the values trimmed, without empty ones; "" for none. Check them first with {@link #refusal}. */
    public static String join(Collection<String> values) {
        if (values == null) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                kept.add(value.trim());
            }
        }
        return String.join(JOINER, kept);
    }

    /** The stored text written again the one way ("SAP ;KICS;" -> "SAP; KICS"). */
    public static String normalize(String stored) {
        return join(parse(stored));
    }

    /** Whether two stored texts hold the same values in the same order (spaces and empty values aside). */
    public static boolean same(String first, String second) {
        return parse(first).equals(parse(second));
    }

    /** What two values are compared by: case and spaces do not count ("kdn-1 " is "KDN-1", "KDN - 1" too). */
    public static String key(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** Whether the values hold this one, compared by {@link #key}. */
    public static boolean contains(Collection<String> values, String value) {
        String wanted = key(value);
        return values != null && values.stream().filter(Objects::nonNull).anyMatch(v -> key(v).equals(wanted));
    }

    /**
     * Why these values cannot be stored, named for the field ("IT Applications: ..."), or empty: an empty value,
     * one with ";", one longer than {@link #MAX_VALUE_LENGTH}, one given twice (ignoring case and spaces).
     */
    public static Optional<String> refusal(String label, List<String> values) {
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            String trimmed = value == null ? "" : value.trim();
            if (trimmed.isEmpty()) {
                return Optional.of(label + ": an empty value");
            }
            if (trimmed.contains(SEPARATOR)) {
                return Optional.of(label + ": \"" + trimmed + "\" holds \";\", which separates the values");
            }
            if (trimmed.length() > MAX_VALUE_LENGTH) {
                return Optional.of(label + ": a value is longer than " + MAX_VALUE_LENGTH + " characters");
            }
            if (!seen.add(key(trimmed))) {
                return Optional.of(label + ": " + trimmed + " is given twice");
            }
        }
        return Optional.empty();
    }
}
