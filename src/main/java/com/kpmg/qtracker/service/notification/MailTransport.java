package com.kpmg.qtracker.service.notification;

/**
 * How an e-mail of the outbox leaves QTracker: notifications.email.provider = log (written to the application log)
 * or smtp (spring.mail.*). Another provider (e.g. Microsoft Graph) is one more implementation.
 */
public interface MailTransport {

    /** One e-mail to one person. */
    record OutgoingMail(String from, String to, String subject, String html, boolean highImportance) {
    }

    /** The provider's name, stored in email_outbox.provider. */
    String name();

    /** Sends it or throws; the dispatcher records the failure and tries again. */
    void send(OutgoingMail mail) throws Exception;
}
