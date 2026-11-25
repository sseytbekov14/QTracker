package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.EmailOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Reads the outbox (EmailOutbox); OutboxWriter inserts and EmailOutboxDispatcher updates it with SQL. */
@Repository
public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, Long> {

    List<EmailOutbox> findByControlIdOrderByIdAsc(Long controlId);

    /**
     * A person's e-mail changed (2026-11-25): the keys of their e-mails take the new address, so the planned e-mails
     * already made today (T2, T3, T4, T5) are not made again for it; a key the new address already has stays.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "UPDATE email_outbox SET dedupe_key = REPLACE(dedupe_key, :fromMail, :toMail)"
            + " WHERE recipient_user_id = :userId AND dedupe_key LIKE CONCAT('%', :fromMail, '%')"
            + " AND REPLACE(dedupe_key, :fromMail, :toMail) NOT IN (SELECT x.dedupe_key FROM email_outbox x)", nativeQuery = true)
    int readdressKeys(@org.springframework.data.repository.query.Param("userId") Long userId,
                      @org.springframework.data.repository.query.Param("fromMail") String fromMail,
                      @org.springframework.data.repository.query.Param("toMail") String toMail);

    /** ... and their e-mails not sent yet (waiting, or failed and tried again) go to the new address, as To or CC. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "UPDATE email_outbox SET recipient_email = :toMail"
            + " WHERE recipient_user_id = :userId AND status IN ('PENDING', 'FAILED', 'SENDING')", nativeQuery = true)
    int readdressWaiting(@org.springframework.data.repository.query.Param("userId") Long userId,
                         @org.springframework.data.repository.query.Param("toMail") String toMail);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "UPDATE email_outbox SET cc_emails = REPLACE(cc_emails, :fromMail, :toMail)"
            + " WHERE cc_emails LIKE CONCAT('%', :fromMail, '%') AND status IN ('PENDING', 'FAILED', 'SENDING')", nativeQuery = true)
    int readdressWaitingCopies(@org.springframework.data.repository.query.Param("fromMail") String fromMail,
                               @org.springframework.data.repository.query.Param("toMail") String toMail);

}
