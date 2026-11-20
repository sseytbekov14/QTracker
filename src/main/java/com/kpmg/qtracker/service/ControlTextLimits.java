package com.kpmg.qtracker.service;

/**
 * The longest free text of a control field, in one place (2026-11-20): the server's check (@Size of ControlDTO and
 * ControlDetailsDTO), the pages' maxlength and character counters, and the columns (TEXT since V31) all follow it.
 * Fields: Control Description, PRP, Process Name, References to this Control, Department, Process Activities,
 * Control Operator's Program, Control Steps Performed and Results, SoQM Admin Team Comments, Process Owner Comments.
 */
public final class ControlTextLimits {

    /** Characters a free-text field of a control may hold. */
    public static final int MAX = 20000;

    /** Characters of the Description an e-mail shows; the whole text is on the control (Go to Control). */
    public static final int EMAIL_DESCRIPTION = 500;

    /** The most an Excel cell holds (32 767 characters); a longer value is cut, never refused by the file. */
    public static final int EXCEL_CELL = 32767;

    private ControlTextLimits() {
    }

    /** The text cut to at most max characters, "…" at the end when cut; null stays null. */
    public static String shorten(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 1)).stripTrailing() + "…";
    }
}
