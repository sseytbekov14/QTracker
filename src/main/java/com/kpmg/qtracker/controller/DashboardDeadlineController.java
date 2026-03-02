package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.DashboardCalendarEventDTO;
import com.kpmg.qtracker.dto.DashboardDeadlineCountdownItemDTO;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.DashboardService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardDeadlineController {
    private final DashboardService dashboardService;

    @GetMapping("/deadline-countdown")
    public ResponseEntity<List<DashboardDeadlineCountdownItemDTO>> getDeadlineCountdown(
            @RequestParam(defaultValue = "3") int days,
            @RequestParam(defaultValue = "10") int limit,
            HttpSession session) {
        User currentUser = getCurrentUser(session);
        if (currentUser == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(dashboardService.getDeadlineCountdown(currentUser, days, limit));
    }

    @GetMapping("/deadline-calendar")
    public ResponseEntity<List<DashboardCalendarEventDTO>> getDeadlineCalendar(
            @RequestParam String start,
            @RequestParam String end,
            HttpSession session) {
        User currentUser = getCurrentUser(session);
        if (currentUser == null) {
            return ResponseEntity.status(401).build();
        }
        try {
            LocalDate startDate = parseToLocalDate(start);
            LocalDate endDate = parseToLocalDate(end);
            return ResponseEntity.ok(dashboardService.getDeadlineCalendar(currentUser, startDate, endDate));
        } catch (DateTimeParseException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    private User getCurrentUser(HttpSession session) {
        Object currentUser = session.getAttribute("currentUser");
        return currentUser instanceof User user ? user : null;
    }

    private LocalDate parseToLocalDate(String value) {
        if (value == null || value.isBlank()) {
            throw new DateTimeParseException("Blank date value", String.valueOf(value), 0);
        }
        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value).toLocalDate();
        } catch (DateTimeParseException ignored) {
        }
        return LocalDate.parse(value);
    }
}
