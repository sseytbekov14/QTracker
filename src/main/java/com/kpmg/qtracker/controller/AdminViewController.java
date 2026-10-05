package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.AdminAuditTrail;
import com.kpmg.qtracker.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminViewController {

    private final UserService userService;
    private final AdminAuditTrail adminAuditTrail;

    @GetMapping("/users")
    public String users(Model model, HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return "redirect:/login";
        }
        AccessPolicy.Subject subject = AccessPolicy.Subject.of(currentUser);
        if (!AccessPolicy.canOpenAdminPanel(subject)) {
            return "redirect:/";
        }

        List<User> users = userService.getAllUsers().stream()
                .sorted(Comparator.comparing(User::getDisplayName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();

        model.addAttribute("users", users);
        model.addAttribute("accessLevels", AccessLevel.values());
        model.addAttribute("accessScopes", AccessScope.values());
        // A read-only admin sees the users and the audit trail but changes nothing
        model.addAttribute("canManageUsers", AccessPolicy.canManageUsers(subject));
        AdminAuditTrail.Trail trail = adminAuditTrail.latest();
        model.addAttribute("auditTrail", trail.entries());
        model.addAttribute("auditCounts", trail.counts());
        model.addAttribute("auditGroups", AdminAuditTrail.Group.values());
        model.addAttribute("auditLimit", AdminAuditTrail.LIMIT);
        model.addAttribute("auditUserAccessCount", trail.counts().get(AdminAuditTrail.Group.USER_ACCESS));
        // Names for the "Target user" column, by e-mail
        Map<String, String> userNames = new HashMap<>();
        users.forEach(user -> {
            if (user.getMail() != null) {
                userNames.put(user.getMail().toLowerCase(Locale.ROOT), user.getDisplayName());
            }
        });
        model.addAttribute("userNames", userNames);

        return "admin-users";
    }
}
