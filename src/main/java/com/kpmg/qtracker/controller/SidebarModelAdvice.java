package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.security.AuthMode;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.ComponentControlsList;
import com.kpmg.qtracker.service.IControlService;
import com.kpmg.qtracker.service.KdnControlsOverview;
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
    private final ObjectProvider<AuthMode> authMode;
    private final ObjectProvider<IControlService> controlService;

    @ModelAttribute("sidebar")
    public SidebarInfo sidebar(HttpSession session) {
        User user = session != null ? (User) session.getAttribute("currentUser") : null;
        return user == null ? null : new SidebarInfo(user);
    }

    public final class SidebarInfo {
        private final User user;
        private Long unreadCount;
        private Boolean kdnPage;

        private SidebarInfo(User user) {
            this.user = user;
        }

        public String getName() {
            return user.getDisplayName();
        }

        public String getEmail() {
            return user.getMail();
        }

        /** The role: SoQM Admin Team, User or KDN. */
        public String getRole() {
            return RoleDisplayMapper.role(user);
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

        /** SoQM can create controls (shows the "New Control" button). */
        public boolean isSoqm() {
            return AccessPolicy.canCreateControls(AccessPolicy.Subject.of(user));
        }

        /** The Admin Panel link. */
        public boolean isAdmin() {
            return AccessPolicy.canOpenAdminPanel(AccessPolicy.Subject.of(user));
        }

        /**
         * The list a page's breadcrumb leads back to (the Controls page is gone): every control the user sees, or the
         * KDN page for the role KDN, who has no Action Centre.
         */
        public String getListHref() {
            return isDashboard() ? "/component/" + ComponentControlsList.ALL_CODE : KdnControlsOverview.CONTROLS_HREF;
        }

        public String getListLabel() {
            return isDashboard() ? ComponentControlsList.ALL_NAME : ComponentControlsList.KDN_NAME;
        }

        /** The Dashboard and Action Centre items (AccessPolicy.seesDashboard: not for the role KDN). */
        public boolean isDashboard() {
            return AccessPolicy.seesDashboard(AccessPolicy.Subject.of(user));
        }

        /** The Notifications item, its bell and its counter (AccessPolicy.hasNotifications: not for the role KDN). */
        public boolean isNotifications() {
            return AccessPolicy.hasNotifications(AccessPolicy.Subject.of(user));
        }

        /** Where Notifications is: the dashboard's tab, or its own page for those without a dashboard. */
        public String getNotificationsHref() {
            return isDashboard() ? "/#notifications" : "/notifications";
        }

        /**
         * The KDN item (AccessPolicy.seesKdnPage). The controls are read only for a User with My controls, the one
         * case that depends on them, and once per page.
         */
        public boolean isKdnPage() {
            if (kdnPage == null) {
                AccessPolicy.Subject subject = AccessPolicy.Subject.of(user);
                long kdnControls = 0;
                IControlService controls = controlService.getIfAvailable();
                if (!AccessPolicy.seesKdnPage(subject, 0) && controls != null && user.getId() != null) {
                    // Only the KDN controls are read, not all of the person's controls on every page
                    kdnControls = controls.findVisibleKdnControlsForUser(user).size();
                }
                kdnPage = AccessPolicy.seesKdnPage(subject, kdnControls);
            }
            return kdnPage;
        }

        /** The Logout link (auth.logout.enabled; a slice test without AuthMode keeps it). */
        public boolean isLogout() {
            AuthMode mode = authMode.getIfAvailable();
            return mode == null || mode.logout();
        }

        /** Computed lazily, only when a page actually renders the sidebar; none without notifications. */
        public long getUnreadCount() {
            if (unreadCount == null && !isNotifications()) {
                unreadCount = 0L;
            }
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
