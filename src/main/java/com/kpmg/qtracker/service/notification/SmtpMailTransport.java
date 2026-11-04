package com.kpmg.qtracker.service.notification;

import jakarta.activation.DataHandler;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * notifications.email.provider=smtp (the default): the SMTP relay of spring.mail.*. High importance (T4, T5) is sent as
 * the headers Importance: high and X-Priority: 1, as Outlook reads them. Every e-mail is automatic, so each says
 * Auto-Submitted: auto-generated (RFC 3834: no out-of-office replies to it). The e-mail is multipart/alternative: the
 * plain-text part first, the HTML last (the one a client shows when it can). An HTML that names images of the layout
 * (cid:..., EmailLayout.INLINE_IMAGES) goes in multipart/related with them; an e-mail queued before them is sent as it
 * was.
 */
@Component
@ConditionalOnProperty(prefix = "notifications.email", name = "provider", havingValue = "smtp", matchIfMissing = true)
public class SmtpMailTransport implements MailTransport {

    private final ObjectProvider<JavaMailSender> mailSender;

    public SmtpMailTransport(ObjectProvider<JavaMailSender> mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public String name() {
        return "smtp";
    }

    @Override
    public void send(OutgoingMail mail) throws Exception {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("no SMTP server (spring.mail.host is not set)");
        }
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
        if (mail.from() != null && !mail.from().isBlank()) {
            helper.setFrom(mail.from());
        }
        helper.setTo(mail.to());
        helper.setSubject(mail.subject());
        MimeBodyPart text = new MimeBodyPart();
        text.setText(mail.plainText(), StandardCharsets.UTF_8.name());
        MimeBodyPart html = new MimeBodyPart();
        html.setText(mail.html(), StandardCharsets.UTF_8.name(), "html");
        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(text);
        alternative.addBodyPart(html);
        List<String> images = new TreeMap<>(EmailLayout.INLINE_IMAGES).keySet().stream()
                .filter(cid -> mail.html() != null && mail.html().contains("cid:" + cid)).toList();
        if (images.isEmpty()) {
            message.setContent(alternative);
        } else {
            // The images go with the HTML: multipart/related, the alternative first, the images after it
            MimeBodyPart body = new MimeBodyPart();
            body.setContent(alternative);
            MimeMultipart related = new MimeMultipart("related");
            related.addBodyPart(body);
            for (String cid : images) {
                related.addBodyPart(inlineImage(cid));
            }
            message.setContent(related);
        }
        message.setHeader("Auto-Submitted", "auto-generated");
        if (mail.highImportance()) {
            message.setHeader("Importance", "high");
            message.setHeader("X-Priority", "1");
        }
        sender.send(message);
    }

    /** An image of the layout as an inline part, Content-ID its cid. */
    static MimeBodyPart inlineImage(String cid) throws Exception {
        String resource = EmailLayout.INLINE_IMAGES.get(cid);
        MimeBodyPart image = new MimeBodyPart();
        image.setDataHandler(new DataHandler(new ByteArrayDataSource(BYTES.computeIfAbsent(resource,
                SmtpMailTransport::read), "image/png")));
        image.setContentID("<" + cid + ">");
        image.setDisposition(Part.INLINE);
        image.setFileName(cid + ".png");
        return image;
    }

    /** The bytes of each image, read once. */
    private static final Map<String, byte[]> BYTES = new ConcurrentHashMap<>();

    private static byte[] read(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("no " + resource + " in the application", e);
        }
    }
}
