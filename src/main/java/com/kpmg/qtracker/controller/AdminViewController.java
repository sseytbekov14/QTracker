package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.UserService;
import com.kpmg.qtracker.util.RoleDisplayMapper;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminViewController {

    private final UserService userService;
    private final AdminAuditService adminAuditService;

    @GetMapping("/users")
    public String users(Model model, HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return "redirect:/login";
        }
        if (!isAdmin(currentUser)) {
            return "redirect:/";
        }

        List<User> users = userService.getAllUsers().stream()
                .sorted(Comparator.comparing(User::getDisplayName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();

        model.addAttribute("users", users);
        model.addAttribute("allowedRoles", userService.getAllowedRoles());
        model.addAttribute("allowedSecondaryRoles", userService.getAllowedSecondaryRoles());
        model.addAttribute("noSecondaryRole", UserService.NO_SECONDARY_ROLE);
        model.addAttribute("roleLabels", roleLabels());
        model.addAttribute("auditLogs", adminAuditService.getRecentLogs());

        return "admin-users";
    }

    /** Display names for the role codes the selects offer ("SOQM_TEAM" -> "SoQM Team"). */
    private Map<String, String> roleLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        userService.getAllowedRoles().forEach(role -> labels.put(role, RoleDisplayMapper.display(role)));
        userService.getAllowedSecondaryRoles().forEach(role -> labels.put(role, RoleDisplayMapper.display(role)));
        return labels;
    }

    private boolean isAdmin(User user) {
        return userService.hasAdminAccess(user);
    }
}
