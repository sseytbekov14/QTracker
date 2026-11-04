package com.kpmg.qtracker.service.notification;

/**
 * How an e-mail of the outbox leaves QTracker: notifications.email.provider = log (written to the application log)
 * or smtp (spring.mail.*). Another provider (e.g. Microsoft Graph) is one more implementation.
 */
public interface MailTransport {

    /**
     * One e-mail to one person: its HTML and its plain-text part (null for an e-mail queued before email_outbox.body_text,
     * V24).
     */
    record OutgoingMail(String from, String to, String subject, String html, String text, boolean highImportance) {

        /** The plain-text part, or the text of the HTML for an older e-mail without one. */
        public String plainText() {
            return text != null && !text.isBlank() ? text : LogMailTransport.text(html);
        }
    }

    /** The provider's name, stored in email_outbox.provider. */
    String name();

    /** Sends it or throws; the dispatcher records the failure and tries again. */
    void send(OutgoingMail mail) throws Exception;
}
