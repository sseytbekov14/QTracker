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
import com.kpmg.qtracker.util.StatusDisplayMapper;
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
        if (!transitionGuard.check(control, currentUser, permission, WorkflowTransition.INITIATE).allowed()) {
            return "redirect:/view-control/" + id;
        }

        List<InitiationReadiness.Item> items = InitiationReadiness.items(control, assignment);
        List<InitiationRow> rows = items.stream()
                .map(item -> new InitiationRow(item.label(), item.tab(), item.done(),
                        item.done() ? initiationValue(item.field(), control, assignment) : null))
                .toList();
        LocalDate todayAlmaty = DeadlineOverdue.today(Instant.now());

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getRole());
        model.addAttribute("userEmail", currentUser.getMail());
        model.addAttribute("userRole", currentUser.getRole());
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
        return "redirect:/controls";
    }

    @GetMapping("/")
    public String dashboard(@RequestParam(value = "notifLimit", required = false) Integer notifLimit,
                            Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);
        String userEmail = currentUser.getMail();
        String userRole = currentUser.getRole();
        AccessPolicy.Subject subject = AccessPolicy.Subject.of(currentUser);

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getRole());
        model.addAttribute("userEmail", userEmail);
        model.addAttribute("userRole", userRole);
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
        // Controls where the current workflow step belongs to this user; overdue first, then nearest deadline
        List<ControlResponseDTO> actionItems = allControls.stream()
                .filter(control -> isMyTurn(subject, userEmail, control))
                .sorted(Comparator.comparing(ControlResponseDTO::isOverdue).reversed()
                        .thenComparing(ControlResponseDTO::getDeadline,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
        model.addAttribute("actionItems", actionItems.stream().limit(DASHBOARD_ACTION_ITEMS_LIMIT).toList());
        model.addAttribute("actionItemsTotal", actionItems.size());
        model.addAttribute("actionItemsOverdue", actionItems.stream().filter(ControlResponseDTO::isOverdue).count());

        // ===== ACTION CENTRE DATA =====
        addComponentSummaries(model, allControls);

        // ===== NOTIFICATIONS DATA =====
        // Newest first, NOTIFICATIONS_PAGE_SIZE at a time ("Show more" raises notifLimit).
        // The limit is applied before mapping because each item loads its control.
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

        return "dashboard";
    }

    @GetMapping("/controls")
    public String controls(@RequestParam(value = "scope", required = false) String scope,
                           @RequestParam(value = "status", required = false) String status,
                           @RequestParam(value = "filter", required = false) String filter,
                           @RequestParam(value = "component", required = false) String component,
                           Model model,
                           HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);
        String userRole = currentUser.getRole();
        String userEmail = currentUser.getMail();
        AccessPolicy.Subject subject = AccessPolicy.Subject.of(currentUser);
        boolean soqm = AccessPolicy.isSoqm(subject);
        // SoQM, admins and scope ALL: "active" = not completed; everyone else: the controls waiting for them
        boolean seesAll = AccessPolicy.seesAllControls(subject);
        String normalizedScope = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        String normalizedStatus = status == null ? "" : status.trim();
        String normalizedFilter = filter == null ? "" : filter.trim();
        if ("ALL".equalsIgnoreCase(normalizedFilter)) {
            normalizedFilter = "";
        }
        boolean defaultAllControls =
                normalizedScope.isBlank() && normalizedStatus.isBlank() && normalizedFilter.isBlank();
        boolean overdueFilter = "OVERDUE".equalsIgnoreCase(normalizedFilter);
        boolean completedFilter = "COMPLETED".equalsIgnoreCase(normalizedFilter);
        if ("OVERDUE".equalsIgnoreCase(normalizedStatus)) {
            overdueFilter = true;
            normalizedStatus = "";
        }
        String statusFilter = "";
        String effectiveScope;
        if (normalizedScope.isBlank()) {
            if (defaultAllControls) {
                effectiveScope = "all";
            } else if (soqm) {
                effectiveScope = "all";
            } else {
                effectiveScope = seesAll ? "active" : "mine";
            }
        } else {
            effectiveScope = normalizedScope;
        }
        if (!seesAll) {
            if (!"active".equals(effectiveScope) && !"all".equals(effectiveScope)) {
                effectiveScope = "active";
            }
        } else {
            // SoQM / admin / scope ALL: "active" = not completed (was silently turned into "all",
            // so the dashboard's Active link showed every control)
            if (!"all".equals(effectiveScope) && !"active".equals(effectiveScope)) {
                effectiveScope = "all";
            }
        }
        if (completedFilter && !seesAll) {
            effectiveScope = "all";
        }
        
        // Apply status filter for all users (not just SOQM_TEAM)
        if (!normalizedStatus.isBlank()) {
            String upperStatus = normalizedStatus.toUpperCase(Locale.ROOT);
            Set<String> allowedStatuses = Set.of(
                    "DRAFT",
                    "IN_PROGRESS",
                    "REVIEW",
                    "SOQM_HEAD_REVIEW",
                    "PROCESS_OWNER_REVIEW",
                    "COMPLETED"
            );
            if (allowedStatuses.contains(upperStatus)) {
                statusFilter = upperStatus;
                System.out.println("✅ Status filter set to: " + statusFilter + " (normalizedStatus=" + normalizedStatus + ")");
            } else {
                System.out.println("❌ Status '" + upperStatus + "' not in allowed list");
            }
        } else {
            System.out.println("ℹ️ normalizedStatus is blank");
        }

        List<ControlResponseDTO> userControlsList = findControlsVisibleToUser(currentUser);
        String componentFilter = resolveComponentCode(component);
        if (componentFilter != null) {
            userControlsList = userControlsList.stream()
                    .filter(control -> componentFilter.equalsIgnoreCase(control.getComponent()))
                    .collect(Collectors.toList());
        }
        Map<Long, LocalDateTime> completionTimeByControlId = resolveCompletionTimes(userControlsList);
        // Most recently updated (or created) first; controls with neither date count as the oldest
        userControlsList.sort(Comparator.comparing(
                (ControlResponseDTO control) -> control.getUpdatedAt() != null ? control.getUpdatedAt() : control.getCreatedAt(),
                Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())));

        LocalDate todayAlmaty = DeadlineOverdue.today(Instant.now());
        if (overdueFilter) {
            userControlsList = userControlsList.stream()
                    .filter(control -> DeadlineOverdue.isOverdue(control.getPerformanceStatus(), control.getDeadline(), todayAlmaty))
                    .collect(Collectors.toList());
            System.out.println("controls filter scope=overdue user=" + userEmail
                    + " count=" + userControlsList.size());
        } else {
            if ("active".equals(effectiveScope)) {
                int beforeCount = userControlsList.size();
                // Only apply active queue filter if NO status filter is specified
                if (statusFilter.isBlank()) {
                        if (seesAll) {
                        userControlsList = userControlsList.stream()
                                .filter(control -> control.getPerformanceStatus() == null
                                        || !"COMPLETED".equalsIgnoreCase(control.getPerformanceStatus()))
                                .collect(Collectors.toList());
                    } else {
                        userControlsList = userControlsList.stream()
                                .filter(control -> isMyTurn(subject, userEmail, control))
                                .collect(Collectors.toList());
                    }
                }
                System.out.println("controls filter scope=active user=" + userEmail
                        + " before=" + beforeCount + " after=" + userControlsList.size());
            } else {
                System.out.println("controls filter scope=all user=" + userEmail
                        + " count=" + userControlsList.size());
            }
            // Apply status filter for all users
            if (!statusFilter.isBlank()) {
                String filterValue = statusFilter;
                System.out.println("🔍 Applying statusFilter='" + filterValue + "' to " + userControlsList.size() + " controls");
                System.out.println("   Control statuses before filter:");
                for (ControlResponseDTO c : userControlsList) {
                    String normalized = normalizeStatus(c.getPerformanceStatus());
                    System.out.println("      - " + c.getControlId() + ": raw='" + c.getPerformanceStatus() + "' normalized='" + normalized + "'");
                }
                userControlsList = userControlsList.stream()
                        .filter(control -> {
                            String controlStatus = normalizeStatus(control.getPerformanceStatus());
                            boolean matches = filterValue.equals(controlStatus);
                            if (matches) {
                                System.out.println("   ✅ Control " + control.getControlId() + " status=" + controlStatus + " matches");
                            }
                            return matches;
                        })
                        .collect(Collectors.toList());
                System.out.println("🔍 After statusFilter: " + userControlsList.size() + " controls remain");
            }
            if (completedFilter) {
                userControlsList = userControlsList.stream()
                        .filter(control -> "COMPLETED".equals(normalizeStatus(control.getPerformanceStatus())))
                        .collect(Collectors.toList());
            }
        }
        
        for (ControlResponseDTO control : userControlsList) {
            control.setOverdue(DeadlineOverdue.isOverdue(control.getPerformanceStatus(), control.getDeadline(), todayAlmaty));
            control.setClosedLate(DeadlineOverdue.isClosedLate(control.getPerformanceStatus(), control.getDeadline(),
                    completedOn(control, completionTimeByControlId)));
        }

        ControlCounters counters = countControlsVisibleToUser(userControlsList);

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getRole());
        model.addAttribute("userEmail", userEmail);
        model.addAttribute("userRole", userRole);
        model.addAttribute("userSeesAll", seesAll);
        model.addAttribute("canExportAll", AccessPolicy.canExportAllControls(subject));
        String resolvedControlsFilter = effectiveScope;
        if (overdueFilter && !soqm) {
            resolvedControlsFilter = "overdue";
        } else if (completedFilter && !soqm) {
            resolvedControlsFilter = "completed";
        }
        model.addAttribute("controlsFilter", resolvedControlsFilter);
        String resolvedStatusFilter = statusFilter;
        if (overdueFilter) {
            resolvedStatusFilter = "OVERDUE";
        } else if (completedFilter) {
            resolvedStatusFilter = "COMPLETED";
        }
        model.addAttribute("statusFilter", resolvedStatusFilter);
        model.addAttribute("componentFilter", componentFilter);
        model.addAttribute("componentFilterName", componentFilter != null ? COMPONENT_NAMES.get(componentFilter) : null);
        addControlsFilterLinks(model, resolvedStatusFilter, resolvedControlsFilter, componentFilter);
        model.addAttribute("controls", userControlsList);
        // Controls where the current workflow step is this user's ("Your turn" badge)
        model.addAttribute("actionControlIds", userControlsList.stream()
                .filter(control -> isMyTurn(subject, userEmail, control))
                .map(ControlResponseDTO::getId)
                .collect(Collectors.toSet()));
        // Not overdue yet, but the deadline is within the next 3 days (Almaty date)
        model.addAttribute("dueSoonControlIds", userControlsList.stream()
                .filter(control -> DeadlineOverdue.isDueSoon(control.getPerformanceStatus(), control.getDeadline(),
                        todayAlmaty, DUE_SOON_DAYS))
                .map(ControlResponseDTO::getId)
                .collect(Collectors.toSet()));
        model.addAttribute("totalControls", counters.total());
        model.addAttribute("activeControls", counters.active());
        model.addAttribute("completedControls", counters.completed());
        model.addAttribute("overdueControls", counters.overdue());
        model.addAttribute("unreadNotifications", getUnreadCount(currentUser));

        return "controls";
    }

    private List<ControlResponseDTO> findControlsVisibleToUser(User currentUser) {
        if (currentUser == null) {
            return new ArrayList<>();
        }
        String userEmail = currentUser.getMail();
        boolean seesAll = AccessPolicy.seesAllControls(AccessPolicy.Subject.of(currentUser));
        List<Control> visibleControls = controlService.findVisibleControlsForUser(currentUser);
        Map<Long, ControlResponseDTO> controlMap = new LinkedHashMap<>();
        for (Control control : visibleControls) {
            if (control == null || control.getId() == null) {
                continue;
            }
            ControlResponseDTO dto = controlService.convertToResponseDTO(control);
            boolean sharedOnly = !seesAll
                    && isSharedWithUser(control.getId(), userEmail)
                    && !isDirectlyAssignedToUser(dto, userEmail);
            dto.setSharedViewOnly(sharedOnly);
            controlMap.put(control.getId(), dto);
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

    /** A filter link on the Controls page (status chip / component option). */
    public record FilterLink(String label, String href, boolean active) {
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

    /** /controls URL with an optional status (or filter=OVERDUE/COMPLETED / scope=active) and component. */
    private String controlsUrl(String statusKey, String value, String component) {
        org.springframework.web.util.UriComponentsBuilder builder =
                org.springframework.web.util.UriComponentsBuilder.fromPath("/controls");
        if (statusKey != null && value != null) {
            builder.queryParam(statusKey, value);
        }
        if (component != null) {
            builder.queryParam("component", component);
        }
        // build().encode() escapes "&" inside values (component "A&C")
        return builder.build().encode().toUriString();
    }

    private void addControlsFilterLinks(Model model, String statusFilter, String controlsFilter,
                                        String component) {
        String status = statusFilter == null ? "" : statusFilter;
        boolean activeScope = "active".equals(controlsFilter);
        boolean all = status.isEmpty() && !activeScope;

        List<FilterLink> chips = new ArrayList<>();
        chips.add(new FilterLink("All", controlsUrl(null, null, component), all));
        // Everyone may see drafts: the ones they work on, or all of them with scope ALL
        chips.add(new FilterLink("Draft", controlsUrl("status", "DRAFT", component), "DRAFT".equals(status)));
        chips.add(new FilterLink("In Progress", controlsUrl("status", "IN_PROGRESS", component), "IN_PROGRESS".equals(status)));
        chips.add(new FilterLink("Review", controlsUrl("status", "REVIEW", component), "REVIEW".equals(status)));
        chips.add(new FilterLink("SoQM Review", controlsUrl("status", "SOQM_HEAD_REVIEW", component), "SOQM_HEAD_REVIEW".equals(status)));
        chips.add(new FilterLink("Process Owner Review", controlsUrl("status", "PROCESS_OWNER_REVIEW", component), "PROCESS_OWNER_REVIEW".equals(status)));
        chips.add(new FilterLink("Completed", controlsUrl("filter", "COMPLETED", component), "COMPLETED".equals(status)));
        chips.add(new FilterLink("Overdue", controlsUrl("filter", "OVERDUE", component), "OVERDUE".equals(status)));
        model.addAttribute("statusChips", chips);

        model.addAttribute("statTotalLink", new FilterLink("Total", controlsUrl(null, null, component), all));
        model.addAttribute("statActiveLink", new FilterLink("Active", controlsUrl("scope", "active", component),
                status.isEmpty() && activeScope));
        model.addAttribute("statCompletedLink", new FilterLink("Completed", controlsUrl("filter", "COMPLETED", component),
                "COMPLETED".equals(status)));
        model.addAttribute("statOverdueLink", new FilterLink("Overdue", controlsUrl("filter", "OVERDUE", component),
                "OVERDUE".equals(status)));

        // Component select keeps the current status filter
        String statusKey = null;
        String statusValue = null;
        if ("OVERDUE".equals(status) || "COMPLETED".equals(status)) {
            statusKey = "filter";
            statusValue = status;
        } else if (!status.isEmpty()) {
            statusKey = "status";
            statusValue = status;
        } else if (activeScope) {
            statusKey = "scope";
            statusValue = "active";
        }
        List<FilterLink> components = new ArrayList<>();
        components.add(new FilterLink("All components", controlsUrl(statusKey, statusValue, null), component == null));
        for (Map.Entry<String, String> entry : COMPONENT_NAMES.entrySet()) {
            components.add(new FilterLink(entry.getValue() + " (" + entry.getKey() + ")",
                    controlsUrl(statusKey, statusValue, entry.getKey()), entry.getKey().equals(component)));
        }
        model.addAttribute("componentOptions", components);
    }

    /** Per-component breakdown for the Action Centre cards. */
    public record ComponentSummary(String code, String name, long total, long active, long overdue, long completed) {
        public int completedPercent() {
            return total == 0 ? 0 : (int) Math.round(completed * 100.0 / total);
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

    private void addComponentSummaries(Model model, List<ControlResponseDTO> controls) {
        List<ComponentSummary> summaries = new ArrayList<>();
        long allTotal = 0, allOverdue = 0, allCompleted = 0;
        for (Map.Entry<String, String> component : COMPONENT_NAMES.entrySet()) {
            long total = 0, overdue = 0, completed = 0;
            for (ControlResponseDTO control : controls) {
                if (!component.getKey().equals(control.getComponent())) {
                    continue;
                }
                total++;
                if (DeadlineOverdue.isCompleted(control.getPerformanceStatus())) {
                    completed++;
                } else if (control.isOverdue()) {
                    overdue++;
                }
            }
            summaries.add(new ComponentSummary(component.getKey(), component.getValue(),
                    total, total - overdue - completed, overdue, completed));
            allTotal += total;
            allOverdue += overdue;
            allCompleted += completed;
        }
        model.addAttribute("componentSummaries", summaries);
        model.addAttribute("componentSummaryAll", new ComponentSummary("All", "All components",
                allTotal, allTotal - allOverdue - allCompleted, allOverdue, allCompleted));
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

    @GetMapping("/notifications")
    public String notifications(HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        return "redirect:/#notifications";
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

        User currentUser = getCurrentUser(session);
        notificationService.markAllAsRead(currentUser.getId());

        return "redirect:/#notifications";
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

    @GetMapping("/component/{componentName}")
    public String controlsByComponent(@PathVariable String componentName, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;
        // The component page is the Controls list filtered by component
        String code = resolveComponentCode(componentName);
        return "redirect:" + (code == null ? "/controls" : controlsUrl(null, null, code));
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
        model.addAttribute("userTitle", currentUser.getRole());
        model.addAttribute("userEmail", userEmail);
        model.addAttribute("userRole", currentUser.getRole());
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

        boolean hasSharedSubmitted = false;
        if (permission.isSharedViewer()) {
            hasSharedSubmitted = workflowHistoryRepository.hasSharedSubmitted(id, userEmail);
        }
        model.addAttribute("hasSharedSubmitted", hasSharedSubmitted);

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

        // A draft links to its Initiate page for whoever the server lets initiate it
        model.addAttribute("canInitiate",
                transitionGuard.check(control, currentUser, permission, WorkflowTransition.INITIATE).allowed());
        // SoQM Year choices for the Control tab
        model.addAttribute("soqmYearOptions", SoqmYear.options(todayAlmaty));

        return "view-control";
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
        model.addAttribute("userTitle", currentUser.getRole());
        model.addAttribute("userEmail", currentUser.getMail());

        return "new-control";
    }

    /** The Action Centre is a tab of the dashboard; this address only leads there (old bookmarks). */
    @GetMapping("/action-centre")
    public String actionCentre() {
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
                return "redirect:/controls";
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
            model.addAttribute("userTitle", currentUser.getRole());
            model.addAttribute("userEmail", currentUser.getMail());
            model.addAttribute("userRole", currentUser.getRole());
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

            // Check if current user is a shared viewer
            boolean isShared = assignment.getControlSharedWith() != null
                    && assignment.getControlSharedWith().stream()
                        .anyMatch(e -> e != null && e.equalsIgnoreCase(currentUser.getMail()));
            model.addAttribute("isShared", isShared);

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




