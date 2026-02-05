package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.NotificationRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationReadRepository extends JpaRepository<NotificationRead, Long> {
    
    // Check if specific notification has been read by user
    Optional<NotificationRead> findByUserIdAndNotificationId(Long userId, String notificationId);
    
    // Get all notification IDs read by user
    @Query("SELECT nr.notificationId FROM NotificationRead nr WHERE nr.userId = :userId")
    List<String> findReadNotificationIdsByUserId(@Param("userId") Long userId);
    
    // Check if notification exists for user
    boolean existsByUserIdAndNotificationId(Long userId, String notificationId);
}
