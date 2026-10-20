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
 * <p>The e-mail is HTML: the text escaped, its line breaks kept, {LINK} a clickable address. A text without {LINK}
 * (T12) gets the "Go to Control" button before its closing words. The in-app notification keeps the plain text.
 */
@Component
public class TemplateRenderer {

    public static final String LINK = "LINK";
    public static final String BUTTON_LABEL = "Go to Control";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Z_]+)}");
    private static final Pattern PLACEHOLDER_LINK = Pattern.compile("\\{" + LINK + "}");

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
        String body = stored.get().getBody();
        Set<String> missing = new LinkedHashSet<>();
        missing.addAll(missing(subject, values));
        missing.addAll(missing(body, values));
        if (!missing.isEmpty()) {
            return new Result(null, "no value for " + String.join(", ", missing) + " in " + template.name(), subject);
        }
        String text = substitute(body, values, false);
        String html = substitute(body, values, true);
        if (!PLACEHOLDER_LINK.matcher(body).find() && values.get(LINK) != null) {
            html = withButton(html, values.get(LINK));
        }
        return new Result(new Rendered(substitute(subject, values, false).replaceAll("\\s+", " ").trim(), text,
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

    private static String substitute(String text, Map<String, String> values, boolean html) {
        String source = text == null ? "" : text.replace("\r\n", "\n");
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

    private static String anchor(String link) {
        String href = HtmlUtils.htmlEscape(link, StandardCharsets.UTF_8.name());
        return "<a href=\"" + href + "\" style=\"color:#00338D\">" + href + "</a>";
    }

    /** The button before "Thank you" / "Kind regards", at the end without them; the address under it as well. */
    private static String withButton(String html, String link) {
        int closing = closingWordsAt(html);
        String before = closing < 0 ? html : html.substring(0, closing);
        String after = closing < 0 ? "" : html.substring(closing);
        String href = HtmlUtils.htmlEscape(link, StandardCharsets.UTF_8.name());
        String button = "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:16px 0 8px\">"
                + "<tr><td style=\"background:#00338D;border-radius:4px\">"
                + "<a href=\"" + href + "\" style=\"display:inline-block;padding:10px 22px;font-family:Arial,sans-serif;"
                + "font-size:14px;font-weight:bold;color:#ffffff;text-decoration:none\">" + BUTTON_LABEL + "</a></td></tr></table>"
                + "<p style=\"margin:0 0 16px;font-family:Arial,sans-serif;font-size:12px;color:#666666\">"
                + "If the button does not open, copy this address into the browser:<br>\n"
                + "<a href=\"" + href + "\" style=\"color:#00338D\">" + href + "</a></p>\n";
        return stripTrailingBreaks(before) + button + after;
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
