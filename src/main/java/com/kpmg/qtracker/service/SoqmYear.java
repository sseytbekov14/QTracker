package com.kpmg.qtracker.service;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * SoQM years run from 1 October to 30 September and are stored as "1 OCT 2026 - 30 SEP 2027"
 * (the 2026-27 year). On 1 October the next year starts.
 */
public final class SoqmYear {

    private static final Pattern LABEL = Pattern.compile("1 OCT (\\d{4}) - 30 SEP (\\d{4})");
    // Choices offered: the two previous years, the current one and the next one
    private static final int YEARS_BACK = 2;
    private static final int YEARS_AHEAD = 1;

    private SoqmYear() {
    }

    /** Calendar year in which the SoQM year containing {@code day} starts. */
    public static int startYear(LocalDate day) {
        return day.getMonth().compareTo(Month.OCTOBER) >= 0 ? day.getYear() : day.getYear() - 1;
    }

    public static String label(int startYear) {
        return "1 OCT " + startYear + " - 30 SEP " + (startYear + 1);
    }

    public static String current(LocalDate today) {
        return label(startYear(today));
    }

    public static List<String> options(LocalDate today) {
        int current = startYear(today);
        return IntStream.rangeClosed(current - YEARS_BACK, current + YEARS_AHEAD)
                .mapToObj(SoqmYear::label)
                .toList();
    }

    /** The stored year when it is one of the options, otherwise the current one. */
    public static String preselected(String stored, LocalDate today) {
        return stored != null && options(today).contains(stored.trim()) ? stored.trim() : current(today);
    }

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        Matcher matcher = LABEL.matcher(value.trim());
        return matcher.matches()
                && Integer.parseInt(matcher.group(2)) == Integer.parseInt(matcher.group(1)) + 1;
    }

    public static String invalidMessage() {
        return "SoQM Year must be a SoQM year such as " + label(2026);
    }
}
