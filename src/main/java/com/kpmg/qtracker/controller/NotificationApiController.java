package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationApiController {
    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<List<Notification>> getNotifications(@RequestParam Long userId) {
        List<Notification> notifications = notificationService.getUserNotifications(userId);
        return ResponseEntity.ok(notifications);
    }
}
