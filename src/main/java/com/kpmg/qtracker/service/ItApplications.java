package com.kpmg.qtracker.service;

import com.kpmg.qtracker.util.ValueList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IT Applications on the Details tab (business decision of 2026-10-19): several systems of a fixed list, stored in
 * controls.it_applications as ValueList writes them ("KICS; CQS"). A value saved before the list (older or imported
 * data, read as one value) stays, shown "not in list", until SoQM Admin Team remove it. Who sees and changes them: as
 * Other Related Controls (everyone who sees the control / ControlPermission.canEditAll).
 */
public final class ItApplications {

    /** The field's name, also in the Changelog. */
    public static final String LABEL = "IT Applications";
    /** The systems to choose from, in the order the business gave them (2026-10-15/16). */
    public static final List<String> OPTIONS = List.of(
            "OpenPD",
            "SuccessFactory",
            "Active Directory",
            "IMS staff-directory",
            "CEAC",
            "Sentinel",
            "GLMS",
            "KICS",
            "CQS",
            "HR System",
            "Cloud Environment / Secure Server OS",
            "Cloud Environment / Secure Server",
            "Finance System");
    /** More values than this on one control are refused. */
    public static final int MAX = 50;

    private static final Map<String, String> BY_KEY = byKey();

    private ItApplications() {
    }

    /** The stored systems in their order, each marked when the list does not have it. */
    public static List<ChosenValues.Shown> of(String stored) {
        return ChosenValues.shown(stored, BY_KEY);
    }

    /** What a save stores: systems of the list, or ones the control had before; or why it is refused. */
    public static ChosenValues.Checked check(String before, String incoming) {
        return ChosenValues.check(LABEL, "application", before, incoming, BY_KEY, MAX);
    }

    private static Map<String, String> byKey() {
        Map<String, String> byKey = new LinkedHashMap<>();
        OPTIONS.forEach(option -> byKey.put(ValueList.key(option), option));
        return Map.copyOf(byKey);
    }
}
