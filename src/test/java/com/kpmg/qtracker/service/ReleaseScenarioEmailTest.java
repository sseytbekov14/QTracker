package com.kpmg.qtracker.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Release test pass, stage D: an e-mail carries texts people typed (comments, Control IDs, names); in the HTML
 * part they must stay text, not markup.
 */
class ReleaseScenarioEmailTest {

    @Test
    void typedTextInTheBody_isEscapedInTheHtmlEmail_lineBreaksStay() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        EmailNotificationChannel channel = new EmailNotificationChannel(sender, "qtracker@release.test", "", false);

        channel.send("person@release.test", "Control returned",
                "Comment: <a href=\"http://evil.test\">Open</a> <script>alert(1)</script> & more\nКонтроль ✅");

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        sent.getValue().writeTo(raw);
        String html = String.valueOf(sent.getValue().getContent());
        assertThat(html).doesNotContain("<a href").doesNotContain("<script>");
        assertThat(html).contains("&lt;a href=&quot;http://evil.test&quot;&gt;Open&lt;/a&gt;")
                .contains("&lt;script&gt;").contains("&amp; more").contains("<br>").contains("Контроль ✅");
        assertThat(raw.toString(StandardCharsets.UTF_8)).contains("charset=UTF-8");
    }

    /** The notification's link: a "Go to Control" button before the closing words, the address under it. */
    @Test
    void theLink_isAGoToControlButton_beforeTheClosingWords() {
        String html = EmailNotificationChannel.toHtml("Dear Anna,\n\nPlease click the \"Go to Control\" button below.\n"
                + "\nThank you,\n\nKind regards,\nSoQM Team", "https://qtracker.kz:8081/view-control/6");

        assertThat(html).contains("<a href=\"https://qtracker.kz:8081/view-control/6\"").contains(">Go to Control</a>")
                .contains("copy this address into the browser")
                .contains(">https://qtracker.kz:8081/view-control/6</a>");
        assertThat(html.indexOf(">Go to Control</a>")).isGreaterThan(html.indexOf("button below."))
                .isLessThan(html.indexOf("Thank you,"));
        assertThat(html).startsWith("Dear Anna,<br><br>Please click");
    }

    @Test
    void withoutClosingWords_theButtonIsAtTheEnd_andWithoutAnAbsoluteLinkThereIsNone() {
        String atTheEnd = EmailNotificationChannel.toHtml("Control ID: HR-1\nReturned by: Anna", "https://q.kz/view-control/1");
        assertThat(atTheEnd).startsWith("Control ID: HR-1<br>Returned by: Anna<table");

        for (String link : new String[]{null, "", "/view-control/1"}) {
            String html = EmailNotificationChannel.toHtml("Text\nKind regards,\nSoQM Team", link);
            assertThat(html).as(String.valueOf(link)).isEqualTo("Text<br>Kind regards,<br>SoQM Team")
                    .doesNotContain("Go to Control");
        }
    }

    @Test
    void aLinkWithQuotes_staysInsideTheAttribute() {
        String html = EmailNotificationChannel.toHtml("Text", "https://q.kz/view-control/1\"><script>x</script>");
        assertThat(html).doesNotContain("<script>").contains("&quot;&gt;&lt;script&gt;");
    }
}
