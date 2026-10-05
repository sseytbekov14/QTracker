package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.NotificationService;
import com.kpmg.qtracker.util.NotificationTypeDisplayMapper;
import com.kpmg.qtracker.util.RoleDisplayMapper;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Supplies the data for the shared sidebar (templates/fragments/sidebar.html) to page controllers,
 * so every page shows the same user block, navigation and unread badge.
 */
@ControllerAdvice(assignableTypes = {ViewController.class, AdminViewController.class})
@RequiredArgsConstructor
public class SidebarModelAdvice {

    // ObjectProvider: @ControllerAdvice beans are loaded into every @WebMvcTest slice, where these may be absent
    private final ObjectProvider<NotificationService> notificationService;
    private final ObjectProvider<NotificationTypeDisplayMapper> notificationTypeDisplayMapper;

    @ModelAttribute("sidebar")
    public SidebarInfo sidebar(HttpSession session) {
        User user = session != null ? (User) session.getAttribute("currentUser") : null;
        return user == null ? null : new SidebarInfo(user);
    }

    public final class SidebarInfo {
        private final User user;
        private Long unreadCount;

        private SidebarInfo(User user) {
            this.user = user;
        }

        public String getName() {
            return user.getDisplayName();
        }

        public String getEmail() {
            return user.getMail();
        }

        /** Up to two initials for the avatar: "SoQM Team 1" -> "ST", fallback to the e-mail. */
        public String getInitials() {
            String source = user.getDisplayName() != null && !user.getDisplayName().isBlank()
                    ? user.getDisplayName()
                    : (user.getMail() != null ? user.getMail().split("@")[0] : "");
            StringBuilder initials = new StringBuilder();
            for (String part : source.trim().split("[\\s._-]+")) {
                if (!part.isEmpty() && Character.isLetter(part.charAt(0))) {
                    initials.append(Character.toUpperCase(part.charAt(0)));
                }
                if (initials.length() == 2) {
                    break;
                }
            }
            return initials.length() > 0 ? initials.toString() : "?";
        }

        /** The user's access, e.g. "Participant · KDN" (RoleDisplayMapper). */
        public String getRoleLabel() {
            return RoleDisplayMapper.access(user);
        }

        /** SoQM can create controls (shows the "New Control" button). */
        public boolean isSoqm() {
            return AccessPolicy.canCreateControls(AccessPolicy.Subject.of(user));
        }

        /** The Admin Panel link. */
        public boolean isAdmin() {
            return AccessPolicy.canOpenAdminPanel(AccessPolicy.Subject.of(user));
        }

        /** Computed lazily, only when a page actually renders the sidebar. */
        public long getUnreadCount() {
            if (unreadCount == null) {
                NotificationService notifications = notificationService.getIfAvailable();
                NotificationTypeDisplayMapper typeMapper = notificationTypeDisplayMapper.getIfAvailable();
                unreadCount = user.getId() == null || notifications == null ? 0L
                        : notifications.getUnreadNotifications(user.getId()).stream()
                                .filter(n -> typeMapper == null || !typeMapper.isHiddenType(n.getType()))
                                .count();
            }
            return unreadCount;
        }
    }
}
