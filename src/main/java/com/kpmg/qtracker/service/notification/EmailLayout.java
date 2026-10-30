package com.kpmg.qtracker.service.notification;

import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;

/**
 * The one layout of every QTracker e-mail (2026-10-30); a template gives only its title (the subject) and its text.
 * Built for Outlook (its Word engine): tables only, every style inline, a fixed 600 px column inside an Outlook-only
 * table, the button drawn by VML in Outlook and by a styled link elsewhere. Parts: a hidden preheader (the line the
 * inbox shows after the subject), the #00338D header "QTracker", the white card with the title and the text, the
 * footer "automated message". The plain-text part of the e-mail has the same title, text and footer.
 */
final class EmailLayout {

    static final String BRAND = "#00338D";
    static final String FOOTER = "This is an automated message from QTracker. Please do not reply to this e-mail.";
    static final String LINK_HINT = "If the button does not work, open this link:";
    private static final String BACKGROUND = "#F2F4F7";
    private static final String FONT = "font-family:Arial,Helvetica,sans-serif;";
    private static final int PREHEADER_MAX = 140;

    private EmailLayout() {
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
                + "<title>" + safeTitle + "</title>\n"
                + "<!--[if mso]><xml><o:OfficeDocumentSettings><o:AllowPNG/><o:PixelsPerInch>96</o:PixelsPerInch>"
                + "</o:OfficeDocumentSettings></xml><![endif]-->\n"
                + "</head>\n"
                + "<body style=\"margin:0;padding:0;background-color:" + BACKGROUND + ";\">\n"
                + preheaderBlock(preheader)
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" bgcolor=\""
                + BACKGROUND + "\" style=\"background-color:" + BACKGROUND + ";\">\n"
                + "<tr><td align=\"center\" style=\"padding:24px 12px;\">\n"
                // Outlook ignores max-width: its own fixed table keeps the column at 600 px
                + "<!--[if mso]><table role=\"presentation\" width=\"600\" align=\"center\" cellspacing=\"0\""
                + " cellpadding=\"0\" border=\"0\"><tr><td><![endif]-->\n"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\""
                + " style=\"width:100%;max-width:600px;\">\n"
                + "<tr><td bgcolor=\"" + BRAND + "\" style=\"background-color:" + BRAND + ";padding:18px 32px;" + FONT
                + "font-size:20px;line-height:24px;font-weight:bold;color:#ffffff;\">QTracker</td></tr>\n"
                + "<tr><td bgcolor=\"#ffffff\" style=\"background-color:#ffffff;padding:28px 32px 32px 32px;"
                + "border:1px solid #E1E4EA;border-top:0;\">\n"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\">\n"
                + "<tr><td style=\"padding:0 0 20px 0;\"><h1 style=\"margin:0;" + FONT + "font-size:20px;line-height:26px;"
                + "font-weight:bold;color:" + BRAND + ";\">" + safeTitle + "</h1></td></tr>\n"
                + "<tr><td style=\"" + FONT + "font-size:14px;line-height:21px;color:#222222;\">" + content
                + "</td></tr>\n"
                + "</table>\n"
                + "</td></tr>\n"
                + "<tr><td align=\"center\" style=\"padding:16px 32px;" + FONT + "font-size:12px;line-height:18px;"
                + "color:#6B7280;\">" + FOOTER + "</td></tr>\n"
                + "</table>\n"
                + "<!--[if mso]></td></tr></table><![endif]-->\n"
                + "</td></tr>\n"
                + "</table>\n"
                + "</body>\n"
                + "</html>\n";
    }

    /**
     * The "Go to Control" button and, under it, the same link as text (for a client that blocks the button). In
     * Outlook a VML rounded rectangle, elsewhere a link styled as a button.
     */
    static String button(String link) {
        String href = escape(link);
        return "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\">\n"
                + "<tr><td style=\"padding:16px 0 8px 0;\">"
                + "<!--[if mso]><v:roundrect xmlns:v=\"urn:schemas-microsoft-com:vml\""
                + " xmlns:w=\"urn:schemas-microsoft-com:office:word\" href=\"" + href + "\""
                + " style=\"height:42px;v-text-anchor:middle;width:200px;\" arcsize=\"10%\" stroke=\"f\" fillcolor=\""
                + BRAND + "\"><w:anchorlock/><center style=\"color:#ffffff;" + FONT
                + "font-size:15px;font-weight:bold;\">" + TemplateRenderer.BUTTON_LABEL + "</center></v:roundrect>"
                + "<![endif]-->"
                + "<!--[if !mso]><!-- --><a href=\"" + href + "\" style=\"background-color:" + BRAND
                + ";border-radius:4px;color:#ffffff;display:inline-block;" + FONT + "font-size:15px;font-weight:bold;"
                + "line-height:42px;text-align:center;text-decoration:none;width:200px;-webkit-text-size-adjust:none;\">"
                + TemplateRenderer.BUTTON_LABEL + "</a><!--<![endif]-->"
                + "</td></tr>\n"
                + "<tr><td style=\"padding:0 0 16px 0;" + FONT + "font-size:12px;line-height:18px;color:#6B7280;\">"
                + LINK_HINT + "<br>\n<a href=\"" + href + "\" style=\"color:" + BRAND
                + ";text-decoration:underline;word-break:break-all;\">" + href + "</a></td></tr>\n"
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

    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text == null ? "" : text, StandardCharsets.UTF_8.name());
    }
}
