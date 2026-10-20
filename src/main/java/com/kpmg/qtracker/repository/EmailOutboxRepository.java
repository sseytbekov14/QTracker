package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.EmailOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Reads the outbox (EmailOutbox); OutboxWriter inserts and EmailOutboxDispatcher updates it with SQL. */
@Repository
public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, Long> {

    List<EmailOutbox> findByControlIdOrderByIdAsc(Long controlId);

    List<EmailOutbox> findByStatusOrderByIdAsc(String status);
}
