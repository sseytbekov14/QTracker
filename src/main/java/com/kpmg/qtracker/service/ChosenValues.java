package com.kpmg.qtracker.service;

import com.kpmg.qtracker.util.ValueList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A field whose values are chosen from a list (Other Related Controls, IT Applications on the Details tab): what a save
 * may store, and how the page marks a stored value that is no longer in the list.
 */
public final class ChosenValues {

    private ChosenValues() {
    }

    /** A stored value as the page shows it: notInList when the list no longer has it (it stays until removed). */
    public record Shown(String value, boolean notInList) {
    }

    /** What a save stores (the values joined, ValueList), or why it is refused. */
    public record Checked(String stored, String refusal) {

        public Optional<String> refusalIfAny() {
            return Optional.ofNullable(refusal);
        }
    }

    /** The stored values in their order, each marked when the list (by ValueList.key) does not have it. */
    public static List<Shown> shown(String stored, Map<String, String> listByKey) {
        List<Shown> shown = new ArrayList<>();
        for (String value : ValueList.parse(stored)) {
            shown.add(new Shown(value, !listByKey.containsKey(ValueList.key(value))));
        }
        return shown;
    }

    /**
     * The values a save sends, checked: each one once (case and spaces do not count), without ";", at most max of them,
     * each one in the list (then written as the list writes it) or already stored before (kept as stored, even if the
     * list no longer has it). Anything else is refused, naming the field.
     *
     * @param label     the field's name, first in a refusal ("Other Related Controls: ...")
     * @param unit      what one value is, for "at most 50 controls" ("control")
     * @param listByKey the list's values by ValueList.key, as the list writes them
     */
    public static Checked check(String label, String unit, String before, String incoming,
                                Map<String, String> listByKey, int max) {
        List<String> values = ValueList.parse(incoming);
        Optional<String> refusal = ValueList.refusal(label, values);
        if (refusal.isPresent()) {
            return new Checked(null, refusal.get());
        }
        if (values.size() > max) {
            return new Checked(null, label + ": at most " + max + " " + unit + "s");
        }
        Map<String, String> stored = new LinkedHashMap<>();
        for (String value : ValueList.parse(before)) {
            stored.putIfAbsent(ValueList.key(value), value);
        }
        List<String> kept = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String value : values) {
            String key = ValueList.key(value);
            if (listByKey.containsKey(key)) {
                kept.add(listByKey.get(key));
            } else if (stored.containsKey(key)) {
                kept.add(stored.get(key));
            } else {
                unknown.add(value);
            }
        }
        if (!unknown.isEmpty()) {
            return new Checked(null, label + ": " + String.join(", ", unknown)
                    + (unknown.size() == 1 ? " is" : " are") + " not in the list. Reload the page and choose again.");
        }
        return new Checked(ValueList.join(kept), null);
    }
}
