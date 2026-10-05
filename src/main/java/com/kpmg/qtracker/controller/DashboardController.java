package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.DashboardChartDataDTO;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.DashboardService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/dashboard/admin")
@RequiredArgsConstructor
public class DashboardController {
    private final DashboardService dashboardService;

    @GetMapping("/status")
    public ResponseEntity<DashboardChartDataDTO> getStatus(HttpSession session) {
        if (!seesOrganisationCharts(session)) {
            return unauthorizedOrForbidden(session);
        }
        return ResponseEntity.ok(dashboardService.getStatusBreakdown());
    }

    @GetMapping("/component-breakdown")
    public ResponseEntity<DashboardChartDataDTO> getComponentBreakdown(HttpSession session) {
        if (!seesOrganisationCharts(session)) {
            return unauthorizedOrForbidden(session);
        }
        return ResponseEntity.ok(dashboardService.getComponentBreakdown());
    }

    @GetMapping("/frequency")
    public ResponseEntity<DashboardChartDataDTO> getFrequency(HttpSession session) {
        if (!seesOrganisationCharts(session)) {
            return unauthorizedOrForbidden(session);
        }
        return ResponseEntity.ok(dashboardService.getFrequencyBreakdown());
    }

    @GetMapping("/overdue-trend")
    public ResponseEntity<DashboardChartDataDTO> getOverdueTrend(HttpSession session) {
        if (!seesOrganisationCharts(session)) {
            return unauthorizedOrForbidden(session);
        }
        return ResponseEntity.ok(dashboardService.getOverdueTrend());
    }

    private boolean seesOrganisationCharts(HttpSession session) {
        return AccessPolicy.seesOrganisationCharts(AccessPolicy.Subject.of((User) session.getAttribute("currentUser")));
    }

    private ResponseEntity<DashboardChartDataDTO> unauthorizedOrForbidden(HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.status(403).build();
    }
}
