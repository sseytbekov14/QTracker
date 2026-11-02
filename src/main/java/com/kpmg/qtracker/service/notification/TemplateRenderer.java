package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.entity.NotificationTemplateText;
import com.kpmg.qtracker.repository.NotificationTemplateTextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
 * line breaks kept) it is the "Go to Control" button, the address not shown; in its plain-text part the line
 * "Go to Control: address". In the in-app notification its line is left out: no address and no word of
 * a link (the page has its own "Go to Control" button).
 * <p>The e-mail is {@link EmailLayout}: the name of the step or event as the card's title, the text in the card, the
 * first line after the greeting as the preheader. Its subject is built from that name, {@link #subject}.
 */
@Component
public class TemplateRenderer {

    public static final String LINK = "LINK";
    public static final String BUTTON_LABEL = "Go to Control";
    private static final String SUBJECT_TAG = "[QTracker]";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Z_]+)}");
    private static final Pattern PLACEHOLDER_LINK = Pattern.compile("\\{" + LINK + "}");
    private static final Pattern THREE_OR_MORE_BREAKS = Pattern.compile("\\n(?:[ \\t]*\\n){2,}");

    private final NotificationTemplateTextRepository templates;

    public TemplateRenderer(NotificationTemplateTextRepository templates) {
        this.templates = templates;
    }

    /**
     * The texts of one notification: the e-mail's subject, the title (of the e-mail and the in-app notification), the
     * in-app text, the HTML of the e-mail and its plain-text part (multipart/alternative).
     */
    public record Rendered(String subject, String title, String text, String html, String mailText) {
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
        String title = substitute(subject, values, false).replaceAll("\\s+", " ").trim();
        String inApp = text(body, values);
        return new Result(new Rendered(subject(template, values.get(NotificationValues.CONTROL_ID), title), title, inApp,
                EmailLayout.page(title, preheader(inApp, title), html(body, values)),
                EmailLayout.plainText(title, mailText(body, values))), null, subject);
    }

    /** The e-mail's subject: "[QTracker] Prefix: Control ID: step or event"; no prefix for one that only informs. */
    static String subject(Template template, String controlId, String title) {
        StringBuilder out = new StringBuilder(SUBJECT_TAG).append(' ');
        if (template.label() != Template.Label.NONE) {
            out.append(template.label().text()).append(": ");
        }
        if (controlId != null && !controlId.isBlank()) {
            out.append(controlId.strip()).append(": ");
        }
        return out.append(title).toString();
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

    /** The first line after the greeting ("Dear ..."), or the title when there is none. */
    private static String preheader(String inApp, String title) {
        for (String line : inApp.split("\n")) {
            if (!line.isBlank() && !line.startsWith("Dear ")) {
                return line.strip();
            }
        }
        return title;
    }

    /** The e-mail's plain-text part: a line of {LINK} "Go to Control: address", within a line "Go to Control (address)". */
    private static String mailText(String body, Map<String, String> values) {
        Map<String, String> inline = new HashMap<>(values);
        inline.put(LINK, BUTTON_LABEL + " (" + values.get(LINK) + ")");
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(isLinkLine(line) ? BUTTON_LABEL + ": " + values.get(LINK)
                    : substitute(line, inline, false).stripTrailing());
        }
        return THREE_OR_MORE_BREAKS.matcher(out).replaceAll("\n\n").strip();
    }

    /** The card's text: each line escaped, a line of {LINK} the button, {LINK} within a line a bold link. */
    private static String html(String body, Map<String, String> values) {
        String[] lines = body.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (isLinkLine(lines[i])) {
                // A table is a block with its own space around it: no line break and no empty line after it
                out.append(EmailLayout.button(values.get(LINK)));
                if (i + 1 < lines.length && lines[i + 1].isBlank()) {
                    i++;
                }
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
                + "\" style=\"color:" + EmailLayout.BRAND + ";font-weight:bold\">" + BUTTON_LABEL + "</a>";
    }
}
