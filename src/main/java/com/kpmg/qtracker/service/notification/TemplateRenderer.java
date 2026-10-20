package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.entity.NotificationTemplateText;
import com.kpmg.qtracker.repository.NotificationTemplateTextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts the values into a notification's text, read from notification_template at every call (a text changed there is
 * used at once). Every placeholder must get a value: otherwise nothing is made (TP-2, FAILED_RENDER).
 * <p>The link (the rule of 2026-10-20): {LINK} stands on a line of its own. In the e-mail (HTML: the text escaped, its
 * line breaks kept) it is a "Go to Control" button, a link to the control whose address is not shown. In the in-app
 * notification its line is left out: no address and no word of a link (the page has its own "Go to Control" button).
 */
@Component
public class TemplateRenderer {

    public static final String LINK = "LINK";
    public static final String BUTTON_LABEL = "Go to Control";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Z_]+)}");
    private static final Pattern PLACEHOLDER_LINK = Pattern.compile("\\{" + LINK + "}");
    private static final Pattern THREE_OR_MORE_BREAKS = Pattern.compile("\\n(?:[ \\t]*\\n){2,}");

    private final NotificationTemplateTextRepository templates;

    public TemplateRenderer(NotificationTemplateTextRepository templates) {
        this.templates = templates;
    }

    /** The texts of one notification: the subject, the plain text (in-app) and the HTML (e-mail). */
    public record Rendered(String subject, String text, String html) {
    }

    /** Rendered, or why not (the error of FAILED_RENDER). */
    public record Result(Rendered rendered, String error, String rawSubject) {

        public boolean ok() {
            return rendered != null;
        }
    }

    public Result render(Template template, Map<String, String> values) {
        Optional<NotificationTemplateText> stored = templates.findById(template.name());
        if (stored.isEmpty()) {
            return new Result(null, "no text " + template.name() + " in notification_template", template.defaultSubject());
        }
        String subject = stored.get().getSubject();
        String body = stored.get().getBody() == null ? "" : stored.get().getBody().replace("\r\n", "\n");
        Set<String> missing = new LinkedHashSet<>();
        missing.addAll(missing(subject, values));
        missing.addAll(missing(body, values));
        if (!missing.isEmpty()) {
            return new Result(null, "no value for " + String.join(", ", missing) + " in " + template.name(), subject);
        }
        String html = html(body, values);
        if (!PLACEHOLDER_LINK.matcher(body).find() && values.get(LINK) != null) {
            html = withButton(html, values.get(LINK));
        }
        return new Result(new Rendered(substitute(subject, values, false).replaceAll("\\s+", " ").trim(),
                text(body, values),
                "<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#222222\">" + html + "</div>"), null, subject);
    }

    private static Set<String> missing(String text, Map<String, String> values) {
        Set<String> missing = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            if (values.get(matcher.group(1)) == null) {
                missing.add("{" + matcher.group(1) + "}");
            }
        }
        return missing;
    }

    /** The in-app text: the lines of {LINK} left out (and {LINK} anywhere else), no empty line at the end. */
    private static String text(String body, Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (isLinkLine(line)) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(substitute(PLACEHOLDER_LINK.matcher(line).replaceAll(""), values, false).stripTrailing());
        }
        return THREE_OR_MORE_BREAKS.matcher(out).replaceAll("\n\n").strip();
    }

    /** The e-mail: each line escaped, a line of {LINK} the button, {LINK} within a line a bold link. */
    private static String html(String body, Map<String, String> values) {
        String[] lines = body.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (isLinkLine(lines[i])) {
                // A table is a block of its own: no line break after it
                out.append(button(values.get(LINK)));
                continue;
            }
            out.append(substitute(lines[i], values, true));
            if (i < lines.length - 1) {
                out.append("<br>\n");
            }
        }
        return out.toString();
    }

    private static boolean isLinkLine(String line) {
        return line.strip().equals("{" + LINK + "}");
    }

    private static String substitute(String text, Map<String, String> values, boolean html) {
        String source = text == null ? "" : text;
        StringBuilder out = new StringBuilder();
        Matcher matcher = PLACEHOLDER.matcher(source);
        int at = 0;
        while (matcher.find()) {
            out.append(html ? escape(source.substring(at, matcher.start())) : source.substring(at, matcher.start()));
            String value = values.get(matcher.group(1));
            if (!html) {
                out.append(value);
            } else if (LINK.equals(matcher.group(1))) {
                out.append(anchor(value));
            } else {
                out.append(escape(value));
            }
            at = matcher.end();
        }
        out.append(html ? escape(source.substring(at)) : source.substring(at));
        return out.toString();
    }

    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text, StandardCharsets.UTF_8.name()).replace("\n", "<br>\n");
    }

    /** {LINK} within a line: "Go to Control" in bold, the address not shown. */
    private static String anchor(String link) {
        return "<a href=\"" + HtmlUtils.htmlEscape(link, StandardCharsets.UTF_8.name())
                + "\" style=\"color:#00338D;font-weight:bold\">" + BUTTON_LABEL + "</a>";
    }

    /** The "Go to Control" button: a table cell, as Outlook draws it, holding the link; the address not shown. */
    static String button(String link) {
        String href = HtmlUtils.htmlEscape(link, StandardCharsets.UTF_8.name());
        return "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:12px 0\">"
                + "<tr><td style=\"background:#00338D;border-radius:4px\">"
                + "<a href=\"" + href + "\" style=\"display:inline-block;padding:10px 22px;font-family:Arial,sans-serif;"
                + "font-size:14px;font-weight:bold;color:#ffffff;text-decoration:none\">" + BUTTON_LABEL + "</a>"
                + "</td></tr></table>\n";
    }

    /** A text without {LINK}: the button before "Thank you" / "Kind regards", at the end without them. */
    private static String withButton(String html, String link) {
        int closing = closingWordsAt(html);
        String before = closing < 0 ? html : html.substring(0, closing);
        String after = closing < 0 ? "" : html.substring(closing);
        return stripTrailingBreaks(before) + button(link) + after;
    }

    private static int closingWordsAt(String html) {
        int at = 0;
        for (String line : html.split("\n", -1)) {
            String start = line.strip();
            if (start.startsWith("Thank you") || start.startsWith("Kind regards")) {
                return at;
            }
            at += line.length() + 1;
        }
        return -1;
    }

    private static String stripTrailingBreaks(String html) {
        String out = html.stripTrailing();
        while (out.endsWith("<br>")) {
            out = out.substring(0, out.length() - 4).stripTrailing();
        }
        return out;
    }
}
