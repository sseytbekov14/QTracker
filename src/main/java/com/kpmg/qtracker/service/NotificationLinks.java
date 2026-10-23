package com.kpmg.qtracker.service;

import java.util.regex.Pattern;

/**
 * The link of a notification to its control (Notification.link): on the notification's page the "Go to Control"
 * button. The page hides the lines of the text that are only an address (the texts before 2026-10-16 had a "Control
 * Link:" line; the texts of NOTIFICATIONS_v5 have {LINK} on a line of its own in T4, T5, T6..T11, clickable in the
 * e-mail, see TemplateRenderer), so the button is the one link there.
 */
public final class NotificationLinks {

    /** The label of the button, on the page and in the e-mail; the texts name it. */
    public static final String BUTTON_LABEL = "Go to Control";

    /**
     * A line that is only an address of a control, or "Control Link: " with one or none: what the activation, the
     * deadline and the reminder texts held before.
     */
    private static final Pattern LINK_LINE = Pattern.compile(
            "[ \\t]*(?:Control Link:[ \\t]*\\S*|https?://\\S+|/(?:view-control|performance-cycle)/\\S+)[ \\t]*");
    private static final Pattern THREE_OR_MORE_BREAKS = Pattern.compile("\\n(?:[ \\t]*\\n){2,}");

    private NotificationLinks() {
    }

    /** The text without its link lines; every other line as it was, at most one empty line in a row. */
    public static String withoutLinkLines(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            if (!line.isBlank() && LINK_LINE.matcher(line).matches()) {
                continue;
            }
            if (!kept.isEmpty()) {
                kept.append('\n');
            }
            kept.append(line);
        }
        return THREE_OR_MORE_BREAKS.matcher(kept).replaceAll("\n\n").strip();
    }

    /** Whether an e-mail can carry the link: an absolute address (app.base-url is set), not a path of the server. */
    public static boolean isAbsolute(String link) {
        return link != null && (link.startsWith("https://") || link.startsWith("http://"));
    }
}
