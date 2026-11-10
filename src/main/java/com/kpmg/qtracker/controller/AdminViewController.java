package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.security.AuthMode;
import com.kpmg.qtracker.enums.AccessRight;
import com.kpmg.qtracker.enums.UserRole;
import com.kpmg.qtracker.enums.Visibility;
import com.kpmg.qtracker.service.AccessPolicy;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminViewController {

    private static final Logger logger = LoggerFactory.getLogger(AdminViewController.class);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    /** data-role of a System Admin Team row: none of the roles set in the Admin Panel. */
    static final String SYSTEM_ADMIN_ROLE = "SYSTEM_ADMIN";

    private final UserService userService;
    private final AuthMode authMode;
    private final com.kpmg.qtracker.service.notification.EmailLog emailLog;
    private final com.kpmg.qtracker.service.AuditLog auditLog;

    /**
     * One row of the users table: the user, their Access lines, whether it is the viewer, and whether the account is
     * System Admin Team (set on the server: shown as such, with no Edit, AccessPolicy.userChangeRefusal).
     */
    public record UserRow(User user, AccessPolicy.Profile profile, List<String> accessLines, String lastLogin,
                          boolean self, boolean systemAdmin) {

        public String role() {
            return systemAdmin ? SYSTEM_ADMIN_ROLE : profile.role().name();
        }

        /** The role as the badge shows it. */
        public String roleName() {
            return systemAdmin ? AccessPolicy.SYSTEM_ADMIN_NAME : profile.role().getDisplayName();
        }

        public String visibility() {
            return profile.visibility() != null ? profile.visibility().name() : "";
        }

        public String access() {
            return profile.access() != null ? profile.access().name() : "";
        }

        /** What the Visibility and Access filters match: the Access lines, for every role. */
        public String visibilityKey() {
            return RoleDisplayMapper.visibilityKey(profile);
        }

        public String accessKey() {
            return RoleDisplayMapper.accessKey(profile);
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
        // What Add user preselects for the role User (AccessPolicy.Profile.NEW_USER_*)
        model.addAttribute("newUserVisibility", AccessPolicy.Profile.NEW_USER_VISIBILITY.name());
        model.addAttribute("newUserAccess", AccessPolicy.Profile.NEW_USER_ACCESS.name());
        model.addAttribute("visibilityFilter", RoleDisplayMapper.visibilityFilter());
        model.addAttribute("accessFilter", RoleDisplayMapper.accessFilter());
        // The Permissions text of each role and the values a role fixes, for the user dialog
        Map<String, List<RoleDisplayMapper.FixedValue>> roleFixedValues = new LinkedHashMap<>();
        for (UserRole role : UserRole.values()) {
            roleFixedValues.put(role.name(), RoleDisplayMapper.fixedValues(role));
        }
        model.addAttribute("roleFixedValues", roleFixedValues);
        model.addAttribute("permissions", RoleDisplayMapper.permissionsByKey());
        model.addAttribute("roleAccessLines", RoleDisplayMapper.roleAccessLines());
        model.addAttribute("canManageUsers", AccessPolicy.canManageUsers(subject));
        // With SSO people sign in with their corporate e-mail at Microsoft and get no password
        model.addAttribute("sso", authMode.sso());
        // The Audit Trail is a section of its own (/admin/audit, 2026-11-10)

        return "admin-users";
    }

    /**
     * The E-mails page of the Admin Panel (2026-11-10): every e-mail QTracker made, newest first, with its state in plain
     * words (EmailLog). SoQM Admin Team and System Admin Team (AccessPolicy.seesEmailLog); it only reads.
     */
    @GetMapping("/emails")
    public String emails(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate to,
                         @RequestParam(required = false) String status,
                         @RequestParam(required = false) String event,
                         @RequestParam(required = false) String q,
                         @RequestParam(defaultValue = "1") int page,
                         Model model, HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return "redirect:/login";
        }
        if (!AccessPolicy.seesEmailLog(AccessPolicy.Subject.of(currentUser))) {
            return "redirect:/";
        }
        com.kpmg.qtracker.service.notification.EmailLog.Page result = emailLog.find(
                new com.kpmg.qtracker.service.notification.EmailLog.Filter(from, to, status, event, q, page));
        model.addAttribute("log", result);
        model.addAttribute("statuses", com.kpmg.qtracker.service.notification.EmailLog.Status.values());
        model.addAttribute("events", com.kpmg.qtracker.service.notification.EmailLog.EVENTS);
        model.addAttribute("status", status == null ? "" : status);
        model.addAttribute("event", event == null ? "" : event);
        model.addAttribute("q", q == null ? "" : q);
        return "admin-emails";
    }

    /**
     * An e-mail exactly as the person got it, for View on the E-mails page (2026-11-10): its HTML with the pictures,
     * shown in a frame of the dialog. The page of the frame may load nothing, run nothing and send nothing (its own
     * Content-Security-Policy), so whatever the e-mail holds stays a picture of it. Only the plain text kept: that text.
     */
    @GetMapping("/emails/{id}/html")
    public org.springframework.http.ResponseEntity<String> emailHtml(@org.springframework.web.bind.annotation.PathVariable long id,
                                                                     HttpSession session) {
        org.springframework.http.ResponseEntity<String> refused = refusedEmailLog(session);
        if (refused != null) {
            return refused;
        }
        return emailLog.body(id)
                .map(body -> org.springframework.http.ResponseEntity.ok()
                        .contentType(new org.springframework.http.MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                        .header("Content-Security-Policy", EMAIL_FRAME_POLICY)
                        .header("Cache-Control", "no-store")
                        .body(body.html() != null ? body.html()
                                : "<!DOCTYPE html><html><body><pre style=\"white-space:pre-wrap;font:14px/1.5 Arial,sans-serif;margin:16px\">"
                                + org.springframework.web.util.HtmlUtils.htmlEscape(body.text()) + "</pre></body></html>"))
                .orElseGet(() -> org.springframework.http.ResponseEntity.notFound().build());
    }

    /** The plain-text part of the same e-mail (what a mail program without HTML shows), for View's "Plain text". */
    @GetMapping("/emails/{id}/text")
    public org.springframework.http.ResponseEntity<String> emailText(@org.springframework.web.bind.annotation.PathVariable long id,
                                                                     HttpSession session) {
        org.springframework.http.ResponseEntity<String> refused = refusedEmailLog(session);
        if (refused != null) {
            return refused;
        }
        return emailLog.body(id).filter(body -> body.text() != null)
                .map(body -> org.springframework.http.ResponseEntity.ok()
                        .contentType(new org.springframework.http.MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                        .header("Cache-Control", "no-store")
                        .body(body.text()))
                .orElseGet(() -> org.springframework.http.ResponseEntity.notFound().build());
    }

    /** The e-mail in the frame: its own styles and pictures (data:) only; no scripts, requests, forms or other frames. */
    static final String EMAIL_FRAME_POLICY = "default-src 'none'; img-src data:; style-src 'unsafe-inline';"
            + " frame-ancestors 'self'; base-uri 'none'; form-action 'none'";

    /** 401 without a session, 403 for those who do not see the E-mails page (AccessPolicy.seesEmailLog); null: go on. */
    private static org.springframework.http.ResponseEntity<String> refusedEmailLog(HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return org.springframework.http.ResponseEntity.status(401).build();
        }
        if (!AccessPolicy.seesEmailLog(AccessPolicy.Subject.of(currentUser))) {
            return org.springframework.http.ResponseEntity.status(403).build();
        }
        return null;
    }

    /**
     * The Audit trail section of the Admin Panel (2026-11-10): every audit entry, newest first, in three groups (Users,
     * Controls, System) with before and after (AuditLog). SoQM Admin Team and System Admin Team (AccessPolicy.seesAuditLog).
     */
    @GetMapping("/audit")
    public String audit(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate to,
                        @RequestParam(required = false) String group,
                        @RequestParam(required = false) String action,
                        @RequestParam(required = false) String q,
                        @RequestParam(defaultValue = "1") int page,
                        Model model, HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return "redirect:/login";
        }
        if (!AccessPolicy.seesAuditLog(AccessPolicy.Subject.of(currentUser))) {
            return "redirect:/";
        }
        model.addAttribute("log", auditLog.find(new com.kpmg.qtracker.service.AuditLog.Filter(from, to, group, action, q, page)));
        model.addAttribute("groups", com.kpmg.qtracker.service.AuditLog.Group.values());
        model.addAttribute("actions", com.kpmg.qtracker.service.AuditLog.ACTIONS);
        model.addAttribute("group", group == null ? "" : group);
        model.addAttribute("action", action == null ? "" : action);
        model.addAttribute("q", q == null ? "" : q);
        return "admin-audit";
    }

    private static UserRow row(User user, User viewer) {
        AccessPolicy.Profile profile = AccessPolicy.Profile.of(user);
        boolean systemAdmin = AccessPolicy.Subject.of(user).systemAdmin();
        return new UserRow(user, profile,
                systemAdmin ? RoleDisplayMapper.SYSTEM_ADMIN_ACCESS_LINES : RoleDisplayMapper.accessLines(profile),
                user.getLastLoginAt() != null ? user.getLastLoginAt().format(DATE_TIME) : "",
                Objects.equals(user.getId(), viewer.getId()), systemAdmin);
    }
}
