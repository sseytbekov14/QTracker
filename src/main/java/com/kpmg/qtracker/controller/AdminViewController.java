package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
import java.util.List;

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

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getRole());
        model.addAttribute("userEmail", currentUser.getMail());
        model.addAttribute("userRole", currentUser.getRole());
        model.addAttribute("users", users);
        model.addAttribute("allowedRoles", userService.getAllowedRoles());
        model.addAttribute("auditLogs", adminAuditService.getRecentLogs());

        return "admin-users";
    }

    @PostMapping("/users/{id}/update")
    public String updateUser(@PathVariable Long id,
                             @RequestParam String role,
                             @RequestParam(required = false) String secondaryRole,
                             @RequestParam(defaultValue = "false") boolean adminAccess,
                             @RequestParam(defaultValue = "false") boolean enabled,
                             HttpSession session,
                             RedirectAttributes redirectAttributes) {
        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return "redirect:/login";
        }
        if (!isAdmin(currentUser)) {
            return "redirect:/";
        }

        try {
            userService.updateUserAccess(id, role, secondaryRole, adminAccess, enabled, currentUser.getId());
            redirectAttributes.addFlashAttribute("successMessage", "User updated successfully");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/admin/users";
    }

    private boolean isAdmin(User user) {
        return userService.hasAdminAccess(user);
    }
}
