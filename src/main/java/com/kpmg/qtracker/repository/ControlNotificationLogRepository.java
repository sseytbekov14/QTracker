package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.ControlNotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface ControlNotificationLogRepository extends JpaRepository<ControlNotificationLog, Long> {
    boolean existsByControlIdAndNotificationCodeAndScheduledDate(Long controlId,
                                                                  String notificationCode,
                                                                  LocalDate scheduledDate);
}
