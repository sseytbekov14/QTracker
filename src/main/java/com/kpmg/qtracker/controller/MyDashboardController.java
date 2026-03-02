package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.DashboardChartDataDTO;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.DashboardService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard/my")
@RequiredArgsConstructor
public class MyDashboardController {
    private final DashboardService dashboardService;

    @GetMapping("/frequency")
    public ResponseEntity<DashboardChartDataDTO> getMyFrequency(HttpSession session) {
        User currentUser = getCurrentUser(session);
        if (currentUser == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(dashboardService.getMyFrequencyBreakdown(currentUser));
    }

    @GetMapping("/component")
    public ResponseEntity<DashboardChartDataDTO> getMyComponent(HttpSession session) {
        User currentUser = getCurrentUser(session);
        if (currentUser == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(dashboardService.getMyComponentBreakdown(currentUser));
    }

    @GetMapping("/overdue-trend")
    public ResponseEntity<DashboardChartDataDTO> getMyOverdueTrend(HttpSession session) {
        User currentUser = getCurrentUser(session);
        if (currentUser == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(dashboardService.getMyOverdueTrend(currentUser));
    }

    private User getCurrentUser(HttpSession session) {
        Object currentUser = session.getAttribute("currentUser");
        return currentUser instanceof User user ? user : null;
    }
}
