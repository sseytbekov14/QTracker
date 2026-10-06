package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessRight;
import com.kpmg.qtracker.enums.UserRole;
import com.kpmg.qtracker.enums.Visibility;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.AdminAuditTrail;
import com.kpmg.qtracker.service.UserService;
import com.kpmg.qtracker.util.RoleDisplayMapper;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminViewController {

    private static final Logger logger = LoggerFactory.getLogger(AdminViewController.class);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final UserService userService;
    private final AdminAuditTrail adminAuditTrail;

    /** One row of the users table: the user, their access as people see it, and whether it is the viewer. */
    public record UserRow(User user, AccessPolicy.Profile profile, String summary, String lastLogin, boolean self) {

        public String role() {
            return profile.role().name();
        }

        public String visibility() {
            return profile.visibility() != null ? profile.visibility().name() : "";
        }

        public String access() {
            return profile.access() != null ? profile.access().name() : "";
        }
    }

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

        // A failure to read the users leaves a clear message on the page instead of an error page
        List<UserRow> rows;
        boolean loadError = false;
        try {
            rows = userService.getAllUsers().stream()
                    .sorted(Comparator.comparing(User::getDisplayName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .map(user -> row(user, currentUser))
                    .toList();
        } catch (RuntimeException ex) {
            logger.error("Admin Panel: the users could not be loaded", ex);
            rows = List.of();
            loadError = true;
        }

        model.addAttribute("rows", rows);
        model.addAttribute("usersLoadError", loadError);
        model.addAttribute("roles", UserRole.values());
        model.addAttribute("visibilities", Visibility.values());
        model.addAttribute("accessRights", AccessRight.values());
        // What each role can do and the values it fixes, for the user dialog
        Map<String, List<String>> roleCanDo = new LinkedHashMap<>();
        Map<String, List<RoleDisplayMapper.FixedValue>> roleFixedValues = new LinkedHashMap<>();
        for (UserRole role : UserRole.values()) {
            roleCanDo.put(role.name(), RoleDisplayMapper.canDo(role));
            roleFixedValues.put(role.name(), RoleDisplayMapper.fixedValues(role));
        }
        model.addAttribute("roleCanDo", roleCanDo);
        model.addAttribute("roleFixedValues", roleFixedValues);
        model.addAttribute("accessHints", RoleDisplayMapper.hints());
        model.addAttribute("canManageUsers", AccessPolicy.canManageUsers(subject));
        AdminAuditTrail.Trail trail = adminAuditTrail.latest();
        model.addAttribute("auditTrail", trail.entries());
        model.addAttribute("auditCounts", trail.counts());
        model.addAttribute("auditGroups", AdminAuditTrail.Group.values());
        model.addAttribute("auditLimit", AdminAuditTrail.LIMIT);
        model.addAttribute("auditUserAccessCount", trail.counts().get(AdminAuditTrail.Group.USER_ACCESS));
        // Names for the "Target user" column, by e-mail
        Map<String, String> userNames = new HashMap<>();
        rows.forEach(row -> {
            if (row.user().getMail() != null) {
                userNames.put(row.user().getMail().toLowerCase(Locale.ROOT), row.user().getDisplayName());
            }
        });
        model.addAttribute("userNames", userNames);

        return "admin-users";
    }

    private static UserRow row(User user, User viewer) {
        AccessPolicy.Profile profile = AccessPolicy.Profile.of(user);
        return new UserRow(user, profile, RoleDisplayMapper.summary(profile),
                user.getLastLoginAt() != null ? user.getLastLoginAt().format(DATE_TIME) : "",
                Objects.equals(user.getId(), viewer.getId()));
    }
}
