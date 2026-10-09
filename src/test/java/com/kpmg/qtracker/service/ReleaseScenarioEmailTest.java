package com.kpmg.qtracker.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Disabled;
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
    @Disabled("D-05 (S2): the HTML e-mail takes the typed text as markup; fixed in the next commit")
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
}
