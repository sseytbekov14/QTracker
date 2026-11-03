package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.entity.NotificationTemplateText;
import com.kpmg.qtracker.repository.NotificationTemplateTextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
 * first line after the greeting as the preheader. Its subject is that name alone (2026-11-03). Above the
 * button stand the details of the control ({@link EmailLayout#details}) and the comment of a return
 * ({@link EmailLayout#quote}), from the values given.
 */
@Component
public class TemplateRenderer {

    public static final String LINK = "LINK";
    public static final String BUTTON_LABEL = "Go to Control";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Z_]+)}");
    private static final Pattern PLACEHOLDER_LINK = Pattern.compile("\\{" + LINK + "}");
    private static final Pattern THREE_OR_MORE_BREAKS = Pattern.compile("\\n(?:[ \\t]*\\n){2,}");
    private static final Set<String> LINES_OF_THE_DETAILS = Set.of("Your role: {ROLES}", "Deadline: {DEADLINE}");

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
        return new Result(new Rendered(title, title, inApp,
                EmailLayout.page(title, preheader(inApp, title), template.label(), html(body, values)),
                EmailLayout.plainText(title, mailText(body, values))), null, subject);
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

    /**
     * The e-mail's plain-text part: the details above a line of {LINK}, which is "Go to Control: address"; within a line
     * "Go to Control (address)".
     */
    private static String mailText(String body, Map<String, String> values) {
        Map<String, String> inline = new HashMap<>(values);
        inline.put(LINK, BUTTON_LABEL + " (" + values.get(LINK) + ")");
        String details = plainBlocks(values);
        boolean placed = false;
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (isDetailsLine(line)) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            if (isLinkLine(line)) {
                if (!placed && !details.isEmpty()) {
                    out.append('\n').append(details).append("\n\n");
                }
                placed = true;
                out.append(BUTTON_LABEL).append(": ").append(values.get(LINK));
            } else {
                out.append(substitute(line, inline, false).stripTrailing());
            }
        }
        if (!placed && !details.isEmpty()) {
            out.append("\n\n").append(details);
        }
        return THREE_OR_MORE_BREAKS.matcher(out).replaceAll("\n\n").strip();
    }

    /**
     * The card's text: each line escaped, the details and the button at the line of {LINK} (the details at the end of a
     * text without one), {LINK} within a line a bold link.
     */
    private static String html(String body, Map<String, String> values) {
        String[] lines = body.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean placed = false;
        for (int i = 0; i < lines.length; i++) {
            if (isDetailsLine(lines[i])) {
                continue;
            }
            if (isLinkLine(lines[i])) {
                if (!placed) {
                    out.append(blocks(values));
                    placed = true;
                }
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
        if (!placed) {
            out.append(blocks(values));
        }
        return out.toString();
    }

    /** What stands above the button: the details, then the comment of a return. */
    private static String blocks(Map<String, String> values) {
        return EmailLayout.details(details(values))
                + EmailLayout.quote(values.get(NotificationValues.FROM), values.get(NotificationValues.COMMENT));
    }

    private static String plainBlocks(Map<String, String> values) {
        String details = EmailLayout.plainDetails(details(values));
        String quote = EmailLayout.plainQuote(values.get(NotificationValues.FROM), values.get(NotificationValues.COMMENT));
        return details.isEmpty() || quote.isEmpty() ? details + quote : details + "\n\n" + quote;
    }

    /** The details block of every e-mail; each template gets the lines it has values for. */
    private static List<EmailLayout.Row> details(Map<String, String> values) {
        return List.of(new EmailLayout.Row("Control", values.get(NotificationValues.CONTROL_ID)),
                new EmailLayout.Row("Description", values.get(NotificationValues.CONTROL_NAME)),
                new EmailLayout.Row("Your role", values.get(NotificationValues.ROLES)),
                values.get(NotificationValues.DUE) != null
                        ? new EmailLayout.Row("Due", values.get(NotificationValues.DUE))
                        : new EmailLayout.Row("Deadline", values.get(NotificationValues.DEADLINE)),
                new EmailLayout.Row("From", values.get(NotificationValues.FROM)));
    }

    private static boolean isLinkLine(String line) {
        return line.strip().equals("{" + LINK + "}");
    }

    /** A line of a text that the e-mail shows in its details block; the in-app notification, which has none, keeps it. */
    private static boolean isDetailsLine(String line) {
        return LINES_OF_THE_DETAILS.contains(line.strip());
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
