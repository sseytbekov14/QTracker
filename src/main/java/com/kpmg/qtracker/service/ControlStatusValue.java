package com.kpmg.qtracker.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Control Status of a control (controls.control_status): Not Applicable, Active, Deleted or Superseded, the
 * choices of View Control's Control tab; the database allows only these codes (chk_controls_control_status). It is
 * not the workflow status (performance_status).
 *
 * <p>A Deleted or Superseded control stays in every list and counter, marked with its badge; Overdue, Active and
 * Completed count it by its workflow status as any other control. Create Control makes no next cycle from it
 * (business decision of 2026-10-13, {@link AccessPolicy#nextCycleRefusal}).
 */
public final class ControlStatusValue {

    public static final String DELETED = "DELETED";
    public static final String SUPERSEDED = "SUPERSEDED";

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("NOT_APPLICABLE", "Not Applicable");
        LABELS.put("ACTIVE", "Active");
        LABELS.put(DELETED, "Deleted");
        LABELS.put(SUPERSEDED, "Superseded");
    }

    /** The codes, in the order of the choices. */
    public static final List<String> CODES = List.copyOf(LABELS.keySet());

    private ControlStatusValue() {
    }

    /** The code for a value given as a code or a label, in any case ("superseded", "Not Applicable"); empty otherwise. */
    public static Optional<String> canonical(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String key = value.trim().replace(' ', '_').toUpperCase(Locale.ROOT);
        return LABELS.containsKey(key) ? Optional.of(key) : Optional.empty();
    }

    /** Deleted or Superseded: no next cycle is made from it. */
    public static boolean isRetired(String value) {
        return canonical(value).map(code -> DELETED.equals(code) || SUPERSEDED.equals(code)).orElse(false);
    }

    /** The badge a list shows for a Deleted or Superseded control ("Deleted", "Superseded"); null for any other. */
    public static String badge(String value) {
        return isRetired(value) ? LABELS.get(canonical(value).orElseThrow()) : null;
    }

    /** The label of the View Control select ("Active", "Not Applicable"); an unknown stored value as it is. */
    public static String label(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return canonical(value).map(LABELS::get).orElse(value.trim());
    }

    public static String invalidMessage() {
        return "Control Status must be one of " + String.join(", ", LABELS.values());
    }
}
