package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.*;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.exception.ControlNotAvailableException;
import com.kpmg.qtracker.exception.ForbiddenException;
import com.kpmg.qtracker.exception.ResourceNotFoundException;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDocumentsRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.repository.WorkflowStepRepository;
import com.kpmg.qtracker.service.*;
import com.kpmg.qtracker.util.NotificationTypeDisplayMapper;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.kpmg.qtracker.service.WorkflowService;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ViewController {
    private final UserService userService;
    private final IControlService controlService;
    private final IPerformanceService performanceService;
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
    private final ControlPermissionService controlPermissionService;

    private User getCurrentUser(HttpSession session) {
        return (User) session.getAttribute("currentUser");
    }

    private String checkAuthAndRedirect(HttpSession session) {
        if (getCurrentUser(session) == null) {
            return "redirect:/login";
        }
        return null;
    }

    @GetMapping("/workflow/approvals")
    public String myApprovals(Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);

        // Получаем ожидающие апрувы
        List<Control> pendingControls = workflowService.getPendingApprovals(currentUser.getMail());

        // Конвертируем в DTO
        List<PendingApprovalDTO> pendingApprovals = pendingControls.stream()
                .map(control -> {
                    PendingApprovalDTO dto = new PendingApprovalDTO();
                    dto.setControlId(control.getId());
                    dto.setControlIdNumber(control.getControlId());
                    dto.setComponent(control.getComponent());
                    dto.setControlType(control.getControlType());
                    dto.setControlDescription(control.getControlDescription());

                    // Получаем информацию о текущем шаге
                    WorkflowStepDTO currentStep = workflowService.getCurrentStep(control.getId());
                    if (currentStep != null) {
                        dto.setCurrentStep(currentStep.getStepType().name());
                        dto.setStepDisplayName(currentStep.getStatus().getDisplayName());
                        dto.setAssignedAt(currentStep.getAssignedAt());
                        dto.setAssignedToName(currentStep.getAssignedToName());
                    }

                    return dto;
                })
                .collect(Collectors.toList());

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", currentUser.getMail());
        model.addAttribute("pendingApprovals", pendingApprovals);

        return "workflow/approvals";
    }

    @GetMapping("/performance/{controlId}")
    public String performanceChecklist(@PathVariable Long controlId, Model model, HttpSession session,
                                       RedirectAttributes redirectAttributes) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);

        Control control = controlService.getControlById(controlId)
                .orElseThrow(() -> new RuntimeException("Control not found with id: " + controlId));

        if (!canViewControl(controlId, control, currentUser)) {
            redirectAttributes.addFlashAttribute("accessDeniedMessage",
                    "Access revoked — you no longer have permission to view this control.");
            return "redirect:/controls";
        }
        String performanceStatus = control.getPerformanceStatus();
        if (performanceStatus == null || performanceStatus.isBlank()) {
            performanceStatus = "DRAFT";
        }

        // Получаем данные из Assignment
        ControlAssignmentDTO assignmentDTO = controlAssignmentService.getAssignmentByControlId(controlId);

        // Получаем данные Performance (built from Control + Assignment, no separate table)
        PerformanceDTO performanceDTO = performanceService.buildPerformanceDTO(control);

        // Если Assigned To все еще пустое или "Not assigned", устанавливаем дефолтное значение
        if (performanceDTO.getAssignedTo() == null || performanceDTO.getAssignedTo().isEmpty() ||
                performanceDTO.getAssignedTo().equals("0")) {
            performanceDTO.setAssignedTo("Not assigned");
        }

        performanceDTO.setControlId(controlId);

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", currentUser.getMail());
        model.addAttribute("control", control);
        model.addAttribute("performance", performanceDTO);
        model.addAttribute("assignment", assignmentDTO); // Добавляем assignment в модель
        model.addAttribute("performanceStatus", performanceStatus);

        return "performance-checklist";
    }

    @GetMapping("/")
    public String dashboard(Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);
        String userEmail = currentUser.getMail();
        String userRole = currentUser.getRole();

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", userEmail);
        model.addAttribute("userRole", userRole);

        // Unread notifications badge
        model.addAttribute("unreadNotifications", getUnreadCount(currentUser));

        List<ControlResponseDTO> allControls = findControlsVisibleToUser(currentUser);
        Map<Long, LocalDateTime> completionTimeByControlId = resolveCompletionTimes(allControls);
        LocalDate todayAlmaty = LocalDate.now(ZoneId.of("Asia/Almaty"));
        for (ControlResponseDTO control : allControls) {
            control.setOverdue(isOverdue(control, todayAlmaty, completionTimeByControlId));
        }
        boolean hideDraftControls = !isGlobalVisibilityRole(userRole);
        ControlCounters dashboardCounters = countControlsVisibleToUser(
                allControls,
                hideDraftControls,
                completionTimeByControlId
        );

        model.addAttribute("totalControls", dashboardCounters.total());
        model.addAttribute("activeControls", dashboardCounters.active());
        model.addAttribute("completedControls", dashboardCounters.completed());
        model.addAttribute("overdueControls", dashboardCounters.overdue());

        model.addAttribute("recentControls", allControls.stream()
                .sorted((c1, c2) -> {
                    if (c1.getCreatedAt() == null || c2.getCreatedAt() == null) return 0;
                    return c2.getCreatedAt().compareTo(c1.getCreatedAt());
                })
                .limit(5)
                .collect(Collectors.toList()));

        // ===== ACTION CENTRE DATA =====
        List<ControlResponseDTO> controlsForAction = allControls;
        Map<String, Long> componentStats = new HashMap<>();
        String[] allComponentNames = {"HR", "INTR", "M&R", "RAP", "A&C", "I&C", "GOV", "EP", "RER", "TECHR"};
        for (String c : allComponentNames) {
            componentStats.put(c, 0L);
        }

        if (isSoqmRole(userRole)) {
            for (ControlResponseDTO ctrl : controlsForAction) {
                String comp = ctrl.getComponent();
                if (comp != null && !comp.trim().isEmpty() && componentStats.containsKey(comp)) {
                    componentStats.put(comp, componentStats.get(comp) + 1);
                }
            }
            componentStats.put("All", (long) controlsForAction.size());
        } else {
            long nonDraftCount = 0L;
            for (ControlResponseDTO ctrl : controlsForAction) {
                String ctrlStatus = normalizeStatus(ctrl.getPerformanceStatus());
                if ("DRAFT".equals(ctrlStatus)) continue;
                nonDraftCount++;
                String comp = ctrl.getComponent();
                if (comp != null && !comp.trim().isEmpty() && componentStats.containsKey(comp)) {
                    componentStats.put(comp, componentStats.get(comp) + 1);
                }
            }
            componentStats.put("All", nonDraftCount);
        }
        model.addAttribute("componentStats", componentStats);

        // ===== NOTIFICATIONS DATA =====
        List<com.kpmg.qtracker.entity.Notification> dbNotifications =
                notificationService.getUserNotifications(currentUser.getId());
        List<NotificationItemDTO> notifications = dbNotifications.stream()
                .filter(notif -> !notificationTypeDisplayMapper.isHiddenType(notif.getType()))
                .map(this::convertNotificationToDTO)
                .collect(Collectors.toList());
        session.setAttribute("cachedNotifications", notifications);
        List<NotificationGroupDTO> groupedNotifications = groupNotificationsByDate(notifications);
        model.addAttribute("notificationGroups", groupedNotifications);

        return "dashboard";
    }

    @GetMapping("/controls")
    public String controls(@RequestParam(value = "scope", required = false) String scope,
                           @RequestParam(value = "status", required = false) String status,
                           @RequestParam(value = "filter", required = false) String filter,
                           Model model,
                           HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);
        String userRole = currentUser.getRole();
        String userEmail = currentUser.getMail();
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
            } else if (isSoqmRole(userRole)) {
                effectiveScope = "all";
            } else {
                effectiveScope = "ADMIN".equals(userRole) ? "active" : "mine";
            }
        } else {
            effectiveScope = normalizedScope;
        }
        if (!isSoqmRole(userRole)) {
            if (!"active".equals(effectiveScope) && !"all".equals(effectiveScope)) {
                effectiveScope = "active";
            }
        } else {
            if (!"all".equals(effectiveScope)) {
                effectiveScope = "all";
            }
        }
        if (completedFilter && !isSoqmRole(userRole)) {
            effectiveScope = "all";
        }
        
        // Apply status filter for all users (not just SOQM_LEAD)
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
        Map<Long, LocalDateTime> completionTimeByControlId = resolveCompletionTimes(userControlsList);
        // Sort by updated date in descending order (most recently updated first)
        userControlsList.sort((c1, c2) -> {
            LocalDateTime date1 = c1.getUpdatedAt() != null ? c1.getUpdatedAt() : c1.getCreatedAt();
            LocalDateTime date2 = c2.getUpdatedAt() != null ? c2.getUpdatedAt() : c2.getCreatedAt();
            return date2.compareTo(date1);
        });

        LocalDate todayAlmaty = LocalDate.now(ZoneId.of("Asia/Almaty"));
        if (overdueFilter) {
            userControlsList = userControlsList.stream()
                    .filter(control -> isOverdue(control, todayAlmaty, completionTimeByControlId))
                    .collect(Collectors.toList());
            System.out.println("controls filter scope=overdue user=" + userEmail
                    + " count=" + userControlsList.size());
        } else {
            if ("active".equals(effectiveScope)) {
                int beforeCount = userControlsList.size();
                // Only apply active queue filter if NO status filter is specified
                if (statusFilter.isBlank()) {
                    if (isGlobalVisibilityRole(userRole)) {
                        userControlsList = userControlsList.stream()
                                .filter(control -> control.getPerformanceStatus() == null
                                        || !"COMPLETED".equalsIgnoreCase(control.getPerformanceStatus()))
                                .collect(Collectors.toList());
                    } else {
                        userControlsList = userControlsList.stream()
                                .filter(control -> isActiveQueueForUser(control, userEmail))
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
        
        // For non-admin/non-SOQM users, hide DRAFT controls in the controls list.
        if (!isGlobalVisibilityRole(userRole)) {
            userControlsList = userControlsList.stream()
                    .filter(control -> !"DRAFT".equals(normalizeStatus(control.getPerformanceStatus())))
                    .collect(Collectors.toList());
        }

        for (ControlResponseDTO control : userControlsList) {
            control.setOverdue(isOverdue(control, todayAlmaty, completionTimeByControlId));
        }

        ControlCounters counters = countControlsVisibleToUser(userControlsList, false, completionTimeByControlId);

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", userEmail);
        model.addAttribute("userRole", userRole);
        model.addAttribute("userIsAdmin", isAdminRole(userRole));
        model.addAttribute("userIsSoqm", isSoqmRole(userRole));
        String resolvedControlsFilter = effectiveScope;
        if (overdueFilter && !isSoqmRole(userRole)) {
            resolvedControlsFilter = "overdue";
        } else if (completedFilter && !isSoqmRole(userRole)) {
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
        model.addAttribute("controls", userControlsList);
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
        String userRole = currentUser.getRole();
        String userEmail = currentUser.getMail();
        List<Control> visibleControls = controlService.findVisibleControlsForUser(userEmail, userRole);
        Map<Long, ControlResponseDTO> controlMap = new LinkedHashMap<>();
        for (Control control : visibleControls) {
            if (control == null || control.getId() == null) {
                continue;
            }
            ControlResponseDTO dto = controlService.convertToResponseDTO(control);
            boolean sharedOnly = !isGlobalVisibilityRole(userRole)
                    && isSharedWithUser(control.getId(), userEmail)
                    && !isDirectlyAssignedToUser(dto, userEmail);
            dto.setSharedViewOnly(sharedOnly);
            controlMap.put(control.getId(), dto);
        }
        return new ArrayList<>(controlMap.values());
    }

    private ControlCounters countControlsVisibleToUser(List<ControlResponseDTO> controls, boolean hideDraftControls) {
        return countControlsVisibleToUser(controls, hideDraftControls, resolveCompletionTimes(controls));
    }

    private ControlCounters countControlsVisibleToUser(List<ControlResponseDTO> controls,
                                                       boolean hideDraftControls,
                                                       Map<Long, LocalDateTime> completionTimeByControlId) {
        List<ControlResponseDTO> base = controls == null ? new ArrayList<>() : new ArrayList<>(controls);
        if (hideDraftControls) {
            base = base.stream()
                    .filter(control -> !"DRAFT".equals(normalizeStatus(control.getPerformanceStatus())))
                    .collect(Collectors.toList());
        }
        LocalDate todayAlmaty = LocalDate.now(ZoneId.of("Asia/Almaty"));
        int totalControls = base.size();
        int completedControls = (int) base.stream()
                .filter(control -> "COMPLETED".equals(normalizeStatus(control.getPerformanceStatus())))
                .count();
        int activeControls = totalControls - completedControls;
        int overdueControls = (int) base.stream()
                .filter(control -> isOverdue(control, todayAlmaty, completionTimeByControlId))
                .count();
        return new ControlCounters(totalControls, activeControls, completedControls, overdueControls);
    }

    private boolean isAdminRole(String userRole) {
        return userRole != null && "ADMIN".equalsIgnoreCase(userRole.trim());
    }

    private boolean isSoqmRole(String userRole) {
        if (userRole == null) {
            return false;
        }
        String normalized = userRole.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return normalized.startsWith("SOQM");
    }

    private boolean isGlobalVisibilityRole(String userRole) {
        return isAdminRole(userRole) || isSoqmRole(userRole);
    }

    private record ControlCounters(int total, int active, int completed, int overdue) {
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "DRAFT";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isOverdue(ControlResponseDTO control,
                              LocalDate today,
                              Map<Long, LocalDateTime> completionTimeByControlId) {
        if (control == null || today == null) {
            return false;
        }
        LocalDate deadline = control.getDeadline();
        if (deadline == null) {
            return false;
        }
        String status = normalizeStatus(control.getPerformanceStatus());
        if ("COMPLETED".equals(status)) {
            LocalDate completedDate = resolveCompletedDate(control, completionTimeByControlId);
            return completedDate != null && completedDate.isAfter(deadline);
        }
        return deadline.isBefore(today);
    }

    private LocalDate resolveCompletedDate(ControlResponseDTO control,
                                           Map<Long, LocalDateTime> completionTimeByControlId) {
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

    private boolean isActiveQueueForUser(ControlResponseDTO control, String userEmail) {
        if (control == null || userEmail == null) {
            return false;
        }
        String status = normalizeStatus(control.getPerformanceStatus());
        if ("COMPLETED".equals(status) || "DRAFT".equals(status)) {
            return false;
        }
        if ("IN_PROGRESS".equals(status)) {
            return listContains(control.getFacilitators(), userEmail);
        }
        if ("REVIEW".equals(status)) {
            return listContains(control.getControlOperators(), userEmail);
        }
        if ("SOQM_HEAD_REVIEW".equals(status)) {
            return listContains(control.getSoqmLeads(), userEmail);
        }
        if ("PROCESS_OWNER_REVIEW".equals(status)) {
            return listContains(control.getProcessOwners(), userEmail);
        }
        return false;
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

    /**
     * Unified access check: can this user view the given control?
     */
    private boolean canViewControl(Long controlId, Control control, User user) {
        return controlPermissionService.resolve(control, user).canView();
    }

    @GetMapping("/notifications")
    public String notifications(Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", currentUser.getMail());

        // Get notifications from database
        List<com.kpmg.qtracker.entity.Notification> dbNotifications =
                notificationService.getUserNotifications(currentUser.getId());

        // Convert to DTO
        List<NotificationItemDTO> notifications = dbNotifications.stream()
                .filter(notif -> !notificationTypeDisplayMapper.isHiddenType(notif.getType()))
                .map(this::convertNotificationToDTO)
                .collect(Collectors.toList());
        
        // Cache notifications in session so detail view can retrieve by ID
        session.setAttribute("cachedNotifications", notifications);

        model.addAttribute("unreadNotifications", getUnreadCount(currentUser));

        // Group notifications by date
        List<NotificationGroupDTO> groupedNotifications = groupNotificationsByDate(notifications);
        model.addAttribute("notificationGroups", groupedNotifications);

        return "notifications";
    }

    @GetMapping("/notification/{notificationId}")
    public String notificationDetail(@PathVariable String notificationId, Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", currentUser.getMail());

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
            
        } catch (NumberFormatException e) {
            model.addAttribute("error", "Invalid notification ID");
            return "notification-detail";
        }

        model.addAttribute("unreadNotifications", getUnreadCount(currentUser));

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
        dto.setBy("System"); // can enhance to store actual user name
        dto.setTimestamp(notif.getCreatedAt());
        dto.setRead(notif.getIsRead());
        dto.setAttachments(new ArrayList<>());
        applyNotificationDisplay(dto);
        
        return dto;
    }

    @GetMapping("/component/{componentName}")
    public String controlsByComponent(@PathVariable String componentName, Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);
        String userRole = currentUser.getRole();
        List<ControlResponseDTO> visibleControls = findControlsVisibleToUser(currentUser);
        List<ControlResponseDTO> controlDTOs = visibleControls.stream()
                .filter(control -> "All".equalsIgnoreCase(componentName)
                        || (control.getComponent() != null && componentName.equalsIgnoreCase(control.getComponent())))
                .sorted((c1, c2) -> c2.getId().compareTo(c1.getId()))
                .collect(Collectors.toList());
        Map<Long, LocalDateTime> completionTimeByControlId = resolveCompletionTimes(controlDTOs);

        boolean hideDraftControls = !isGlobalVisibilityRole(userRole);
        ControlCounters counters = countControlsVisibleToUser(controlDTOs, hideDraftControls, completionTimeByControlId);
        if (hideDraftControls) {
            controlDTOs = controlDTOs.stream()
                    .filter(control -> !"DRAFT".equals(normalizeStatus(control.getPerformanceStatus())))
                    .collect(Collectors.toList());
        }
        LocalDate todayAlmaty = LocalDate.now(ZoneId.of("Asia/Almaty"));
        for (ControlResponseDTO control : controlDTOs) {
            control.setOverdue(isOverdue(control, todayAlmaty, completionTimeByControlId));
        }

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", currentUser.getMail());
        model.addAttribute("controls", controlDTOs);
        model.addAttribute("currentComponent", componentName);
        model.addAttribute("totalControls", counters.total());
        model.addAttribute("activeControls", counters.active());
        model.addAttribute("completedControls", counters.completed());
        model.addAttribute("overdueControls", counters.overdue());

        return "component-controls";
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

        if (!permission.canView()) {
            throw new ForbiddenException("You do not have permission to view this control.");
        }

        String performanceStatus = control.getPerformanceStatus();
        if (performanceStatus == null || performanceStatus.isEmpty()) {
            performanceStatus = "DRAFT";
        }
        if ("DRAFT".equals(normalizeStatus(performanceStatus))
                && permissionService.isSharedOnly(control, currentUser, permission)) {
            throw new ControlNotAvailableException(
                    "This control is still in Draft and has not been initiated into the workflow. "
                            + "You will get access after it is initiated."
            );
        }

        String userEmail = currentUser.getMail();
        boolean readOnly = !permission.canEdit();

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", userEmail);
        model.addAttribute("userRole", currentUser.getRole());
        model.addAttribute("control", control);
        model.addAttribute("performanceStatus", performanceStatus);
        model.addAttribute("readOnly", readOnly);
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
    @GetMapping("/edit-control/{id}")
    public String editControl(@PathVariable Long id, Model model, HttpSession session,
                              RedirectAttributes redirectAttributes) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);

        Control control = controlService.getControlById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Control not found with id: " + id));

        ControlPermission permission = permissionService.resolve(control, currentUser);
        if (!permission.canView()) {
            throw new ForbiddenException("You do not have permission to view this control.");
        }

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", currentUser.getMail());
        model.addAttribute("readOnly", !permission.canEdit());
        model.addAttribute("canUseWorkflowActions", permission.canUseWorkflowActions());
        model.addAttribute("allowedEditableFields", permission.getAllowedEditableFields());
        model.addAttribute("control", control);

        return "edit-control";
    }

    @GetMapping("/new-control")
    public String newControl(Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", currentUser.getMail());

        return "new-control";
    }

    @GetMapping("/action-centre")
    public String actionCentre(Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);
        String userRole = currentUser.getRole();
        String userEmail = currentUser.getMail();

        List<Control> controlsToShow = new ArrayList<>();

        // Show controls based on user role and assignments
        List<Control> allControls = controlService.getAllControls();
        
        for (Control control : allControls) {
            boolean shouldShow = false;
            String performanceStatus = control.getPerformanceStatus();
            if (performanceStatus == null || performanceStatus.isEmpty()) {
                performanceStatus = "DRAFT";
            }

            boolean isCreator = control.getCreatedBy() != null && userEmail.equals(control.getCreatedBy().getMail());

            if (isSoqmRole(userRole)) {
                shouldShow = true;
            } else {
                // For all other roles, check assignment-based access
                try {
                    ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
                    if (assignment != null) {
                        if (assignment.getFacilitator() != null && assignment.getFacilitator().contains(userEmail)) shouldShow = true;
                        if (assignment.getControlOperator() != null && assignment.getControlOperator().contains(userEmail)) shouldShow = true;
                        if (assignment.getProcessOwner() != null && assignment.getProcessOwner().contains(userEmail)) shouldShow = true;
                        if (assignment.getSoqmLead() != null && assignment.getSoqmLead().contains(userEmail)) shouldShow = true;
                    }
                } catch (Exception e) {
                    // Assignment not found
                }
                shouldShow = shouldShow || isCreator;
            }

            if (shouldShow) {
                controlsToShow.add(control);
            }
        }

        // ★ ИНИЦИАЛИЗИРУЕМ ВСЕ КОМПОНЕНТЫ С 0
        Map<String, Long> componentStats = new HashMap<>();

        // Все возможные компоненты
        String[] allComponents = {
                "HR", "INTR", "M&R", "RAP",
                "A&C", "I&C", "GOV",
                "EP", "RER", "TECHR"
        };

        // Устанавливаем 0 для всех компонентов
        for (String component : allComponents) {
            componentStats.put(component, 0L);
        }

        if (isSoqmRole(userRole)) {
            for (Control control : controlsToShow) {
                String component = control.getComponent();
                if (component != null && !component.trim().isEmpty()) {
                    if (componentStats.containsKey(component)) {
                        componentStats.put(component, componentStats.get(component) + 1);
                    } else {
                        componentStats.put(component, 1L);
                    }
                }
            }
            componentStats.put("All", (long) controlsToShow.size());
        } else {
            long nonDraftCount = 0L;
            for (Control control : controlsToShow) {
                String status = normalizeStatus(control.getPerformanceStatus());
                if ("DRAFT".equals(status)) {
                    continue;
                }
                nonDraftCount++;
                String component = control.getComponent();
                if (component != null && !component.trim().isEmpty()) {
                    if (componentStats.containsKey(component)) {
                        componentStats.put(component, componentStats.get(component) + 1);
                    } else {
                        componentStats.put(component, 1L);
                    }
                }
            }

            componentStats.put("All", nonDraftCount);
        }

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", userEmail);
        model.addAttribute("userRole", userRole);
        model.addAttribute("componentStats", componentStats);
        model.addAttribute("controls", controlsToShow);
        model.addAttribute("controlsCount", controlsToShow.size());

        // Add unread notifications count
        model.addAttribute("unreadNotifications", getUnreadCount(currentUser));

        return "action-centre";
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

            if (!canViewControl(controlId, control, currentUser)) {
                redirectAttributes.addFlashAttribute("accessDeniedMessage",
                        "Access revoked — you no longer have permission to view this control.");
                return "redirect:/controls";
            }

            // 2. Получаем Performance DTO (built from Control + Assignment)
            PerformanceDTO performanceDTO = performanceService.buildPerformanceDTO(control);

            // 3. Получаем Assignment
            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(controlId);

            // 4. Получаем Process Owner из Assignment
            String processOwner = "Not assigned";
            if (assignment.getProcessOwner() != null && !assignment.getProcessOwner().isEmpty()) {
                List<String> processOwners = assignment.getProcessOwner();
                // Берем первого process owner
                String email = processOwners.get(0);
                Optional<User> ownerUser = userService.getUserByEmail(email);
                processOwner = ownerUser.map(User::getDisplayName).orElse(email);
            }

            // 5. Получаем Facilitator из Assignment
            String facilitator = "Not assigned";
            if (assignment.getFacilitator() != null && !assignment.getFacilitator().isEmpty()) {
                List<String> facilitators = assignment.getFacilitator();
                String email = facilitators.get(0);
                Optional<User> facilitatorUser = userService.getUserByEmail(email);
                facilitator = facilitatorUser.map(User::getDisplayName).orElse(email);
            }

            // 6. Получаем Control Operator из Assignment
            String controlOperator = "Not assigned";
            if (assignment.getControlOperator() != null && !assignment.getControlOperator().isEmpty()) {
                List<String> operators = assignment.getControlOperator();
                String email = operators.get(0);
                Optional<User> operatorUser = userService.getUserByEmail(email);
                controlOperator = operatorUser.map(User::getDisplayName).orElse(email);
            }

            // 7. Добавляем данные в модель
            model.addAttribute("userName", currentUser.getDisplayName());
            model.addAttribute("userTitle", currentUser.getTitle());
            model.addAttribute("userEmail", currentUser.getMail());
            model.addAttribute("userRole", currentUser.getRole());
            model.addAttribute("controlId", control.getControlId());
            model.addAttribute("control", control);

            model.addAttribute("soqmYear", performanceDTO.getSoqmYear());
            model.addAttribute("initiationDate", LocalDateTime.now()); // Текущее время как Initiation Date
            model.addAttribute("operationDate", assignment.getControlOperationDate());
            model.addAttribute("actualOperationDate", performanceDTO.getActualOperationDate());
            model.addAttribute("performanceStatus", performanceDTO.getPerformanceStatus());
            model.addAttribute("facilitator", facilitator);
            model.addAttribute("controlOperator", controlOperator);
            model.addAttribute("processOwner", processOwner);
            model.addAttribute("lastUpdatedBy", currentUser.getDisplayName());
            model.addAttribute("lastUpdatedOn", LocalDateTime.now());

            // Check if current user is a shared viewer
            boolean isShared = assignment.getControlSharedWith() != null
                    && assignment.getControlSharedWith().stream()
                        .anyMatch(e -> e != null && e.equalsIgnoreCase(currentUser.getMail()));
            model.addAttribute("isShared", isShared);

            return "performance-cycle";

        } catch (Exception e) {
            // В случае ошибки возвращаемся на страницу performance
            return "redirect:/performance/" + controlId + "?error=" + e.getMessage();
        }
    }

    private List<NotificationGroupDTO> groupNotificationsByDate(List<NotificationItemDTO> notifications) {
        Map<String, List<NotificationItemDTO>> grouped = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
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
                    // Format as "Jan 22", "Jun 10" etc
                    dateLabel = notif.getTimestamp().format(java.time.format.DateTimeFormatter.ofPattern("MMM d"));
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




