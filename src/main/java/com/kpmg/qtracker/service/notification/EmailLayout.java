package com.kpmg.qtracker.service.notification;

import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The one layout of every QTracker e-mail (2026-10-30); a template gives only its title and its text, and the data of
 * the details block. Built for Outlook (its Word engine): tables only, every style inline, a fixed 600 px column inside
 * an Outlook-only table, the button drawn by VML in Outlook and by a styled link elsewhere. Parts: a hidden preheader
 * (the line the inbox shows after the subject), the #00338D header "QTracker", the white card with the title, the text
 * and the details of the control, the footer "automated message". The plain-text part of the e-mail has the same title,
 * text, details and footer.
 * <p>The inline styles are the light theme. A client with a dark theme of its own reads the classes (hdr, card, btn and
 * the others of {@link #DARK_COLORS}) in the head of the e-mail: Apple Mail by prefers-color-scheme, Outlook on the web
 * and new Outlook by [data-ogsc] and [data-ogsb]. Classic Outlook for Windows ignores them and repaints by itself.
 */
final class EmailLayout {

    static final String BRAND = "#00338D";
    static final String FOOTER = "This is an automated message from QTracker. Please do not reply to this e-mail.";
    private static final String BACKGROUND = "#F2F4F7";
    private static final String FONT = "font-family:Arial,Helvetica,sans-serif;";
    private static final int PREHEADER_MAX = 140;
    /** Class and dark background. */
    private static final String[][] DARK_BACKGROUNDS = {{"bg", "#111827"}, {"hdr", BRAND}, {"card", "#1F2937"},
            {"btn", "#2F6FDE"}, {"qt", "#111827"}};
    /** Class and dark text colour. */
    private static final String[][] DARK_COLORS = {{"hdr", "#FFFFFF"}, {"btn", "#FFFFFF"}, {"ttl", "#A9C4F5"},
            {"txt", "#E5E7EB"}, {"dtl-k", "#9CA3AF"}, {"dtl-v", "#E5E7EB"}, {"ftr", "#9CA3AF"}, {"qt-k", "#9CA3AF"},
            {"qt-v", "#E5E7EB"}};
    /** Class and dark border colour. */
    private static final String[][] DARK_BORDERS = {{"card", "#374151"}, {"dtl", "#374151"}, {"qt", "#5B8DEF"}};
    /** The longest comment an e-mail carries; the application has the whole of it. */
    static final int COMMENT_MAX = 1000;
    static final String COMMENT_SHORTENED = "Shortened: the full comment is in QTracker.";

    private EmailLayout() {
    }

    /** One line of the details block; a line without a value is not shown. */
    record Row(String label, String value) {

        boolean shown() {
            return value != null && !value.isBlank();
        }
    }

    /**
     * The whole HTML e-mail.
     *
     * @param title     the card's heading, plain text (escaped here)
     * @param preheader the hidden preview line, plain text (escaped here)
     * @param content   the card's text, HTML already
     */
    static String page(String title, String preheader, String content) {
        String safeTitle = escape(title);
        return "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\""
                + " \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:v=\"urn:schemas-microsoft-com:vml\""
                + " xmlns:o=\"urn:schemas-microsoft-com:office:office\" lang=\"en\">\n"
                + "<head>\n"
                + "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "<meta name=\"x-apple-disable-message-reformatting\">\n"
                + "<meta name=\"color-scheme\" content=\"light dark\">\n"
                + "<meta name=\"supported-color-schemes\" content=\"light dark\">\n"
                + "<title>" + safeTitle + "</title>\n"
                + "<!--[if mso]><xml><o:OfficeDocumentSettings><o:AllowPNG/><o:PixelsPerInch>96</o:PixelsPerInch>"
                + "</o:OfficeDocumentSettings></xml><![endif]-->\n"
                + darkStyle()
                + "</head>\n"
                + "<body class=\"bg\" style=\"margin:0;padding:0;background-color:" + BACKGROUND + ";\">\n"
                + preheaderBlock(preheader)
                + "<table class=\"bg\" role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\""
                + " bgcolor=\"" + BACKGROUND + "\" style=\"background-color:" + BACKGROUND + ";\">\n"
                + "<tr><td align=\"center\" style=\"padding:24px 12px;\">\n"
                // Outlook ignores max-width: its own fixed table keeps the column at 600 px
                + "<!--[if mso]><table role=\"presentation\" width=\"600\" align=\"center\" cellspacing=\"0\""
                + " cellpadding=\"0\" border=\"0\"><tr><td><![endif]-->\n"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\""
                + " style=\"width:100%;max-width:600px;\">\n"
                + "<tr><td class=\"hdr\" bgcolor=\"" + BRAND + "\" style=\"background-color:" + BRAND + ";padding:18px 32px;"
                + FONT + "font-size:20px;line-height:24px;font-weight:bold;color:#ffffff;\">QTracker</td></tr>\n"
                + "<tr><td class=\"card\" bgcolor=\"#ffffff\" style=\"background-color:#ffffff;padding:28px 32px 32px 32px;"
                + "border:1px solid #E1E4EA;border-top:0;\">\n"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\">\n"
                + "<tr><td style=\"padding:0 0 20px 0;\"><h1 class=\"ttl\" style=\"margin:0;" + FONT
                + "font-size:20px;line-height:26px;font-weight:bold;color:" + BRAND + ";\">" + safeTitle + "</h1></td></tr>\n"
                + "<tr><td class=\"txt\" style=\"" + FONT + "font-size:14px;line-height:21px;color:#222222;\">" + content
                + "</td></tr>\n"
                + "</table>\n"
                + "</td></tr>\n"
                + "<tr><td class=\"ftr\" align=\"center\" style=\"padding:16px 32px;" + FONT
                + "font-size:12px;line-height:18px;color:#6B7280;\">" + FOOTER + "</td></tr>\n"
                + "</table>\n"
                + "<!--[if mso]></td></tr></table><![endif]-->\n"
                + "</td></tr>\n"
                + "</table>\n"
                + "</body>\n"
                + "</html>\n";
    }

    /**
     * The details of the control: a small table with a light border, the labels grey. Lines without a value are left
     * out; nothing at all when none has one.
     */
    static String details(List<Row> rows) {
        List<Row> shown = rows.stream().filter(Row::shown).toList();
        if (shown.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\""
                + " border=\"0\">\n<tr><td style=\"padding:12px 0 4px 0;\">\n"
                + "<table class=\"dtl\" role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\""
                + " style=\"border:1px solid #E1E4EA;\">\n");
        for (Row row : shown) {
            out.append("<tr><td class=\"dtl-k\" width=\"96\" valign=\"top\" style=\"padding:6px 8px 6px 12px;").append(FONT)
                    .append("font-size:13px;line-height:19px;color:#6B7280;\">").append(escape(row.label())).append("</td>")
                    .append("<td class=\"dtl-v\" valign=\"top\" style=\"padding:6px 12px 6px 0;").append(FONT)
                    .append("font-size:13px;line-height:19px;color:#222222;word-break:break-word;\">")
                    .append(escapeLines(row.value())).append("</td></tr>\n");
        }
        return out.append("</table>\n</td></tr>\n</table>\n").toString();
    }

    /** The details in the plain-text part: "Label: value", one line each. */
    static String plainDetails(List<Row> rows) {
        return String.join("\n", rows.stream().filter(Row::shown)
                .map(row -> row.label() + ": " + row.value().strip()).toList());
    }

    /**
     * The comment of a return as a quote: a blue bar on the left, the line "Comment from ..." over the text, escaped,
     * its line breaks kept; a comment over {@link #COMMENT_MAX} characters cut with a note. Nothing without a comment.
     */
    static String quote(String from, String comment) {
        if (comment == null || comment.isBlank()) {
            return "";
        }
        String text = shortened(comment);
        String note = text.length() == normalized(comment).length() ? "" : "<br>\n<span style=\"font-size:12px;\">"
                + COMMENT_SHORTENED + "</span>";
        return "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\">\n"
                + "<tr><td style=\"padding:12px 0 4px 0;\">\n"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\">\n"
                + "<tr><td class=\"qt\" bgcolor=\"#F5F7FB\" style=\"background-color:#F5F7FB;border-left:4px solid " + BRAND
                + ";padding:8px 12px;\">\n"
                + "<div class=\"qt-k\" style=\"" + FONT + "font-size:12px;line-height:18px;font-weight:bold;color:#6B7280;\">"
                + escape(commentLabel(from)) + "</div>\n"
                + "<div class=\"qt-v\" style=\"" + FONT + "font-size:14px;line-height:21px;color:#222222;word-break:break-word;\">"
                + escapeLines(text) + note + "</div>\n"
                + "</td></tr>\n</table>\n</td></tr>\n</table>\n";
    }

    /** The comment in the plain-text part. */
    static String plainQuote(String from, String comment) {
        if (comment == null || comment.isBlank()) {
            return "";
        }
        String text = shortened(comment);
        return commentLabel(from) + ":\n" + text
                + (text.length() == normalized(comment).length() ? "" : "\n" + COMMENT_SHORTENED);
    }

    private static String commentLabel(String from) {
        return from == null || from.isBlank() ? "Comment" : "Comment from " + from.strip();
    }

    private static String normalized(String comment) {
        return comment.strip().replace("\r\n", "\n");
    }

    /** At most {@link #COMMENT_MAX} characters, "…" the last one of a cut text; a character is never split in two. */
    private static String shortened(String comment) {
        String text = normalized(comment);
        if (text.codePointCount(0, text.length()) <= COMMENT_MAX) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, COMMENT_MAX - 1)).stripTrailing() + "…";
    }

    /**
     * The "Go to Control" button, the address not shown: in Outlook a VML rounded rectangle, elsewhere a link styled
     * as a button.
     */
    static String button(String link) {
        String href = escape(link);
        return "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\">\n"
                + "<tr><td style=\"padding:16px 0 16px 0;\">"
                + "<!--[if mso]><v:roundrect xmlns:v=\"urn:schemas-microsoft-com:vml\""
                + " xmlns:w=\"urn:schemas-microsoft-com:office:word\" href=\"" + href + "\""
                + " style=\"height:42px;v-text-anchor:middle;width:200px;\" arcsize=\"10%\" stroke=\"f\" fillcolor=\""
                + BRAND + "\"><w:anchorlock/><center style=\"color:#ffffff;" + FONT
                + "font-size:15px;font-weight:bold;\">" + TemplateRenderer.BUTTON_LABEL + "</center></v:roundrect>"
                + "<![endif]-->"
                + "<!--[if !mso]><!-- --><a href=\"" + href + "\" class=\"btn\" style=\"background-color:" + BRAND
                + ";border-radius:4px;color:#ffffff;display:inline-block;" + FONT + "font-size:15px;font-weight:bold;"
                + "line-height:42px;text-align:center;text-decoration:none;width:200px;-webkit-text-size-adjust:none;\">"
                + TemplateRenderer.BUTTON_LABEL + "</a><!--<![endif]-->"
                + "</td></tr>\n"
                + "</table>\n";
    }

    /** The plain-text part: the title, the text, the footer. */
    static String plainText(String title, String text) {
        return title + "\n\n" + text + "\n\n--\n" + FOOTER;
    }

    /** The preview line: hidden in the e-mail, shown by the inbox after the subject; at most 140 characters. */
    private static String preheaderBlock(String preheader) {
        String line = preheader == null ? "" : preheader.strip();
        if (line.length() > PREHEADER_MAX) {
            line = line.substring(0, PREHEADER_MAX - 1).stripTrailing() + "…";
        }
        // The blank characters after it keep the inbox from showing the start of the card after the preview line
        return "<div style=\"display:none;font-size:1px;line-height:1px;max-height:0;max-width:0;opacity:0;"
                + "overflow:hidden;mso-hide:all;\">" + escape(line) + "&#8199;&#847;".repeat(40) + "</div>\n";
    }

    /** The colours of a dark theme, for the clients that read a style in the head (see the class comment). */
    private static String darkStyle() {
        return "<style type=\"text/css\">\n"
                + "@media (prefers-color-scheme: dark) {\n"
                + backgrounds("") + colors("") + borders("")
                + "}\n"
                + backgrounds("[data-ogsb] ") + colors("[data-ogsc] ") + borders("[data-ogsb] ")
                + "</style>\n";
    }

    private static String backgrounds(String prefix) {
        StringBuilder out = new StringBuilder();
        for (String[] rule : DARK_BACKGROUNDS) {
            out.append(prefix).append('.').append(rule[0]).append("{background-color:").append(rule[1]).append("!important;}\n");
        }
        return out.toString();
    }

    private static String colors(String prefix) {
        StringBuilder out = new StringBuilder();
        for (String[] rule : DARK_COLORS) {
            out.append(prefix).append('.').append(rule[0]).append("{color:").append(rule[1]).append("!important;}\n");
        }
        return out.toString();
    }

    private static String borders(String prefix) {
        StringBuilder out = new StringBuilder();
        for (String[] rule : DARK_BORDERS) {
            out.append(prefix).append('.').append(rule[0]).append("{border-color:").append(rule[1]).append("!important;}\n");
        }
        return out.toString();
    }

    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text == null ? "" : text, StandardCharsets.UTF_8.name());
    }

    /** Escaped, without the spaces around it, its line breaks kept. */
    private static String escapeLines(String text) {
        return escape(text.strip().replace("\r\n", "\n")).replace("\n", "<br>\n");
    }
}
