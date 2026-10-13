package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.*;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.enums.WorkflowActionType;
import com.kpmg.qtracker.exception.ControlNotAvailableException;
import com.kpmg.qtracker.exception.ForbiddenException;
import com.kpmg.qtracker.exception.ResourceNotFoundException;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDocumentsRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.repository.WorkflowStepRepository;
import com.kpmg.qtracker.service.*;
import com.kpmg.qtracker.util.NotificationTypeDisplayMapper;
import com.kpmg.qtracker.util.RoleDisplayMapper;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.kpmg.qtracker.service.WorkflowService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ViewController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ViewController.class);

    private static final int DASHBOARD_ACTION_ITEMS_LIMIT = 8;
    private static final int DUE_SOON_DAYS = 3;
    private static final int NOTIFICATIONS_PAGE_SIZE = 50;
    private static final int NOTIFICATIONS_MAX_LIMIT = 1000;

    private final UserService userService;
    private final IControlService controlService;
    private final IPerformanceService performanceService;
    private final DashboardService dashboardService;
    private final ControlAssignmentService controlAssignmentService;
    private final WorkflowService workflowService;
    private final ControlAssignmentRepository controlAssignmentRepository;
    private final ControlDetailsService controlDetailsService;
    private final ControlDocumentsRepository controlDocumentsRepository;
    private final NotificationService notificationService;
    private final WorkflowHistoryRepository workflowHistoryRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final NotificationTypeDisplayMapper notificationTypeDisplayMapper;
    private final PermissionService permissionService;
    private final StatusDisplayMapper statusDisplayMapper;
    private final WorkflowTransitionGuard transitionGuard;
    private final ComponentControlsExcel componentControlsExcel;

    private User getCurrentUser(HttpSession session) {
        return (User) session.getAttribute("currentUser");
    }

    private String checkAuthAndRedirect(HttpSession session) {
        if (getCurrentUser(session) == null) {
            return "redirect:/login";
        }
        return null;
    }

    /** The former Initiation Checklist URL; the Initiate page checks access itself. */
    @GetMapping("/performance/{controlId}")
    public String performanceChecklist(@PathVariable Long controlId) {
        return "redirect:/initiate/" + controlId;
    }

    /**
     * Initiation checklist of a draft: what is still missing, the SoQM Year and the Initiate button.
     * Anyone the INITIATE transition does not allow (other participants, controls already in the
     * workflow) is sent to View Control.
     */
    @GetMapping("/initiate/{id}")
    public String initiateControl(@PathVariable Long id, Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);
        Control control = controlService.getControlById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Control not found with id: " + id));
        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(id);
        ControlPermission permission = permissionService.resolve(control, currentUser, assignment);
        if (!permission.canView()) {
            throw new ForbiddenException("You do not have permission to view this control.");
        }
        if (!transitionGuard.check(control, permission, WorkflowTransition.INITIATE).allowed()) {
            return "redirect:/view-control/" + id;
        }

        List<InitiationReadiness.Item> items = InitiationReadiness.items(control, assignment);
        List<InitiationRow> rows = items.stream()
                .map(item -> new InitiationRow(item.label(), item.tab(), item.done(),
                        item.done() ? initiationValue(item.field(), control, assignment) : null))
                .toList();
        LocalDate todayAlmaty = DeadlineOverdue.today(Instant.now());

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userEmail", currentUser.getMail());
        model.addAttribute("control", control);
        model.addAttribute("initiationRows", rows);
        model.addAttribute("initiationReady", InitiationReadiness.isReady(items));
        model.addAttribute("missingCount", rows.stream().filter(row -> !row.done()).count());
        model.addAttribute("soqmYearOptions", SoqmYear.options(todayAlmaty));
        model.addAttribute("initiateSoqmYear", SoqmYear.preselected(control.getSoqmYear(), todayAlmaty));
        return "initiate-control";
    }

    /** One line of the Initiate page: the item, the View Control tab that holds it, and its value once filled in. */
    public record InitiationRow(String label, String tab, boolean done, String value) {
    }

    private String initiationValue(String field, Control control, ControlAssignmentDTO assignment) {
        return switch (field) {
            case "facilitator" -> joinDisplayNames(assignment.getFacilitator());
            case "controlOperator" -> joinDisplayNames(assignment.getControlOperator());
            case "soqmLead" -> joinDisplayNames(assignment.getSoqmLead());
            case "processOwner" -> joinDisplayNames(assignment.getProcessOwner());
            case "controlOperationDate" -> assignment.getControlOperationDate()
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            case "controlFrequency" -> control.getControlFrequency();
            default -> null;
        };
    }

    @GetMapping({"/performance", "/performance/"})
    public String performanceWithoutId(HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;
        return "redirect:/component/ALL";
    }

    @GetMapping("/")
    public String dashboard(@RequestParam(value = "notifLimit", required = false) Integer notifLimit,
                            Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);
        String userEmail = currentUser.getMail();
        AccessPolicy.Subject subject = AccessPolicy.Subject.of(currentUser);
        // The role KDN starts on the KDN page and has no dashboard (AccessPolicy.KDN_SEES_DASHBOARD)
        if (!AccessPolicy.seesDashboard(subject)) {
            return "redirect:" + KdnControlsOverview.CONTROLS_HREF;
        }

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userEmail", userEmail);
        model.addAttribute("userIsAdmin", AccessPolicy.canOpenAdminPanel(subject));
        // The SoQM review queue link and the organisation-wide charts; everyone else gets their own
        model.addAttribute("userIsSoqm", AccessPolicy.isSoqm(subject));
        model.addAttribute("organisationCharts", AccessPolicy.seesOrganisationCharts(subject));

        // Unread notifications badge
        model.addAttribute("unreadNotifications", getUnreadCount(currentUser));

        List<ControlResponseDTO> allControls = findControlsVisibleToUser(currentUser);
        LocalDate todayAlmaty = DeadlineOverdue.today(Instant.now());
        for (ControlResponseDTO control : allControls) {
            control.setOverdue(DeadlineOverdue.isOverdue(control.getPerformanceStatus(), control.getDeadline(), todayAlmaty));
        }
        // The same controls as the Controls list behind each tile, drafts as far as the user sees them
        ControlCounters dashboardCounters = countControlsVisibleToUser(allControls);

        model.addAttribute("totalControls", dashboardCounters.total());
        model.addAttribute("activeControls", dashboardCounters.active());
        model.addAttribute("completedControls", dashboardCounters.completed());
        model.addAttribute("overdueControls", dashboardCounters.overdue());

        // ===== AWAITING MY ACTION =====
        // Controls where the current workflow step belongs to this user; overdue first, then nearest deadline.
        // Not for KDN: they never act on a control, so the block and its header line are left out
        if (AccessPolicy.seesActionQueue(subject)) {
            List<ControlResponseDTO> actionItems = allControls.stream()
                    .filter(control -> isMyTurn(subject, userEmail, control))
                    .sorted(Comparator.comparing(ControlResponseDTO::isOverdue).reversed()
                            .thenComparing(ControlResponseDTO::getDeadline,
                                    Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());
            model.addAttribute("actionItems", actionItems.stream().limit(DASHBOARD_ACTION_ITEMS_LIMIT).toList());
            model.addAttribute("actionItemsTotal", actionItems.size());
            model.addAttribute("actionItemsOverdue", actionItems.stream().filter(ControlResponseDTO::isOverdue).count());
        }

        // ===== ACTION CENTRE DATA =====
        // The component cards; the KDN controls have their own page (/kdn, in the menu)
        addComponentSummaries(model, allControls, todayAlmaty);

        addNotifications(model, currentUser, notifLimit);
        return "dashboard";
    }

    /**
     * The notifications of the person. Everyone else has them on the dashboard's Notifications tab; the role KDN,
     * without a dashboard (AccessPolicy.KDN_SEES_DASHBOARD), here on the same page with only that tab.
     */
    @GetMapping("/notifications")
    public String notifications(@RequestParam(value = "notifLimit", required = false) Integer notifLimit,
                                Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);
        if (AccessPolicy.seesDashboard(AccessPolicy.Subject.of(currentUser))) {
            return "redirect:/#notifications";
        }
        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userEmail", currentUser.getMail());
        model.addAttribute("unreadNotifications", getUnreadCount(currentUser));
        model.addAttribute("notificationsOnly", true);
        addNotifications(model, currentUser, notifLimit);
        return "dashboard";
    }

    // Newest first, NOTIFICATIONS_PAGE_SIZE at a time ("Show more" raises notifLimit).
    // The limit is applied before mapping because each item loads its control.
    private void addNotifications(Model model, User currentUser, Integer notifLimit) {
        int limit = notifLimit == null || notifLimit < NOTIFICATIONS_PAGE_SIZE
                ? NOTIFICATIONS_PAGE_SIZE
                : Math.min(notifLimit, NOTIFICATIONS_MAX_LIMIT);
        List<com.kpmg.qtracker.entity.Notification> visibleNotifications =
                notificationService.getUserNotifications(currentUser.getId()).stream()
                        .filter(notif -> !notificationTypeDisplayMapper.isHiddenType(notif.getType()))
                        .collect(Collectors.toList());
        List<NotificationItemDTO> notifications = visibleNotifications.stream()
                .limit(limit)
                .map(this::convertNotificationToDTO)
                .collect(Collectors.toList());
        model.addAttribute("notificationGroups", groupNotificationsByDate(notifications));
        model.addAttribute("notificationsTotal", visibleNotifications.size());
        model.addAttribute("notificationsShown", notifications.size());
        model.addAttribute("notificationsNextLimit",
                Math.min(limit + NOTIFICATIONS_PAGE_SIZE, NOTIFICATIONS_MAX_LIMIT));
    }

    /**
     * The Controls page is gone (business decision of 2026-10-13): its address leads to the Action Centre's list of
     * every control the user sees (/component/ALL), or of the component asked for, with the known parameters
     * carried over (filter=OVERDUE|COMPLETED, status=..., scope=active as Active, kdn=1, q), so old bookmarks and
     * links keep working. Who sees what is decided by the list, through the policy.
     */
    @GetMapping("/controls")
    public String controls(@RequestParam(value = "scope", required = false) String scope,
                           @RequestParam(value = "status", required = false) String status,
                           @RequestParam(value = "filter", required = false) String filter,
                           @RequestParam(value = "component", required = false) String component,
                           @RequestParam(value = "kdn", required = false) String kdn,
                           @RequestParam(value = "q", required = false) String q) {
        String componentCode = resolveComponentCode(component);
        String listStatus = controlsPageStatus(scope, status, filter);
        org.springframework.web.util.UriComponentsBuilder target = org.springframework.web.util.UriComponentsBuilder
                .fromPath("/component/{code}");
        if (q != null && !q.isBlank()) {
            target.queryParam("q", q.trim());
        }
        if (listStatus != null) {
            target.queryParam("status", listStatus);
        }
        if (ComponentControlsList.Query.of(null, null, null, null, null, null, kdn).kdn()) {
            target.queryParam("kdn", "1");
        }
        return "redirect:" + target.buildAndExpand(componentCode != null ? componentCode : ComponentControlsList.ALL_CODE)
                .encode().toUriString();
    }

    /** The list's status filter for the Controls page's filter, status or scope; null for none. */
    static String controlsPageStatus(String scope, String status, String filter) {
        String filterValue = filter == null ? "" : filter.trim().toUpperCase(Locale.ROOT);
        if ("OVERDUE".equals(filterValue) || "COMPLETED".equals(filterValue)) {
            return filterValue;
        }
        ComponentControlsList.StatusFilter byStatus = ComponentControlsList.Query.of(null, status, null, null, null, null)
                .status();
        if (byStatus != ComponentControlsList.StatusFilter.ALL) {
            return byStatus.key();
        }
        return scope != null && "active".equalsIgnoreCase(scope.trim())
                ? ComponentControlsList.StatusFilter.ACTIVE.key() : null;
    }

    private List<ControlResponseDTO> findControlsVisibleToUser(User currentUser) {
        if (currentUser == null) {
            return new ArrayList<>();
        }
        String userEmail = currentUser.getMail();
        // "Shared" (view only) only for those who see a control because it is shared with them
        boolean seesAll = AccessPolicy.seesWithoutBeingOn(AccessPolicy.Subject.of(currentUser));
        List<Control> visibleControls = controlService.findVisibleControlsForUser(currentUser);
        List<Control> listed = visibleControls.stream()
                .filter(control -> control != null && control.getId() != null)
                .toList();
        // All rows in one conversion (the people read once, not per control)
        Map<Long, ControlResponseDTO> controlMap = new LinkedHashMap<>();
        for (ControlResponseDTO dto : controlService.convertToResponseDTOs(listed)) {
            // The assignment is read again only for a control the person is not assigned to
            boolean sharedOnly = !seesAll
                    && !isDirectlyAssignedToUser(dto, userEmail)
                    && isSharedWithUser(dto.getId(), userEmail);
            dto.setSharedViewOnly(sharedOnly);
            controlMap.put(dto.getId(), dto);
        }
        return new ArrayList<>(controlMap.values());
    }

    private ControlCounters countControlsVisibleToUser(List<ControlResponseDTO> controls) {
        List<ControlResponseDTO> base = controls == null ? new ArrayList<>() : new ArrayList<>(controls);
        DeadlineOverdue.Counts counts = DeadlineOverdue.count(base,
                ControlResponseDTO::getPerformanceStatus, ControlResponseDTO::getDeadline, DeadlineOverdue.today(Instant.now()));
        return new ControlCounters(Math.toIntExact(counts.total()), Math.toIntExact(counts.active()),
                Math.toIntExact(counts.completed()), Math.toIntExact(counts.overdue()));
    }

    /** Known component code (case-insensitive), or null for "all components" / unknown values. */
    private String resolveComponentCode(String component) {
        if (component == null || component.isBlank()) {
            return null;
        }
        String trimmed = component.trim();
        return COMPONENT_NAMES.keySet().stream()
                .filter(code -> code.equalsIgnoreCase(trimmed))
                .findFirst()
                .orElse(null);
    }

    /**
     * An Action Centre card: a component, KDN or "All components", with the counters of its list
     * (ComponentControlsList.Counts, the same numbers the list behind the card shows).
     */
    public record ComponentSummary(String code, String name, ComponentControlsList.Counts counts) {
        public long total() {
            return counts.total();
        }

        public long inProgress() {
            return counts.inProgress();
        }

        public long inReview() {
            return counts.inReview();
        }

        public long completed() {
            return counts.completed();
        }

        public long overdue() {
            return counts.overdue();
        }

        /** Not completed and not overdue (the bar's remainder). */
        public long active() {
            return counts.active();
        }

        public int completedPercent() {
            return counts.completedPercent();
        }

        /** "View all KDN controls", "View all HR controls": the card's link text. */
        public String viewAllLabel() {
            return "View all " + (ComponentControlsList.KDN_CODE.equals(code) ? ComponentControlsList.KDN_NAME
                    : code + " controls");
        }
    }

    private static final Map<String, String> COMPONENT_NAMES = new LinkedHashMap<>();
    static {
        COMPONENT_NAMES.put("HR", "Human Resources");
        COMPONENT_NAMES.put("EP", "Engagement Performance");
        COMPONENT_NAMES.put("A&C", "Acceptance & Continuance");
        COMPONENT_NAMES.put("RER", "Relevant Ethical Requirements");
        COMPONENT_NAMES.put("INTR", "Intellectual Resources");
        COMPONENT_NAMES.put("I&C", "Information & Communication");
        COMPONENT_NAMES.put("GOV", "Governance");
        COMPONENT_NAMES.put("TECHR", "Technological Resources");
        COMPONENT_NAMES.put("M&R", "Monitoring & Remediation");
        COMPONENT_NAMES.put("RAP", "Risk Assessment Process");
    }

    /** The component cards: each counts the controls of its list (ComponentControlsList.controlsOf). */
    private void addComponentSummaries(Model model, List<ControlResponseDTO> controls, LocalDate today) {
        List<ComponentSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, String> component : COMPONENT_NAMES.entrySet()) {
            summaries.add(new ComponentSummary(component.getKey(), component.getValue(),
                    ComponentControlsList.Counts.of(ComponentControlsList.controlsOf(component.getKey(), controls), today)));
        }
        model.addAttribute("componentSummaries", summaries);
        model.addAttribute("componentSummaryAll", new ComponentSummary(ComponentControlsList.ALL_CODE,
                ComponentControlsList.ALL_NAME, ComponentControlsList.Counts.of(listControls(ComponentControlsList.ALL_CODE, controls), today)));
    }

    /**
     * The controls of an Action Centre list: a component's, the KDN controls, or ALL = every control the user sees,
     * whatever its component (what the "All components" card counts; it took the place of the Controls page).
     */
    private static List<ControlResponseDTO> listControls(String code, List<ControlResponseDTO> visible) {
        if (!ComponentControlsList.ALL_CODE.equals(code)) {
            return ComponentControlsList.controlsOf(code, visible);
        }
        return visible;
    }

    private record ControlCounters(int total, int active, int completed, int overdue) {
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "DRAFT";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    // Day the control was completed according to workflow history; only used for "Closed late"
    private LocalDate completedOn(ControlResponseDTO control, Map<Long, LocalDateTime> completionTimeByControlId) {
        if (control == null || control.getId() == null || completionTimeByControlId == null) {
            return null;
        }
        LocalDateTime completedAt = completionTimeByControlId.get(control.getId());
        return completedAt != null ? completedAt.toLocalDate() : null;
    }

    private Map<Long, LocalDateTime> resolveCompletionTimes(List<ControlResponseDTO> controls) {
        if (controls == null || controls.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Long> controlIds = controls.stream()
                .map(ControlResponseDTO::getId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (controlIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, LocalDateTime> completionByControlId = new HashMap<>();

        List<Object[]> stepRows = workflowStepRepository.findLatestCompletedAtByControlIds(controlIds);
        mergeCompletionRows(completionByControlId, stepRows);

        List<Object[]> historyRows = workflowHistoryRepository.findLatestCompletionTimestampByControlIds(controlIds);
        mergeCompletionRows(completionByControlId, historyRows);

        return completionByControlId;
    }

    private void mergeCompletionRows(Map<Long, LocalDateTime> target, List<Object[]> rows) {
        if (target == null || rows == null || rows.isEmpty()) {
            return;
        }
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || !(row[0] instanceof Number) || !(row[1] instanceof LocalDateTime)) {
                continue;
            }
            Long controlId = ((Number) row[0]).longValue();
            LocalDateTime completionTime = (LocalDateTime) row[1];
            LocalDateTime existing = target.get(controlId);
            if (existing == null || completionTime.isAfter(existing)) {
                target.put(controlId, completionTime);
            }
        }
    }

    /** "Your turn": the control's current step is the user's ({@link AccessPolicy#isMyTurn}). */
    private boolean isMyTurn(AccessPolicy.Subject subject, String userEmail, ControlResponseDTO control) {
        if (control == null || userEmail == null) {
            return false;
        }
        return AccessPolicy.isMyTurn(subject, new AccessPolicy.ControlFacts(
                control.getPerformanceStatus(),
                AccessPolicy.isKdnControl(control.getControlId()),
                listContains(control.getFacilitators(), userEmail),
                listContains(control.getControlOperators(), userEmail),
                listContains(control.getSoqmLeads(), userEmail),
                listContains(control.getProcessOwners(), userEmail),
                false));
    }

    private boolean listContains(List<String> items, String value) {
        if (items == null || value == null) return false;
        for (String item : items) {
            if (value.equalsIgnoreCase(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDirectlyAssignedToUser(ControlResponseDTO control, String userEmail) {
        if (control == null || userEmail == null) {
            return false;
        }
        return listContains(control.getFacilitators(), userEmail)
                || listContains(control.getControlOperators(), userEmail)
                || listContains(control.getSoqmLeads(), userEmail)
                || listContains(control.getProcessOwners(), userEmail);
    }

    private boolean isSharedWithUser(Long controlId, String userEmail) {
        if (controlId == null || userEmail == null) {
            return false;
        }
        try {
            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(controlId);
            if (assignment == null) {
                return false;
            }
            return containsEmailNormalized(assignment.getControlSharedWith(), userEmail);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean containsEmailNormalized(List<String> emails, String userEmail) {
        if (emails == null || userEmail == null) {
            return false;
        }
        String target = userEmail.trim().toLowerCase(Locale.ROOT);
        for (String email : emails) {
            if (email == null) {
                continue;
            }
            if (email.trim().toLowerCase(Locale.ROOT).equals(target)) {
                return true;
            }
        }
        return false;
    }

    @GetMapping("/notification/{notificationId}")
    public String notificationDetail(@PathVariable String notificationId, Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);

        try {
            Long notifId = Long.parseLong(notificationId);

            // Get notification from DB
            com.kpmg.qtracker.entity.Notification notif =
                    notificationService.getUserNotifications(currentUser.getId()).stream()
                            .filter(n -> n.getId().equals(notifId))
                            .findFirst()
                            .orElse(null);

            if (notif == null || notificationTypeDisplayMapper.isHiddenType(notif.getType())) {
                model.addAttribute("error", "Notification not found");
                return "notification-detail";
            }

            notificationService.markAsRead(notifId);
            NotificationItemDTO dto = convertNotificationToDTO(notif);
            model.addAttribute("notification", dto);
            // "Go to Control": View Control, a draft on its Assignment tab; no button if the control is gone
            controlService.getControlById(notif.getControlId()).ifPresent(control -> {
                boolean draft = "DRAFT".equals(normalizeStatus(control.getPerformanceStatus()));
                model.addAttribute("controlUrl", "/view-control/" + control.getId() + (draft ? "#assignment" : ""));
                if (control.getComponent() != null && COMPONENT_NAMES.containsKey(control.getComponent())) {
                    model.addAttribute("componentName",
                            COMPONENT_NAMES.get(control.getComponent()) + " (" + control.getComponent() + ")");
                }
            });
        } catch (NumberFormatException e) {
            model.addAttribute("error", "Invalid notification ID");
            return "notification-detail";
        }

        return "notification-detail";
    }

    @PostMapping("/notifications/mark-all-read")
    public String markAllNotificationsRead(HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;
        boolean dashboard = AccessPolicy.seesDashboard(AccessPolicy.Subject.of(getCurrentUser(session)));

        User currentUser = getCurrentUser(session);
        notificationService.markAllAsRead(currentUser.getId());

        return dashboard ? "redirect:/#notifications" : "redirect:/notifications";
    }

    /** Marks one own notification as read; the answer carries the new unread count for the badges. */
    @PostMapping("/notifications/{notificationId}/read")
    @ResponseBody
    public ResponseEntity<Map<String, Long>> markNotificationRead(@PathVariable Long notificationId,
                                                                  HttpSession session) {
        User currentUser = getCurrentUser(session);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean visible = notificationService.findForUser(notificationId, currentUser.getId())
                .filter(notif -> !notificationTypeDisplayMapper.isHiddenType(notif.getType()))
                .isPresent();
        if (!visible) {
            return ResponseEntity.notFound().build();
        }
        notificationService.markAsRead(notificationId);
        return ResponseEntity.ok(Map.of("unread", getUnreadCount(currentUser)));
    }

    private void applyNotificationDisplay(NotificationItemDTO dto) {
        NotificationTypeDisplayMapper.Display display =
                notificationTypeDisplayMapper.map(dto.getType(), dto.getMessage());
        dto.setDisplayLabel(display.label());
        dto.setBadgeClass(display.badgeClass());
    }

    private long getUnreadCount(User currentUser) {
        List<com.kpmg.qtracker.entity.Notification> unread =
                notificationService.getUnreadNotifications(currentUser.getId());
        if (unread == null || unread.isEmpty()) {
            return 0;
        }
        return unread.stream()
                .filter(notif -> !notificationTypeDisplayMapper.isHiddenType(notif.getType()))
                .count();
    }

    private NotificationItemDTO convertNotificationToDTO(com.kpmg.qtracker.entity.Notification notif) {
        NotificationItemDTO dto = new NotificationItemDTO();
        dto.setId(String.valueOf(notif.getId()));
        dto.setType(notif.getType());
        dto.setControlId(notif.getControlId());
        
        // Get control details
        controlService.getControlById(notif.getControlId()).ifPresent(control -> {
            dto.setControlIdNumber(control.getControlId());
            dto.setComponent(control.getComponent());
        });
        
        dto.setMessage(notif.getTitle());
        dto.setFullText(notif.getMessage());
        dto.setTimestamp(notif.getCreatedAt());
        dto.setRead(notif.getIsRead());
        applyNotificationDisplay(dto);
        
        return dto;
    }

    /**
     * The Action Centre's list of a component's controls, or of the KDN controls (/component/KDN): the controls
     * the user sees (findControlsVisibleToUser, the policy), sorted, searched, filtered and paged by
     * ComponentControlsList; /component/All lists the controls of every component. Unknown codes lead to the
     * Controls list.
     */
    @GetMapping("/component/{componentName}")
    public String controlsByComponent(@PathVariable String componentName,
                                      @RequestParam(value = "q", required = false) String q,
                                      @RequestParam(value = "status", required = false) String status,
                                      @RequestParam(value = "sort", required = false) String sort,
                                      @RequestParam(value = "dir", required = false) String dir,
                                      @RequestParam(value = "page", required = false) Integer page,
                                      @RequestParam(value = "size", required = false) Integer size,
                                      @RequestParam(value = "kdn", required = false) String kdnFilter,
                                      Model model, HttpSession session, jakarta.servlet.http.HttpServletRequest request) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);
        // The role KDN has no Action Centre (AccessPolicy.KDN_SEES_DASHBOARD): its lists lead to the KDN page
        if (!AccessPolicy.seesDashboard(AccessPolicy.Subject.of(currentUser))) {
            return "redirect:" + KdnControlsOverview.CONTROLS_HREF;
        }
        String code = listCode(componentName);
        if (code == null) {
            return "redirect:/component/" + ComponentControlsList.ALL_CODE;
        }
        // The KDN list is the KDN page now; old links keep their search, filter, sorting and page
        if (ComponentControlsList.KDN_CODE.equals(code)) {
            org.springframework.web.util.UriComponentsBuilder target = org.springframework.web.util.UriComponentsBuilder
                    .fromPath(KdnControlsOverview.CONTROLS_HREF);
            for (String name : List.of("q", "status", "sort", "dir", "page", "size")) {
                String value = request.getParameter(name);
                if (value != null && !value.isBlank()) {
                    target.queryParam(name, value.trim());
                }
            }
            return "redirect:" + target.build().encode().toUriString();
        }
        String basePath = org.springframework.web.util.UriComponentsBuilder.fromPath("/component/{code}")
                .buildAndExpand(code).encode().toUriString();
        ComponentControlsList.Query query = ComponentControlsList.Query.of(q, status, sort, dir, page, size, kdnFilter);
        return renderList(code, basePath, query, currentUser, model, null);
    }

    /**
     * The KDN page (business decision of 2026-10-13): the KDN controls the user sees, as the Action Centre's lists
     * show them (counters, ten columns, sorting, search, status filter, pages, Export for SoQM Admin Team). Its item
     * in the menu and the page itself: AccessPolicy.seesKdnPage; anyone else gets 403. The role KDN starts here.
     */
    @GetMapping("/kdn")
    public String kdnPage(@RequestParam(value = "q", required = false) String q,
                          @RequestParam(value = "status", required = false) String status,
                          @RequestParam(value = "sort", required = false) String sort,
                          @RequestParam(value = "dir", required = false) String dir,
                          @RequestParam(value = "page", required = false) Integer page,
                          @RequestParam(value = "size", required = false) Integer size,
                          Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);
        ComponentControlsList.Query query = ComponentControlsList.Query.of(q, status, sort, dir, page, size, null);
        return renderList(ComponentControlsList.KDN_CODE, KdnControlsOverview.CONTROLS_HREF, query, currentUser, model,
                count -> {
                    if (!AccessPolicy.seesKdnPage(AccessPolicy.Subject.of(currentUser), count)) {
                        throw new ForbiddenException("The KDN page is for those who see KDN controls");
                    }
                });
    }

    /** The KDN page's Excel: as every list's (SoQM Admin Team only). */
    @GetMapping("/kdn/export")
    public void exportKdn(@RequestParam(value = "q", required = false) String q,
                          @RequestParam(value = "status", required = false) String status,
                          @RequestParam(value = "sort", required = false) String sort,
                          @RequestParam(value = "dir", required = false) String dir,
                          HttpServletResponse response, HttpSession session) throws java.io.IOException {
        exportList(ComponentControlsList.KDN_CODE, q, status, sort, dir, null, response, session);
    }

    /**
     * One list page (component-controls.html): the controls of {@code code} the user sees, as {@code query} asks.
     * {@code admit} checks the number of controls of the list before anything is shown (the KDN page's rule).
     */
    private String renderList(String code, String basePath, ComponentControlsList.Query query, User currentUser,
                              Model model, java.util.function.LongConsumer admit) {
        AccessPolicy.Subject subject = AccessPolicy.Subject.of(currentUser);
        boolean kdn = ComponentControlsList.KDN_CODE.equals(code);
        boolean kdnPage = kdn && KdnControlsOverview.CONTROLS_HREF.equals(basePath);
        String name = listName(code);
        ListData data = null;
        RuntimeException failure = null;
        try {
            data = listData(code, currentUser);
        } catch (RuntimeException e) {
            log.error("Could not load the controls of {} for {}", code, currentUser.getMail(), e);
            failure = e;
        }
        if (admit != null) {
            admit.accept(data == null ? 0 : data.controls().size());
        }

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userEmail", currentUser.getMail());
        model.addAttribute("unreadNotifications", getUnreadCount(currentUser));
        model.addAttribute("listCode", code);
        model.addAttribute("listName", name);
        model.addAttribute("listTitle", kdnPage ? name : "Performance: " + name + " (" + code + ")");
        model.addAttribute("listPath", basePath);
        model.addAttribute("kdnList", kdn);
        model.addAttribute("kdnPage", kdnPage);
        model.addAttribute("allList", ComponentControlsList.ALL_CODE.equals(code));
        // The Excel of the list: SoQM Admin Team only (the server refuses everyone else, exportList)
        model.addAttribute("canExport", AccessPolicy.canExportControlLists(subject));
        model.addAttribute("query", query);
        if (failure == null) {
            model.addAttribute("list", ComponentControlsList.of(basePath, data.controls(), data.names(), query,
                    data.today(), data.marks()));
        } else {
            model.addAttribute("loadError", true);
        }
        return "component-controls";
    }

    /**
     * The Excel of a list, as the list shows it (search, status filter, KDN filter, sorting; every page): SoQM Admin
     * Team only (AccessPolicy.canExportControlLists), 403 for everyone else; 404 for a list that does not exist.
     */
    @GetMapping("/component/{componentName}/export")
    public void exportList(@PathVariable String componentName,
                           @RequestParam(value = "q", required = false) String q,
                           @RequestParam(value = "status", required = false) String status,
                           @RequestParam(value = "sort", required = false) String sort,
                           @RequestParam(value = "dir", required = false) String dir,
                           @RequestParam(value = "kdn", required = false) String kdnFilter,
                           HttpServletResponse response, HttpSession session) throws java.io.IOException {
        User currentUser = getCurrentUser(session);
        if (currentUser == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (!AccessPolicy.canExportControlLists(AccessPolicy.Subject.of(currentUser))) {
            log.warn("List export refused: user={} list={}", currentUser.getId(), componentName);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Only " + com.kpmg.qtracker.enums.UserRole.SOQM_TEAM_NAME + " can export lists");
            return;
        }
        String code = listCode(componentName);
        if (code == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        ListData data = listData(code, currentUser);
        ComponentControlsList.Query query = ComponentControlsList.Query.of(q, status, sort, dir, 1, null, kdnFilter);
        List<ComponentControlsList.Row> rows = ComponentControlsList.allRows(data.controls(), data.names(), query,
                data.today(), data.marks());
        String file = "QTracker_" + code.replaceAll("[^A-Za-z0-9]", "-") + "_" + data.today() + ".xlsx";
        response.setContentType(ComponentControlsExcel.CONTENT_TYPE);
        response.setHeader("Content-Disposition", "attachment; filename=\"" + file + "\"");
        response.setHeader("Cache-Control", "no-store");
        componentControlsExcel.write(listName(code), rows, response.getOutputStream());
    }

    /** The list's code for the path: a known component, KDN or ALL (any case); null for anything else. */
    private String listCode(String requested) {
        String value = requested == null ? "" : requested.trim();
        if (ComponentControlsList.KDN_CODE.equalsIgnoreCase(value)) {
            return ComponentControlsList.KDN_CODE;
        }
        if (ComponentControlsList.ALL_CODE.equalsIgnoreCase(value)) {
            return ComponentControlsList.ALL_CODE;
        }
        return resolveComponentCode(value);
    }

    private static String listName(String code) {
        return ComponentControlsList.KDN_CODE.equals(code) ? ComponentControlsList.KDN_NAME
                : ComponentControlsList.ALL_CODE.equals(code) ? ComponentControlsList.ALL_NAME : COMPONENT_NAMES.get(code);
    }

    /** A list's controls with what its rows show, read once: the people's names, Closed late, Your turn, KDN. */
    private record ListData(List<ControlResponseDTO> controls, Map<String, String> names,
                            ComponentControlsList.Marks marks, LocalDate today) {
    }

    private ListData listData(String code, User currentUser) {
        AccessPolicy.Subject subject = AccessPolicy.Subject.of(currentUser);
        LocalDate today = DeadlineOverdue.today(Instant.now());
        List<ControlResponseDTO> controls = listControls(code, findControlsVisibleToUser(currentUser));
        // "Closed late": the completion day from the workflow history, two queries for the whole list
        Map<Long, LocalDateTime> completedAt = resolveCompletionTimes(controls);
        Set<Long> yourTurn = new java.util.HashSet<>();
        for (ControlResponseDTO control : controls) {
            control.setOverdue(DeadlineOverdue.isOverdue(control.getPerformanceStatus(), control.getDeadline(), today));
            control.setClosedLate(DeadlineOverdue.isClosedLate(control.getPerformanceStatus(), control.getDeadline(),
                    completedOn(control, completedAt)));
            if (isMyTurn(subject, currentUser.getMail(), control)) {
                yourTurn.add(control.getId());
            }
        }
        // Every person of the list in one query
        Map<String, String> names = userService.displayNamesByEmail(ComponentControlsList.emailsOf(controls));
        return new ListData(controls, names,
                new ComponentControlsList.Marks(yourTurn, AccessPolicy.seesKdnMark(subject)), today);
    }

    @GetMapping("/view-control/{id}")
    public String viewControl(@PathVariable Long id, Model model, HttpSession session,
                              RedirectAttributes redirectAttributes) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);

        Control control = controlService.getControlById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Control not found with id: " + id));

        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(id);
        ControlPermission permission = permissionService.resolve(control, currentUser, assignment);

        switch (permissionService.readAccess(control, currentUser, assignment)) {
            case DENIED -> throw new ForbiddenException("You do not have permission to view this control.");
            case DRAFT_NOT_INITIATED -> throw new ControlNotAvailableException(
                    "This control is still in Draft and has not been initiated into the workflow. "
                            + "You will get access after it is initiated."
            );
            case ALLOWED -> {
            }
        }

        String performanceStatus = control.getPerformanceStatus();
        if (performanceStatus == null || performanceStatus.isEmpty()) {
            performanceStatus = "DRAFT";
        }

        String userEmail = currentUser.getMail();
        boolean readOnly = !permission.canEdit();

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userEmail", userEmail);
        model.addAttribute("control", control);
        model.addAttribute("performanceStatus", performanceStatus);
        model.addAttribute("readOnly", readOnly);
        model.addAttribute("canEditAll", permission.canEditAll());
        model.addAttribute("isFacilitator", permission.isFacilitator());
        model.addAttribute("isControlOperator", permission.isControlOperator());
        model.addAttribute("isSoqmLead", permission.isSoqmLead());
        model.addAttribute("isProcessOwner", permission.isProcessOwner());
        model.addAttribute("isSharedViewer", permission.isSharedViewer());
        model.addAttribute("canUseWorkflowActions", permission.canUseWorkflowActions());
        model.addAttribute("allowedEditableFields", permission.getAllowedEditableFields());
        model.addAttribute("allowedEditableFieldsCsv", String.join(",", permission.getAllowedEditableFields()));
        model.addAttribute("stepsLabel", ControlStepsFields.STEPS_LABEL);
        model.addAttribute("operatorProgramLabel", ControlStepsFields.OPERATOR_PROGRAM_LABEL);

        // Read Only / KDN, or a User with Edit who is not assigned: a notice instead of buttons the server refuses
        model.addAttribute("accessNotice", AccessPolicy.notice(AccessPolicy.Subject.of(currentUser), permission).name());

        // Rename ID: SoQM, also on a completed control (not an edit of it, AccessPolicy.canRenameId)
        model.addAttribute("canRenameId", AccessPolicy.canRenameId(permission));
        // The "KDN control" mark in the header and the hint at the Control ID, for SoQM Team
        model.addAttribute("kdnControlMark", AccessPolicy.seesKdnMark(AccessPolicy.Subject.of(currentUser))
                && AccessPolicy.isKdnControl(control.getControlId()));
        model.addAttribute("kdnIdHint", RoleDisplayMapper.KDN_ID_HINT);
        // A completed control (AccessPolicy.isLocked): locked for the user, or changed in place by SoQM Team
        model.addAttribute("completedLocked", permission.isLocked());
        model.addAttribute("completedEdit", permission.isCompletedEdit());
        model.addAttribute("completedControl", permission.isCompleted());

        // Control Shared With: each person with what the place gives them (AccessPolicy.sharedAccess); the marks
        // (Disabled, Not in the system, ...) for SoQM Team, who change the list
        model.addAttribute("sharedWithPeople",
                permissionService.sharedWithPeople(control, assignment, userService::getUserByEmail));
        model.addAttribute("sharedWithNotes", AccessPolicy.seesSharedWithNotes(AccessPolicy.Subject.of(currentUser)));
        model.addAttribute("sharedWithHint", RoleDisplayMapper.SHARED_WITH_HINT);
        model.addAttribute("sharedWithEmpty", RoleDisplayMapper.SHARED_WITH_EMPTY);

        // Header summary + workflow stepper
        String normalizedStatus = normalizeStatus(performanceStatus);
        LocalDate todayAlmaty = DeadlineOverdue.today(Instant.now());
        LocalDate deadline = DeadlineOverdue.deadlineOf(
                assignment != null ? assignment.getControlOperationDeadline() : null, control.getDeadline());
        model.addAttribute("deadline", deadline);
        model.addAttribute("overdue", DeadlineOverdue.isOverdue(performanceStatus, deadline, todayAlmaty));
        model.addAttribute("workflowStepIndex", workflowStepIndex(normalizedStatus));
        model.addAttribute("facilitatorNames", assignment != null ? joinDisplayNames(assignment.getFacilitator()) : null);
        model.addAttribute("operatorNames", assignment != null ? joinDisplayNames(assignment.getControlOperator()) : null);
        model.addAttribute("soqmNames", assignment != null ? joinDisplayNames(assignment.getSoqmLead()) : null);
        model.addAttribute("ownerNames", assignment != null ? joinDisplayNames(assignment.getProcessOwner()) : null);
        boolean yourTurn = permission.canUseWorkflowActions() && (
                ("IN_PROGRESS".equals(normalizedStatus) && permission.isFacilitator())
                        || ("REVIEW".equals(normalizedStatus) && permission.isControlOperator())
                        || ("SOQM_HEAD_REVIEW".equals(normalizedStatus) && permission.isSoqmLead())
                        || ("PROCESS_OWNER_REVIEW".equals(normalizedStatus) && permission.isProcessOwner()));
        model.addAttribute("yourTurn", yourTurn);
        // The field the user's step needs, named in the "Your step" hint and marked on the Details tab
        model.addAttribute("stepField", yourTurn
                ? WorkflowRequiredFieldService.stepField(normalizedStatus).orElse(null) : null);

        // SoQM moves the control on or back for any role (the Move dialog); everyone else has their step buttons
        model.addAttribute("soqmMoves", soqmMoves(permission, normalizedStatus, assignment));

        // A draft links to its Initiate page for whoever the server lets initiate it
        model.addAttribute("canInitiate",
                transitionGuard.check(control, permission, WorkflowTransition.INITIATE).allowed());
        // SoQM Year choices for the Control tab
        model.addAttribute("soqmYearOptions", SoqmYear.options(todayAlmaty));

        return "view-control";
    }

    /** The moves AccessPolicy lets a SoQM user make from this status, nearest first; empty for anyone else. */
    private List<WorkflowMoveOptionDTO> soqmMoves(ControlPermission permission, String status,
                                                  ControlAssignmentDTO assignment) {
        List<WorkflowMoveOptionDTO> options = new ArrayList<>();
        if (!permission.isSoqmLead()) {
            return options;
        }
        for (String target : AccessPolicy.soqmTargets(status)) {
            AccessPolicy.move(permission, status, target).ifPresent(move -> options.add(new WorkflowMoveOptionDTO(
                    move.to(),
                    WorkflowMove.displayStatus(move.to()),
                    move.label(),
                    move.isReturn(),
                    move.onBehalf(),
                    move.actingFor().getDisplayName(),
                    move.onBehalf() ? joinDisplayNames(WorkflowMoveService.peopleOf(move.actingFor(), assignment)) : null,
                    move.commentRequired())));
        }
        return options;
    }

    private ControlResponseDTO convertToResponseDTO(Control control) {
        ControlResponseDTO dto = new ControlResponseDTO();
        dto.setId(control.getId());
        dto.setControlId(control.getControlId());
        dto.setControlFrequency(control.getControlFrequency());
        dto.setControlCategory(control.getControlCategory()); // ВОЗВРАЩАЕМ
        dto.setControlType(control.getControlType());
        dto.setComponent(control.getComponent());
        dto.setOperatedBy(control.getOperatedBy());
        dto.setReferencesToControl(control.getReferencesToControl()); // ВОЗВРАЩАЕМ
        dto.setPriority(control.getPriority());
        dto.setNonAuditServicesApplicability(control.getNonAuditServicesApplicability());
        dto.setHomogeneity(control.getHomogeneity()); // ВОЗВРАЩАЕМ
        dto.setControlStatus(control.getControlStatus()); // НОВОЕ ПОЛЕ
        dto.setControlDescription(control.getControlDescription());
        dto.setPrp(control.getPrp());

        if (control.getCreatedBy() != null) {
            dto.setCreatedBy(control.getCreatedBy().getDisplayName());
        } else {
            dto.setCreatedBy("Unknown");
        }

        dto.setCreatedAt(control.getCreatedAt());
        dto.setUpdatedAt(control.getUpdatedAt());
        return dto;
    }
    /** The former Edit Control page; controls are edited on View Control, which checks access itself. */
    @GetMapping("/edit-control/{id}")
    public String editControl(@PathVariable Long id) {
        return "redirect:/view-control/" + id + "#control";
    }

    @GetMapping("/new-control")
    public String newControl(Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);
        // Only SoQM can create controls (POST /api/controls enforces the same rule)
        if (!AccessPolicy.canCreateControls(AccessPolicy.Subject.of(currentUser))) {
            return "redirect:/";
        }

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userEmail", currentUser.getMail());
        model.addAttribute("kdnIdHint", RoleDisplayMapper.KDN_ID_HINT);

        return "new-control";
    }

    /**
     * The Action Centre is a tab of the dashboard; this address only leads there (old bookmarks), or to the KDN
     * page for the role KDN, who has no Action Centre (AccessPolicy.KDN_SEES_DASHBOARD).
     */
    @GetMapping("/action-centre")
    public String actionCentre(HttpSession session) {
        User currentUser = getCurrentUser(session);
        if (currentUser != null && !AccessPolicy.seesDashboard(AccessPolicy.Subject.of(currentUser))) {
            return "redirect:" + KdnControlsOverview.CONTROLS_HREF;
        }
        return "redirect:/#action-centre";
    }

    @GetMapping("/performance-cycle/{controlId}")
    public String performanceCycle(@PathVariable Long controlId, Model model, HttpSession session,
                                   RedirectAttributes redirectAttributes) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);

        try {
            // 1. Получаем Control
            Control control = controlService.getControlById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found with id: " + controlId));

            if (permissionService.readAccess(control, currentUser) != AccessPolicy.ReadAccess.ALLOWED) {
                redirectAttributes.addFlashAttribute("accessDeniedMessage",
                        "Access revoked — you no longer have permission to view this control.");
                return "redirect:/component/ALL";
            }

            // 2. Получаем Performance DTO (built from Control + Assignment)
            PerformanceDTO performanceDTO = performanceService.buildPerformanceDTO(control);

            // 3. Получаем Assignment
            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(controlId);

            // 4. People: every assignee per role (display names), not only the first one
            String facilitator = joinDisplayNames(assignment.getFacilitator());
            String controlOperator = joinDisplayNames(assignment.getControlOperator());
            String soqmTeam = joinDisplayNames(assignment.getSoqmLead());
            String processOwner = joinDisplayNames(assignment.getProcessOwner());

            // 5. Real dates from the workflow history (was: "now" and the current user)
            List<WorkflowHistory> history = workflowHistoryRepository.findByControlIdOrderByCreatedAtDesc(controlId);
            if (history == null) {
                history = List.of();
            }
            LocalDateTime initiatedAt = history.stream()
                    .filter(h -> h.getActionType() == WorkflowActionType.INITIATE && h.getCreatedAt() != null)
                    .map(WorkflowHistory::getCreatedAt)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            WorkflowHistory lastAction = history.stream()
                    .filter(h -> h.getCreatedAt() != null)
                    .findFirst()
                    .orElse(null);
            LocalDateTime lastUpdatedOn = lastAction != null ? lastAction.getCreatedAt() : control.getUpdatedAt();
            String lastUpdatedBy = lastAction != null ? lastAction.getPerformedByName() : null;

            LocalDate deadline = DeadlineOverdue.deadlineOf(assignment.getControlOperationDeadline(), control.getDeadline());
            String status = normalizeStatus(control.getPerformanceStatus());
            boolean overdue = DeadlineOverdue.isOverdue(status, deadline, DeadlineOverdue.today(Instant.now()));

            // 6. Model
            model.addAttribute("userName", currentUser.getDisplayName());
            model.addAttribute("userEmail", currentUser.getMail());
            model.addAttribute("controlId", control.getControlId());
            model.addAttribute("control", control);

            model.addAttribute("soqmYear", performanceDTO.getSoqmYear());
            model.addAttribute("initiationDate", initiatedAt);
            model.addAttribute("operationDate", assignment.getControlOperationDate());
            model.addAttribute("deadline", deadline);
            model.addAttribute("overdue", overdue);
            model.addAttribute("performanceStatus", performanceDTO.getPerformanceStatus());
            model.addAttribute("workflowStepIndex", workflowStepIndex(status));
            model.addAttribute("facilitator", facilitator);
            model.addAttribute("controlOperator", controlOperator);
            model.addAttribute("soqmTeam", soqmTeam);
            model.addAttribute("processOwner", processOwner);
            model.addAttribute("lastUpdatedBy", lastUpdatedBy);
            model.addAttribute("lastUpdatedOn", lastUpdatedOn);
            model.addAttribute("historyRows", history.stream().map(this::toHistoryRow).toList());

            // The Excel of a completed control: SoQM or a user it is shared with
            model.addAttribute("canExportCompleted", permissionService.canExportCompletedControl(control, currentUser));

            return "performance-cycle";

        } catch (Exception e) {
            return "redirect:/view-control/" + controlId;
        }
    }

    /** One line of the workflow timeline on the Performance Cycle page. */
    public record HistoryRow(LocalDateTime at, String who, String action, String kind,
                             String fromStatus, String toStatus, String comment) {
    }

    private HistoryRow toHistoryRow(WorkflowHistory h) {
        String to = h.getToStep();
        String action;
        String kind;
        WorkflowActionType type = h.getActionType();
        if (type == null) {
            action = "Updated";
            kind = "other";
        } else {
            switch (type) {
                case INITIATE -> { action = "Initiated"; kind = "start"; }
                case SUBMIT_TO_OPERATOR -> { action = "Submitted to Control Operator"; kind = "forward"; }
                case SUBMIT_TO_SOQM_TEAM -> { action = "Submitted to SoQM Team"; kind = "forward"; }
                case SUBMIT_TO_PROCESS_OWNER -> { action = "Sent to Process Owner"; kind = "forward"; }
                case RETURN_TO_FACILITATOR -> { action = "Returned to Facilitator"; kind = "return"; }
                case RETURN_TO_OPERATOR -> { action = "Returned to Control Operator"; kind = "return"; }
                case RETURN, REJECT -> { action = "Returned"; kind = "return"; }
                case APPROVE -> {
                    boolean completed = "COMPLETED".equalsIgnoreCase(to);
                    action = completed ? "Completed" : "Approved";
                    kind = completed ? "done" : "forward";
                }
                case COMMENT -> { action = "Comment"; kind = "other"; }
                case REASSIGN -> { action = "Reassigned"; kind = "other"; }
                default -> { action = type.name(); kind = "other"; }
            }
        }
        String who = h.getPerformedByName() != null && !h.getPerformedByName().isBlank()
                ? h.getPerformedByName()
                : h.getPerformedByEmail();
        return new HistoryRow(h.getCreatedAt(), who, action, kind,
                statusDisplayMapper.display(h.getFromStep()), statusDisplayMapper.display(to), h.getComments());
    }

    /** Position in Facilitator -> Control Operator -> SoQM -> Process Owner -> Completed; -1 = not initiated. */
    private int workflowStepIndex(String status) {
        return switch (status) {
            case "IN_PROGRESS" -> 0;
            case "REVIEW" -> 1;
            case "SOQM_HEAD_REVIEW" -> 2;
            case "PROCESS_OWNER_REVIEW" -> 3;
            case "COMPLETED" -> 4;
            default -> -1;
        };
    }

    private String joinDisplayNames(List<String> emails) {
        if (emails == null || emails.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (String email : emails) {
            if (email == null || email.isBlank()) {
                continue;
            }
            names.add(userService.getUserByEmail(email.trim())
                    .map(User::getDisplayName)
                    .filter(name -> name != null && !name.isBlank())
                    .orElse(email.trim()));
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private List<NotificationGroupDTO> groupNotificationsByDate(List<NotificationItemDTO> notifications) {
        Map<String, List<NotificationItemDTO>> grouped = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Almaty"));
        LocalDate yesterday = today.minusDays(1);

        for (NotificationItemDTO notif : notifications) {
            String dateLabel = "";
            
            if (notif.getTimestamp() != null) {
                LocalDate notifDate = notif.getTimestamp().toLocalDate();
                
                if (notifDate.equals(today)) {
                    dateLabel = "Today";
                } else if (notifDate.equals(yesterday)) {
                    dateLabel = "Yesterday";
                } else {
                    // Includes the year so the same day of different years is not merged into one group
                    dateLabel = notif.getTimestamp().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
                }
            } else {
                dateLabel = "No date";
            }

            grouped.computeIfAbsent(dateLabel, k -> new ArrayList<>()).add(notif);
        }

        // Convert to list of groups maintaining order
        List<NotificationGroupDTO> groups = new ArrayList<>();
        for (Map.Entry<String, List<NotificationItemDTO>> entry : grouped.entrySet()) {
            groups.add(new NotificationGroupDTO(entry.getKey(), entry.getValue()));
        }
        
        return groups;
    }
}




