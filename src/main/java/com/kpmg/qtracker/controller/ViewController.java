package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.*;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlPerformance;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.NotificationRead;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDocumentsRepository;
import com.kpmg.qtracker.repository.NotificationReadRepository;
import com.kpmg.qtracker.service.*;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.kpmg.qtracker.service.WorkflowService;
import java.time.LocalDateTime;
import java.time.LocalDate;
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
    private final NotificationReadRepository notificationReadRepository;
    private final NotificationService notificationService;

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
    public String performanceChecklist(@PathVariable Long controlId, Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);

        Control control = controlService.getControlById(controlId)
                .orElseThrow(() -> new RuntimeException("Control not found with id: " + controlId));

        // Получаем данные из Assignment
        ControlAssignmentDTO assignmentDTO = controlAssignmentService.getAssignmentByControlId(controlId);

        // Получаем данные Performance
        PerformanceDTO performanceDTO = performanceService.findByControlId(controlId)
                .map(perf -> performanceService.convertToDTO(perf, control))
                .orElse(performanceService.convertToDTO(null, control));

        // Автозаполняем Control Operator и Facilitator из Assignment если они пустые
        if (assignmentDTO != null) {
            if ((performanceDTO.getControlOperator() == null || performanceDTO.getControlOperator().isEmpty())
                    && assignmentDTO.getControlOperator() != null && !assignmentDTO.getControlOperator().isEmpty()) {
                performanceDTO.setControlOperator(String.join(", ", assignmentDTO.getControlOperator()));
            }

            if ((performanceDTO.getFacilitator() == null || performanceDTO.getFacilitator().isEmpty())
                    && assignmentDTO.getFacilitator() != null && !assignmentDTO.getFacilitator().isEmpty()) {
                performanceDTO.setFacilitator(String.join(", ", assignmentDTO.getFacilitator()));
            }

            // Также Control Operation Date
            if (performanceDTO.getControlOperationDate() == null && assignmentDTO.getControlOperationDate() != null) {
                performanceDTO.setControlOperationDate(assignmentDTO.getControlOperationDate());
            }

            // ВАЖНОЕ ИСПРАВЛЕНИЕ: Устанавливаем Assigned To как имя первого Facilitator
            if (assignmentDTO.getFacilitator() != null && !assignmentDTO.getFacilitator().isEmpty()) {
                // Получаем первого facilitator из списка
                String facilitatorEmail = assignmentDTO.getFacilitator().get(0);

                // Находим пользователя по email
                Optional<User> facilitatorUser = userService.getUserByEmail(facilitatorEmail);

                if (facilitatorUser.isPresent()) {
                    performanceDTO.setAssignedTo(facilitatorUser.get().getDisplayName());
                } else {
                    performanceDTO.setAssignedTo(facilitatorEmail); // показываем email если пользователь не найден
                }
            }

            // Дополнительно: если Assigned To все еще пустое, проверяем Control Operator
            if ((performanceDTO.getAssignedTo() == null || performanceDTO.getAssignedTo().isEmpty() ||
                    performanceDTO.getAssignedTo().equals("0") || performanceDTO.getAssignedTo().equals("Not assigned"))
                    && assignmentDTO.getControlOperator() != null && !assignmentDTO.getControlOperator().isEmpty()) {
                // Берем первого Control Operator
                String operatorEmail = assignmentDTO.getControlOperator().get(0);
                Optional<User> operatorUser = userService.getUserByEmail(operatorEmail);

                if (operatorUser.isPresent()) {
                    performanceDTO.setAssignedTo(operatorUser.get().getDisplayName());
                }
            }
        }

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

        // Use Map for O(1) deduplication instead of stream().anyMatch()
        Map<Long, ControlResponseDTO> controlMap = new LinkedHashMap<>();

        // ★ ADMIN sees ALL controls
        if ("ADMIN".equals(userRole)) {
            List<Control> allControlsList = controlService.getAllControls();
            for (Control control : allControlsList) {
                ControlResponseDTO dto = controlService.convertToResponseDTO(control);
                controlMap.put(control.getId(), dto);
            }
        } else {
            // Always show controls created by the user
            List<ControlResponseDTO> userControls = controlService.getUserControlsDTO(userEmail);
            for (ControlResponseDTO control : userControls) {
                controlMap.put(control.getId(), control);
            }

            // If user is FACILITATOR, also add controls assigned to them (all statuses)
            if ("FACILITATOR".equals(userRole)) {
                List<ControlResponseDTO> facilitatorControls = controlService.getFacilitatorControlsDTO(userEmail);
                for (ControlResponseDTO control : facilitatorControls) {
                    controlMap.putIfAbsent(control.getId(), control);
                }
            }

            // If user is CONTROL_OPERATOR, also add controls assigned to them
            if ("CONTROL_OPERATOR".equals(userRole)) {
                List<Control> operatorControls = controlService.getControlOperatorControls(userEmail);
                for (Control control : operatorControls) {
                    controlMap.putIfAbsent(control.getId(), controlService.convertToResponseDTO(control));
                }
            }

            // If user is PROCESS_OWNER, also add controls assigned to them
            if ("PROCESS_OWNER".equals(userRole)) {
                List<Control> ownerControls = controlService.getProcessOwnerControls(userEmail);
                for (Control control : ownerControls) {
                    controlMap.putIfAbsent(control.getId(), controlService.convertToResponseDTO(control));
                }
            }

            // If user is SOQM_LEAD, also add controls assigned to them
            if ("SOQM_LEAD".equals(userRole)) {
                List<Control> soqmControls = controlService.getSoqmLeadControls(userEmail);
                for (Control control : soqmControls) {
                    controlMap.putIfAbsent(control.getId(), controlService.convertToResponseDTO(control));
                }
            }
        }

        List<ControlResponseDTO> allControls = new ArrayList<>(controlMap.values());
        int totalControls = allControls.size();

        // Count only editable controls as active
        // ★ ADMIN: all non-completed controls are active
        // Logic: editable if (creator AND status "In Progress") OR (facilitator AND status "Facilitator Review") OR (operator AND status "Control Operator Review") OR (soqm AND status "SoQM Lead Review") OR (po AND status "Process Owner Review")
        int activeControls = 0;
        int completedControls = 0;
        boolean isAdmin = "ADMIN".equals(userRole);
        
        for (ControlResponseDTO control : allControls) {
            String status = control.getControlStatus();
            
            if ("Completed".equals(status)) {
                completedControls++;
            } else if (isAdmin) {
                // ADMIN: все незавершенные контроли считаются активными
                activeControls++;
            } else {
                boolean isCreator = userEmail.equals(control.getCreatedByEmail());
                boolean isFacilitator = control.getFacilitators() != null && control.getFacilitators().contains(userEmail);
                boolean isOperator = control.getControlOperators() != null && control.getControlOperators().contains(userEmail);
                boolean isSoqmLead = control.getSoqmLeads() != null && control.getSoqmLeads().contains(userEmail);
                boolean isProcessOwner = control.getProcessOwners() != null && control.getProcessOwners().contains(userEmail);
                
                boolean isEditable = (isCreator && "In Progress".equals(status)) ||
                                    (isFacilitator && "Facilitator Review".equals(status)) ||
                                    (isOperator && "Control Operator Review".equals(status)) ||
                                    (isSoqmLead && "SoQM Lead Review".equals(status)) ||
                                    (isProcessOwner && "Process Owner Review".equals(status));
                
                if (isEditable) {
                    activeControls++;
                }
            }
        }

        model.addAttribute("totalControls", totalControls);
        model.addAttribute("activeControls", activeControls);
        model.addAttribute("completedControls", completedControls);
        model.addAttribute("overdueControls", 0);

        model.addAttribute("recentControls", allControls.stream()
                .sorted((c1, c2) -> {
                    if (c1.getCreatedAt() == null || c2.getCreatedAt() == null) return 0;
                    return c2.getCreatedAt().compareTo(c1.getCreatedAt());
                })
                .limit(5)
                .collect(Collectors.toList()));

        return "dashboard";
    }

    @GetMapping("/controls")
    public String controls(@RequestParam(value = "scope", required = false) String scope,
                           Model model,
                           HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);
        String userRole = currentUser.getRole();
        String userEmail = currentUser.getMail();
        String normalizedScope = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        String effectiveScope;
        if (normalizedScope.isBlank()) {
            effectiveScope = "ADMIN".equals(userRole) ? "active" : "mine";
        } else {
            effectiveScope = normalizedScope;
        }
        if ("ADMIN".equals(userRole)) {
            if (!"active".equals(effectiveScope) && !"completed".equals(effectiveScope) && !"all".equals(effectiveScope)) {
                effectiveScope = "active";
            }
        } else {
            if (!"mine".equals(effectiveScope) && !"all".equals(effectiveScope)) {
                effectiveScope = "mine";
            }
        }

        // Use LinkedHashMap to preserve order and Set for O(1) lookup
        Map<Long, ControlResponseDTO> controlMap = new LinkedHashMap<>();
        
        // ★ ADMIN sees ALL controls
        if ("ADMIN".equals(userRole)) {
            List<Control> allControls = controlService.getAllControls();
            for (Control control : allControls) {
                ControlResponseDTO dto = controlService.convertToResponseDTO(control);
                controlMap.put(control.getId(), dto);
            }
        } else {
            // Always show controls created by the user
            List<ControlResponseDTO> userControls = controlService.getUserControlsDTO(userEmail);
            for (ControlResponseDTO control : userControls) {
                controlMap.put(control.getId(), control);
            }

            // If user is FACILITATOR, also add controls assigned to them (all statuses)
            if ("FACILITATOR".equals(userRole)) {
                List<ControlResponseDTO> facilitatorControls = controlService.getFacilitatorControlsDTO(userEmail);
                for (ControlResponseDTO control : facilitatorControls) {
                    controlMap.putIfAbsent(control.getId(), control);
                }
            }

            // If user is CONTROL_OPERATOR, also add controls assigned to them
            if ("CONTROL_OPERATOR".equals(userRole)) {
                List<Control> operatorControls = controlService.getControlOperatorControls(userEmail);
                for (Control control : operatorControls) {
                    if (!controlMap.containsKey(control.getId())) {
                        ControlResponseDTO dto = controlService.convertToResponseDTO(control);
                        controlMap.put(control.getId(), dto);
                    }
                }
            }

            // If user is PROCESS_OWNER, also add controls assigned to them
            if ("PROCESS_OWNER".equals(userRole)) {
                List<Control> ownerControls = controlService.getProcessOwnerControls(userEmail);
                for (Control control : ownerControls) {
                    if (!controlMap.containsKey(control.getId())) {
                        ControlResponseDTO dto = controlService.convertToResponseDTO(control);
                        controlMap.put(control.getId(), dto);
                    }
                }
            }

            // If user is SOQM_LEAD, also add controls assigned to them
            if ("SOQM_LEAD".equals(userRole)) {
                List<Control> soqmControls = controlService.getSoqmLeadControls(userEmail);
                for (Control control : soqmControls) {
                    if (!controlMap.containsKey(control.getId())) {
                        ControlResponseDTO dto = controlService.convertToResponseDTO(control);
                        controlMap.put(control.getId(), dto);
                    }
                }
            }
        }

        // Note: In /controls page, SOQM_LEAD sees both created controls AND assigned controls
        // In /component/All and /action-centre, they see ALL controls

        List<ControlResponseDTO> userControlsList = new ArrayList<>(controlMap.values());
        // Sort by updated date in descending order (most recently updated first)
        userControlsList.sort((c1, c2) -> {
            LocalDateTime date1 = c1.getUpdatedAt() != null ? c1.getUpdatedAt() : c1.getCreatedAt();
            LocalDateTime date2 = c2.getUpdatedAt() != null ? c2.getUpdatedAt() : c2.getCreatedAt();
            return date2.compareTo(date1);
        });

        if ("mine".equals(effectiveScope)) {
            int beforeCount = userControlsList.size();
            userControlsList = userControlsList.stream()
                    .filter(control -> isAssignedToUser(control, userEmail))
                    .filter(control -> isActiveForUser(control, userEmail, userRole))
                    .collect(Collectors.toList());
            System.out.println("controls filter scope=mine user=" + userEmail
                    + " before=" + beforeCount + " after=" + userControlsList.size());
        } else if ("active".equals(effectiveScope)) {
            int beforeCount = userControlsList.size();
            userControlsList = userControlsList.stream()
                    .filter(control -> control.getControlStatus() == null
                            || !"Completed".equalsIgnoreCase(control.getControlStatus()))
                    .collect(Collectors.toList());
            System.out.println("controls filter scope=active user=" + userEmail
                    + " before=" + beforeCount + " after=" + userControlsList.size());
        } else if ("completed".equals(effectiveScope)) {
            int beforeCount = userControlsList.size();
            userControlsList = userControlsList.stream()
                    .filter(control -> "Completed".equalsIgnoreCase(control.getControlStatus()))
                    .collect(Collectors.toList());
            System.out.println("controls filter scope=completed user=" + userEmail
                    + " before=" + beforeCount + " after=" + userControlsList.size());
        } else {
            System.out.println("controls filter scope=all user=" + userEmail
                    + " count=" + userControlsList.size());
        }

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", userEmail);
        model.addAttribute("userRole", userRole);
        model.addAttribute("controlsFilter", effectiveScope);
        model.addAttribute("controls", userControlsList);
        model.addAttribute("unreadNotifications", getUnreadCount(currentUser));

        return "controls";
    }

    private boolean isAssignedToUser(ControlResponseDTO control, String userEmail) {
        return (userEmail != null && userEmail.equalsIgnoreCase(control.getCreatedByEmail()))
                || listContains(control.getFacilitators(), userEmail)
                || listContains(control.getControlOperators(), userEmail)
                || listContains(control.getSoqmLeads(), userEmail)
                || listContains(control.getProcessOwners(), userEmail);
    }

    private boolean isActiveForUser(ControlResponseDTO control, String userEmail, String userRole) {
        if (control == null) return false;
        String status = control.getControlStatus();

        if ("ADMIN".equals(userRole)) {
            return status != null && !"Completed".equalsIgnoreCase(status);
        }

        boolean isCreator = userEmail != null && userEmail.equalsIgnoreCase(control.getCreatedByEmail());
        boolean isFacilitator = listContains(control.getFacilitators(), userEmail);
        boolean isOperator = listContains(control.getControlOperators(), userEmail);
        boolean isSoqmLead = listContains(control.getSoqmLeads(), userEmail);
        boolean isProcessOwner = listContains(control.getProcessOwners(), userEmail);

        return (("In Progress".equals(status)) && (isCreator || "ADMIN".equals(userRole)))
                || (isFacilitator && "Facilitator Review".equals(status))
                || (isOperator && "Control Operator Review".equals(status))
                || (isSoqmLead && "SoQM Lead Review".equals(status))
                || (isProcessOwner && "Process Owner Review".equals(status));
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
            .map(this::convertNotificationToDTO)
            .collect(Collectors.toList());
        
        // Cache notifications in session so detail view can retrieve by ID
        session.setAttribute("cachedNotifications", notifications);

        long unreadCount = notificationService.countUnread(currentUser.getId());
        model.addAttribute("unreadNotifications", unreadCount);

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

        // Parse notification ID and mark as read
        try {
            Long notifId = Long.parseLong(notificationId);
            notificationService.markAsRead(notifId);
            
            // Get notification from DB
            com.kpmg.qtracker.entity.Notification notif = 
                notificationService.getUserNotifications(currentUser.getId()).stream()
                    .filter(n -> n.getId().equals(notifId))
                    .findFirst()
                    .orElse(null);
            
            if (notif == null) {
                model.addAttribute("error", "Notification not found");
                return "notification-detail";
            }
            
            NotificationItemDTO dto = convertNotificationToDTO(notif);
            model.addAttribute("notification", dto);
            
        } catch (NumberFormatException e) {
            model.addAttribute("error", "Invalid notification ID");
            return "notification-detail";
        }

        long unreadCount = notificationService.countUnread(currentUser.getId());
        model.addAttribute("unreadNotifications", unreadCount);

        return "notification-detail";
    }

    @PostMapping("/notifications/mark-all-read")
    public String markAllNotificationsRead(HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);
        notificationService.markAllAsRead(currentUser.getId());

        return "redirect:/notifications";
    }

    private List<NotificationItemDTO> buildNotifications(User currentUser) {
        List<NotificationItemDTO> notifications = new ArrayList<>();

        String userEmail = currentUser.getMail();
        String userRole = currentUser.getRole();
        Long userId = currentUser.getId();

        // Get all read notification IDs for this user
        List<String> readNotificationIds = notificationReadRepository.findReadNotificationIdsByUserId(userId);

        // Collect related controls similar to /controls
        Map<Long, Control> relatedControls = new LinkedHashMap<>();

        if ("ADMIN".equals(userRole)) {
            for (Control c : controlService.getAllControls()) {
                relatedControls.put(c.getId(), c);
            }
        } else {
            // Created by user
            for (Control c : controlService.getUserControls(userEmail)) {
                relatedControls.put(c.getId(), c);
            }

            // Assigned roles
            for (Control c : controlService.getControlOperatorControls(userEmail)) {
                relatedControls.putIfAbsent(c.getId(), c);
            }
            for (Control c : controlService.getProcessOwnerControls(userEmail)) {
                relatedControls.putIfAbsent(c.getId(), c);
            }
            for (Control c : controlService.getSoqmLeadControls(userEmail)) {
                relatedControls.putIfAbsent(c.getId(), c);
            }
            // Facilitator controls (DTO -> Control via id lookup)
            for (ControlResponseDTO dto : controlService.getFacilitatorControlsDTO(userEmail)) {
                controlService.getControlById(dto.getId()).ifPresent(c -> relatedControls.putIfAbsent(c.getId(), c));
            }
        }

        // For each control, generate events
        for (Control control : relatedControls.values()) {
            Long cid = control.getId();
            String controlIdNumber = control.getControlId();

            // Status change (use updatedAt if present)
            NotificationItemDTO statusItem = new NotificationItemDTO();
            String statusNotifId = "status-" + cid;
            statusItem.setId(statusNotifId);
            statusItem.setType("Status Change");
            statusItem.setControlId(cid);
            statusItem.setControlIdNumber(controlIdNumber);
            statusItem.setComponent(control.getComponent());
            String statusText = (control.getControlStatus() != null ? control.getControlStatus() : "In Progress");
            statusItem.setMessage("Status set to: " + statusText);
            statusItem.setFullText("The control status has been updated to: " + statusText + "\n\nControl: " + controlIdNumber + "\nComponent: " + control.getComponent());
            statusItem.setBy(control.getCreatedBy() != null ? control.getCreatedBy().getDisplayName() : "System");
            statusItem.setTimestamp(control.getUpdatedAt() != null ? control.getUpdatedAt() : control.getCreatedAt());
            statusItem.setRead(readNotificationIds.contains(statusNotifId));
            statusItem.setAttachments(new ArrayList<>());
            notifications.add(statusItem);

            // Comments (SoQM Head)
            ControlDetailsDTO details = controlDetailsService.getDetailsByControlId(cid);
            if (details != null) {
                if (details.getSoqmHeadComments() != null && !details.getSoqmHeadComments().trim().isEmpty()) {
                    NotificationItemDTO commentItem = new NotificationItemDTO();
                    String commentNotifId = "comment-soqm-" + cid;
                    commentItem.setId(commentNotifId);
                    commentItem.setType("Comment");
                    commentItem.setControlId(cid);
                    commentItem.setControlIdNumber(controlIdNumber);
                    commentItem.setComponent(control.getComponent());
                    commentItem.setMessage("SoQM Lead commented: " + details.getSoqmHeadComments());
                    commentItem.setFullText("SoQM Lead Comment:\n\n" + details.getSoqmHeadComments());
                    commentItem.setBy("SoQM Lead");
                    commentItem.setTimestamp(control.getUpdatedAt());
                    commentItem.setRead(readNotificationIds.contains(commentNotifId));
                    commentItem.setAttachments(new ArrayList<>());
                    notifications.add(commentItem);
                }
                if (details.getProcessOwnerComments() != null && !details.getProcessOwnerComments().trim().isEmpty()) {
                    NotificationItemDTO commentItem = new NotificationItemDTO();
                    String commentNotifId = "comment-owner-" + cid;
                    commentItem.setId(commentNotifId);
                    commentItem.setType("Comment");
                    commentItem.setControlId(cid);
                    commentItem.setControlIdNumber(controlIdNumber);
                    commentItem.setComponent(control.getComponent());
                    commentItem.setMessage("Process Owner commented: " + details.getProcessOwnerComments());
                    commentItem.setFullText("Process Owner Comment:\n\n" + details.getProcessOwnerComments());
                    commentItem.setBy("Process Owner");
                    commentItem.setTimestamp(control.getUpdatedAt());
                    commentItem.setRead(readNotificationIds.contains(commentNotifId));
                    commentItem.setAttachments(new ArrayList<>());
                    notifications.add(commentItem);
                }
            }

            // File uploads
            controlDocumentsRepository.findByControlId(cid).ifPresent(doc -> {
                String fileName = doc.getAttachment();
                String link = doc.getLink();
                if ((fileName != null && !fileName.trim().isEmpty()) || (link != null && !link.trim().isEmpty())) {
                    NotificationItemDTO fileItem = new NotificationItemDTO();
                    String fileNotifId = "file-" + cid;
                    fileItem.setId(fileNotifId);
                    fileItem.setType("File Upload");
                    fileItem.setControlId(cid);
                    fileItem.setControlIdNumber(controlIdNumber);
                    fileItem.setComponent(control.getComponent());
                    String fileText = (fileName != null && !fileName.trim().isEmpty()) ? ("Attachment: " + fileName) : ("Link: " + link);
                    fileItem.setMessage(fileText);
                    fileItem.setFullText(fileText + "\n\nControl: " + controlIdNumber);
                    fileItem.setBy(control.getCreatedBy() != null ? control.getCreatedBy().getDisplayName() : "User");
                    fileItem.setTimestamp(control.getUpdatedAt());
                    fileItem.setRead(readNotificationIds.contains(fileNotifId));
                    
                    // Add attachments
                    List<AttachmentDTO> attachments = new ArrayList<>();
                    if (fileName != null && !fileName.trim().isEmpty()) {
                        attachments.add(new AttachmentDTO(fileName, "/documents/" + cid, "file"));
                    }
                    if (link != null && !link.trim().isEmpty()) {
                        attachments.add(new AttachmentDTO("External Link", link, "link"));
                    }
                    fileItem.setAttachments(attachments);
                    
                    notifications.add(fileItem);
                }
            });
        }

        // Sort newest first by timestamp
        notifications.sort((a, b) -> {
            LocalDateTime ta = a.getTimestamp();
            LocalDateTime tb = b.getTimestamp();
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });

        return notifications;
    }

    private long getUnreadCount(User currentUser) {
        // Use DB-backed notification service
        return notificationService.countUnread(currentUser.getId());
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
        
        return dto;
    }

    @GetMapping("/component/{componentName}")
    public String controlsByComponent(@PathVariable String componentName, Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);
        String userRole = currentUser.getRole();
        String userEmail = currentUser.getMail();

        List<Control> allControls;

        if ("All".equals(componentName)) {
            allControls = controlService.getAllControls();
        } else {
            allControls = controlService.getControlsByComponent(componentName);
        }

        // Use the SAME logic as /controls page
        List<Control> filteredControls = new ArrayList<>();

        for (Control control : allControls) {
            boolean shouldShow = false;
            String performanceStatus = control.getControlStatus();
            if (performanceStatus == null || performanceStatus.isEmpty()) {
                performanceStatus = "In Progress";
            }

            boolean isCreator = control.getCreatedBy() != null && userEmail.equals(control.getCreatedBy().getMail());

            switch (userRole) {
                case "SOQM_LEAD":
                    // SoQM Lead sees ALL controls
                    shouldShow = true;
                    break;
                case "CONTROL_OPERATOR":
                    // Show if created OR assigned to them
                    boolean isAssignedOperator = false;
                    try {
                        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
                        if (assignment != null && assignment.getControlOperator() != null) {
                            isAssignedOperator = assignment.getControlOperator().contains(userEmail);
                        }
                    } catch (Exception e) {
                        // Assignment not found
                    }
                    shouldShow = isCreator || isAssignedOperator;
                    break;
                case "PROCESS_OWNER":
                    // Show if created OR assigned to them
                    boolean isAssignedOwner = false;
                    try {
                        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
                        if (assignment != null && assignment.getProcessOwner() != null) {
                            isAssignedOwner = assignment.getProcessOwner().contains(userEmail);
                        }
                    } catch (Exception e) {
                        // Assignment not found
                    }
                    shouldShow = isCreator || isAssignedOwner;
                    break;
                case "FACILITATOR":
                    // Show if created OR assigned as facilitator
                    boolean isFacilitator = false;
                    try {
                        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
                        if (assignment != null && assignment.getFacilitator() != null) {
                            isFacilitator = assignment.getFacilitator().contains(userEmail);
                        }
                    } catch (Exception e) {
                        // Assignment not found
                    }
                    shouldShow = isCreator || isFacilitator;
                    break;
                default:
                    shouldShow = isCreator;
                    break;
            }

            if (shouldShow) {
                filteredControls.add(control);
            }
        }

        List<ControlResponseDTO> controlDTOs = filteredControls.stream()
                .map(controlService::convertToResponseDTO)
                .sorted((c1, c2) -> c2.getId().compareTo(c1.getId())) // Sort by ID desc (newest first)
                .collect(Collectors.toList());

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", currentUser.getMail());
        model.addAttribute("controls", controlDTOs);
        model.addAttribute("currentComponent", componentName);

        return "component-controls";
    }


    @GetMapping("/view-control/{id}")
    public String viewControl(@PathVariable Long id, Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);

        Control control = controlService.getControlById(id)
                .orElseThrow(() -> new RuntimeException("Control not found with id: " + id));

        // Use control_status instead
        String performanceStatus = control.getControlStatus();
        if (performanceStatus == null || performanceStatus.isEmpty()) {
            performanceStatus = "In Progress";
        }

        // Determine if current user can edit this control
        String userEmail = currentUser.getMail();
        String userRole = currentUser.getRole();
        boolean isCreator = control.getCreatedBy() != null && control.getCreatedBy().getMail().equals(userEmail);
        boolean isInProgress = "In Progress".equals(performanceStatus);
        List<String> facilitators = controlService.getFacilitatorsForControl(id);
        boolean isFacilitator = facilitators != null && facilitators.contains(userEmail);
        
        // Check if user is Control Operator for this control
        boolean isControlOperator = false;
        if ("CONTROL_OPERATOR".equals(userRole)) {
            Optional<ControlAssignment> assignmentOpt = controlAssignmentRepository.findByControlId(id);
            if (assignmentOpt.isPresent()) {
                String operatorField = assignmentOpt.get().getControlOperator();
                isControlOperator = operatorField != null && operatorField.contains(userEmail);
            }
        }
        
        // Check if user is SoQM Lead for this control
        boolean isSoqmLead = false;
        if ("SOQM_LEAD".equals(userRole)) {
            Optional<ControlAssignment> assignmentOpt = controlAssignmentRepository.findByControlId(id);
            if (assignmentOpt.isPresent()) {
                String soqmField = assignmentOpt.get().getSoqmLead();
                isSoqmLead = soqmField != null && soqmField.contains(userEmail);
            }
        }
        
        // Check if user is Process Owner for this control
        boolean isProcessOwner = false;
        if ("PROCESS_OWNER".equals(userRole)) {
            Optional<ControlAssignment> assignmentOpt = controlAssignmentRepository.findByControlId(id);
            if (assignmentOpt.isPresent()) {
                String poField = assignmentOpt.get().getProcessOwner();
                isProcessOwner = poField != null && poField.contains(userEmail);
            }
        }
        
        // Control Operator can edit when status is "Control Operator Review"
        boolean isControlOperatorReview = "Control Operator Review".equals(performanceStatus);
        boolean isSoqmLeadReview = "SoQM Lead Review".equals(performanceStatus);
        boolean isProcessOwnerReview = "Process Owner Review".equals(performanceStatus);
        
        boolean canEdit = (isCreator && isInProgress) ||
                          (isFacilitator && "Facilitator Review".equals(performanceStatus)) ||
                          (isControlOperator && isControlOperatorReview) ||
                          (isSoqmLead && isSoqmLeadReview) ||
                          (isProcessOwner && isProcessOwnerReview);
        boolean readOnly = !canEdit;

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", userEmail);
        model.addAttribute("userRole", currentUser.getRole());
        model.addAttribute("control", control);
        model.addAttribute("performanceStatus", performanceStatus);
        model.addAttribute("readOnly", readOnly);
        model.addAttribute("isFacilitator", isFacilitator);
        model.addAttribute("isControlOperator", isControlOperator);
        model.addAttribute("isSoqmLead", isSoqmLead);
        model.addAttribute("isProcessOwner", isProcessOwner);

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
    public String editControl(@PathVariable Long id, Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);

        Control control = controlService.getControlById(id)
                .orElseThrow(() -> new RuntimeException("Control not found with id: " + id));

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", currentUser.getMail());
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
            String performanceStatus = control.getControlStatus();
            if (performanceStatus == null || performanceStatus.isEmpty()) {
                performanceStatus = "In Progress";
            }

            boolean isCreator = control.getCreatedBy() != null && userEmail.equals(control.getCreatedBy().getMail());

            switch (userRole) {
                case "SOQM_LEAD":
                    // SoQM Lead sees ALL controls
                    shouldShow = true;
                    break;
                case "CONTROL_OPERATOR":
                    // Show if created OR assigned to them
                    boolean isAssignedOperator = false;
                    try {
                        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
                        if (assignment != null && assignment.getControlOperator() != null) {
                            isAssignedOperator = assignment.getControlOperator().contains(userEmail);
                        }
                    } catch (Exception e) {
                        // Assignment not found
                    }
                    shouldShow = isCreator || isAssignedOperator;
                    break;
                case "PROCESS_OWNER":
                    // Show if created OR assigned to them
                    boolean isAssignedOwner = false;
                    try {
                        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
                        if (assignment != null && assignment.getProcessOwner() != null) {
                            isAssignedOwner = assignment.getProcessOwner().contains(userEmail);
                        }
                    } catch (Exception e) {
                        // Assignment not found
                    }
                    shouldShow = isCreator || isAssignedOwner;
                    break;
                case "FACILITATOR":
                    // Show if created OR assigned as facilitator
                    boolean isFacilitator = false;
                    try {
                        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
                        if (assignment != null && assignment.getFacilitator() != null) {
                            isFacilitator = assignment.getFacilitator().contains(userEmail);
                        }
                    } catch (Exception e) {
                        // Assignment not found
                    }
                    shouldShow = isCreator || isFacilitator;
                    break;
                default:
                    // Other roles see only controls they created
                    shouldShow = isCreator;
                    break;
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

        // Считаем реальные значения
        for (Control control : controlsToShow) {
            String component = control.getComponent();
            if (component != null && !component.trim().isEmpty()) {
                // Если компонент есть в нашем списке, увеличиваем счетчик
                if (componentStats.containsKey(component)) {
                    componentStats.put(component, componentStats.get(component) + 1);
                } else {
                    // Если компонент не в списке, добавляем его
                    componentStats.put(component, 1L);
                }
            }
        }

        // ★ ВАЖНО: добавляем "All" с общим количеством
        componentStats.put("All", (long) controlsToShow.size());

        model.addAttribute("userName", currentUser.getDisplayName());
        model.addAttribute("userTitle", currentUser.getTitle());
        model.addAttribute("userEmail", userEmail);
        model.addAttribute("userRole", userRole);
        model.addAttribute("componentStats", componentStats);
        model.addAttribute("controls", controlsToShow);
        model.addAttribute("controlsCount", controlsToShow.size());

        // Add unread notifications count
        long unreadCount = notificationService.countUnread(currentUser.getId());
        model.addAttribute("unreadNotifications", unreadCount);

        return "action-centre";
    }

    @GetMapping("/performance-cycle/{controlId}")
    public String performanceCycle(@PathVariable Long controlId, Model model, HttpSession session) {
        String redirect = checkAuthAndRedirect(session);
        if (redirect != null) return redirect;

        User currentUser = getCurrentUser(session);

        try {
            // 1. Получаем Control
            Control control = controlService.getControlById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found with id: " + controlId));

            // 2. Получаем Performance (после инициализации)
            PerformanceDTO performanceDTO = performanceService.findByControlId(controlId)
                    .map(perf -> performanceService.convertToDTO(perf, control))
                    .orElseThrow(() -> new RuntimeException("Performance not initialized"));

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
