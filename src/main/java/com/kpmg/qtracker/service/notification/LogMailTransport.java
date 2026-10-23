package com.kpmg.qtracker.service.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** notifications.email.provider=log: the whole e-mail (to, subject, importance, text) in the log, nothing sent. */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "notifications.email", name = "provider", havingValue = "log")
public class LogMailTransport implements MailTransport {

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void send(OutgoingMail mail) {
        log.info("E-mail (provider log)\nFrom: {}\nTo: {}\nSubject: {}\nImportance: {}\n\n{}", mail.from(), mail.to(),
                mail.subject(), mail.highImportance() ? "High" : "Normal", text(mail.html()));
    }

    /**
     * The text of the HTML of an e-mail: line breaks kept, the link "Go to Control: address", another link as its
     * text, the button on a line of its own.
     */
    public static String text(String html) {
        if (html == null) {
            return "";
        }
        String text = html.replace("\r\n", "\n")
                .replaceAll("<a href=\"([^\"]*)\"[^>]*>" + TemplateRenderer.BUTTON_LABEL + "</a>",
                        TemplateRenderer.BUTTON_LABEL + ": $1")
                .replaceAll("<br>\n?", "\n")
                .replaceAll("</p>\n?", "\n")
                .replaceAll("(?<!\n)<table", "\n<table")
                .replaceAll("</table>\n?", "\n")
                .replaceAll("<[^>]+>", "");
        return HtmlUtils.htmlUnescape(text).strip();
    }
}
