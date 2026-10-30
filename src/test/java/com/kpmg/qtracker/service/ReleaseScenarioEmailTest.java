package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.NotificationTemplateText;
import com.kpmg.qtracker.repository.NotificationTemplateTextRepository;
import com.kpmg.qtracker.service.notification.MailTransport;
import com.kpmg.qtracker.service.notification.SmtpMailTransport;
import com.kpmg.qtracker.service.notification.Template;
import com.kpmg.qtracker.service.notification.TemplateRenderer;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Release test pass, stage D: an e-mail carries texts people typed (Control IDs, descriptions); in the HTML they must
 * stay text, not markup, and the link must stay inside its attribute.
 */
class ReleaseScenarioEmailTest {

    private final NotificationTemplateTextRepository templates = mock(NotificationTemplateTextRepository.class);
    private final TemplateRenderer renderer = new TemplateRenderer(templates);

    ReleaseScenarioEmailTest() {
        when(templates.findById(anyString())).thenAnswer(call -> {
            Template template = Template.valueOf(call.getArgument(0));
            NotificationTemplateText text = new NotificationTemplateText();
            text.setSubject(template.defaultSubject());
            text.setBody(template.defaultBody());
            return Optional.of(text);
        });
    }

    private static Map<String, String> values(String name, String link) {
        Map<String, String> values = new HashMap<>();
        values.put("CONTROL_ID", "HR-1");
        values.put("CONTROL_NAME", name);
        values.put("OCCURRENCE_DATE", "05-11-2026");
        values.put("DEADLINE", "19-11-2026");
        values.put("LINK", link);
        values.put("NAME", "Anna");
        values.put("ROLES", "Facilitator");
        return values;
    }

    @Test
    void typedTextInTheBody_isEscapedInTheHtmlEmail_lineBreaksStay() throws Exception {
        TemplateRenderer.Rendered rendered = renderer.render(Template.T1,
                values("<a href=\"http://evil.test\">Open</a> <script>alert(1)</script> & more Контроль ✅",
                        "https://qtracker.kz/view-control/6")).rendered();
        String html = rendered.html();

        assertThat(html).doesNotContain("<a href=\"http://evil.test\"").doesNotContain("<script>");
        assertThat(html).contains("&lt;a href=&quot;http://evil.test&quot;&gt;Open&lt;/a&gt;")
                .contains("&lt;script&gt;").contains("&amp; more").contains("<br>").contains("Контроль ✅");
        // The plain-text part is text: the typed text as it is
        assertThat(rendered.mailText()).contains("<a href=\"http://evil.test\">Open</a> <script>alert(1)</script> & more");

        // Sent as UTF-8, both parts
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        new SmtpMailTransport(new StaticListableBeanFactory(Map.of("sender", sender)).getBeanProvider(JavaMailSender.class))
                .send(new MailTransport.OutgoingMail("qtracker@release.test", "person@release.test", "Subject", html,
                        rendered.mailText(), false));
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        sent.getValue().writeTo(raw);
        assertThat(raw.toString(StandardCharsets.UTF_8)).contains("charset=UTF-8");
        MimeMultipart parts = (MimeMultipart) sent.getValue().getContent();
        assertThat(String.valueOf(parts.getBodyPart(0).getContent())).contains("Контроль ✅");
        assertThat(String.valueOf(parts.getBodyPart(1).getContent())).contains("Контроль ✅");
    }

    /** The link: a "Go to Control" button before the closing words, the address as a text link under it. */
    @Test
    void theLink_isAGoToControlButton_theAddressUnderIt() {
        String link = "https://qtracker.kz:8081/view-control/6";
        TemplateRenderer.Rendered rendered = renderer.render(Template.T12, values("Payroll", link)).rendered();
        String html = rendered.html();

        assertThat(html).containsPattern("<a href=\"https://qtracker\\.kz:8081/view-control/6\"[^>]*>Go to Control</a>")
                .contains("If the button does not work, open this link:<br>\n<a href=\"" + link + "\"")
                .contains(">" + link + "</a>");
        assertThat(html.indexOf(">Go to Control</a>")).isLessThan(html.indexOf(">" + link + "</a>"));
        assertThat(html.indexOf(">" + link + "</a>")).isLessThan(html.indexOf("Kind regards,"));
        assertThat(rendered.mailText()).contains("\nGo to Control: " + link + "\n\nKind regards,");
    }

    @Test
    void aLinkWithQuotes_staysInsideTheAttribute() {
        String html = renderer.render(Template.T3, values("Payroll", "https://q.kz/view-control/1\"><script>x</script>"))
                .rendered().html();

        assertThat(html).doesNotContain("<script>").contains("&quot;&gt;&lt;script&gt;");
    }
}
