package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.NotificationTemplateText;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationTemplateTextRepository extends JpaRepository<NotificationTemplateText, String> {
}
