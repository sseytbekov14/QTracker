package com.kpmg.qtracker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.enums.WorkflowActionType;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Moves a control from its status to another, for every workflow endpoint: who may (the guard, from
 * {@link AccessPolicy}), the comment, the required fields, the new status, the history entry, the audit
 * entry and the notifications. A SoQM user may make the move of any role (business decision 3); history and
 * audit then name the SoQM user as the one who did it, the role they acted for, the people assigned to that
 * step and "on behalf", and those people get an in-app copy of the notification.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowMoveService {

    /** Every return needs a reason (spec 9.4). */
    public static final String RETURN_COMMENT_REQUIRED = "A comment is required to return the control";
    public static final String COMMENT_TOO_LONG = "Comment is too long. Maximum 2000 characters allowed.";
    public static final String AUDIT_ACTION = "WORKFLOW_MOVE";
    private static final int MAX_COMMENT = 2000;

    private final IControlService controlService;
    private final ControlAssignmentService controlAssignmentService;
    private final ControlPermissionService controlPermissionService;
    private final WorkflowTransitionGuard transitionGuard;
    private final WorkflowRequiredFieldService requiredFieldService;
    private final WorkflowHistoryRepository workflowHistoryRepository;
    private final NotificationService notificationService;
    private final AdminAuditService adminAuditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** What happened: 200 with the new status, or the HTTP status and message of the refusal. */
    public record Outcome(int httpStatus, String message, String newStatus, WorkflowMove move) {

        static Outcome refused(int httpStatus, String message) {
            return new Outcome(httpStatus, message, null, null);
        }

        public boolean ok() {
            return httpStatus == 200;
        }
    }

    /** A standard step (the endpoint of one transition, or the transitions of a perform-action name). */
    @Transactional
    public Outcome perform(Control control, User user, Collection<WorkflowTransition> candidates, String comment) {
        ControlPermission permission = controlPermissionService.resolve(control, user);
        return execute(control, user, transitionGuard.check(control, permission, candidates), comment);
    }

    /** A move to a target status: a step of the user's own, or any of SoQM's ({@link AccessPolicy#move}). */
    @Transactional
    public Outcome moveTo(Control control, User user, String targetStatus, String comment) {
        ControlPermission permission = controlPermissionService.resolve(control, user);
        return execute(control, user, transitionGuard.checkMove(control, permission, targetStatus), comment);
    }

    private Outcome execute(Control control, User user, WorkflowTransitionGuard.Decision decision, String comment) {
        if (!decision.allowed()) {
            return Outcome.refused(decision.httpStatus(), decision.message());
        }
        WorkflowMove move = decision.move();
        String trimmedComment = comment == null || comment.isBlank() ? null : comment.trim();

        // The step's own field first, as every endpoint checked it: SoQM comments for any move out of SoQM
        // review, Process Owner Comments for any move out of Process Owner Review
        Optional<String> missingReviewComment = requiredFieldService.getMissingReviewCommentMessage(control);
        if (missingReviewComment.isPresent()) {
            return Outcome.refused(400, missingReviewComment.get());
        }
        if (move.commentRequired() && trimmedComment == null) {
            return Outcome.refused(400, move.isReturn() ? RETURN_COMMENT_REQUIRED
                    : "A comment is required when SoQM acts for the " + move.actingFor().getDisplayName());
        }
        if (trimmedComment != null && trimmedComment.length() > MAX_COMMENT) {
            return Outcome.refused(400, COMMENT_TOO_LONG);
        }
        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
        if (!move.isReturn()) {
            Optional<String> missingField = requiredFieldService.getMissingFieldMessage(control);
            if (missingField.isPresent()) {
                return Outcome.refused(400, missingField.get());
            }
            String missingPerson = missingNextPerson(move.to(), assignment);
            if (missingPerson != null) {
                return Outcome.refused(400, missingPerson);
            }
        }

        control.setPerformanceStatus(move.to());
        if (move.isReturn() && "IN_PROGRESS".equals(move.to())) {
            control.setReturnToFacilitatorComment(trimmedComment);
        } else if (move.isReturn() && "REVIEW".equals(move.to())) {
            control.setReturnToOperatorComment(trimmedComment);
        }
        controlService.save(control);

        List<String> assigned = peopleOf(move.actingFor(), assignment);
        recordHistory(control, user, move, trimmedComment, assigned);
        recordAudit(control, user, move, trimmedComment, assigned);
        notifyMove(control, user, move, trimmedComment, assignment);
        if (move.onBehalf()) {
            notificationService.sendOnBehalfCopy(control, assigned, user.getDisplayName(),
                    move.actingFor().getDisplayName(), move.label(), move.from(), move.to(), trimmedComment);
        }
        log.info("Workflow move {} -> {} on control {} by {}{}", move.from(), move.to(), control.getId(),
                user.getMail(), move.onBehalf() ? " on behalf of the " + move.actingFor().getDisplayName() : "");
        return new Outcome(200, null, move.to(), move);
    }

    /** Moving on needs someone in the next step's field. */
    private String missingNextPerson(String to, ControlAssignmentDTO assignment) {
        return switch (to) {
            case "REVIEW" -> isEmpty(assignment.getControlOperator())
                    ? "Control Operator not assigned. Please assign a Control Operator first." : null;
            case "SOQM_HEAD_REVIEW" -> isEmpty(assignment.getSoqmLead())
                    ? "SoQM Team not assigned. Please assign a SoQM Team first." : null;
            case "PROCESS_OWNER_REVIEW" -> isEmpty(assignment.getProcessOwner())
                    ? "Process Owner not assigned. Please assign a Process Owner first." : null;
            default -> null;
        };
    }

    private void recordHistory(Control control, User user, WorkflowMove move, String comment, List<String> assigned) {
        WorkflowHistory history = new WorkflowHistory();
        history.setControlId(control.getId());
        history.setActionType(historyType(move));
        history.setFromStep(move.from());
        history.setToStep(move.to());
        history.setPerformedByEmail(user.getMail());
        history.setPerformedByName(user.getDisplayName());
        history.setComments(comment != null ? comment : defaultComment(move));
        history.setActedAs(move.actingFor().getDisplayName());
        history.setOnBehalf(move.onBehalf());
        history.setAssignedPerformer(assigned.isEmpty() ? null : String.join(", ", assigned));
        history.setCreatedAt(LocalDateTime.now(Notification.ZONE));
        workflowHistoryRepository.save(history);
    }

    /** One audit entry per move (spec 9.3, 21.2): previous and new status, who did it and for whom. */
    private void recordAudit(Control control, User user, WorkflowMove move, String comment, List<String> assigned) {
        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("Performance Status", move.from());
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("Performance Status", move.to());
        next.put("Acted as", move.actingFor().getDisplayName());
        next.put("On behalf", move.onBehalf());
        next.put("Assigned", String.join(", ", assigned));
        next.put("Comment", comment);
        String description = move.label() + ": " + WorkflowMove.displayStatus(move.from()) + " -> "
                + WorkflowMove.displayStatus(move.to())
                + (move.onBehalf() ? " (SoQM on behalf of the " + move.actingFor().getDisplayName() + ")" : "");
        try {
            adminAuditService.logActionWithChanges(user.getMail(), user.getDisplayName(), AUDIT_ACTION, control,
                    description, objectMapper.writeValueAsString(List.of("Performance Status")),
                    objectMapper.writeValueAsString(previous), objectMapper.writeValueAsString(next));
        } catch (JsonProcessingException e) {
            log.error("Could not write the audit entry of a workflow move on control {}", control.getId(), e);
        }
    }

    /**
     * The notice of the move, as each step sends it: a submit to the people of the next step, Complete to the
     * Facilitator, Control Operator and SoQM, a return to the people of the step it goes back to.
     */
    private void notifyMove(Control control, User user, WorkflowMove move, String comment,
                            ControlAssignmentDTO assignment) {
        if (move.isReturn()) {
            WorkflowTransition.Actor target = AccessPolicy.stepOwner(move.to());
            notificationService.sendReturnNotifications(control, peopleOf(target, assignment),
                    move.actingFor().getDisplayName(), user.getDisplayName(), target.getDisplayName(), comment,
                    returnType(move.to()));
            return;
        }
        switch (move.to()) {
            case "REVIEW" -> notificationService.sendTemplateNotifications(control, assignment.getControlOperator(),
                    NotificationTemplateService.TemplateType.FACILITATOR_TO_OPERATOR, false);
            case "SOQM_HEAD_REVIEW" -> notificationService.sendTemplateNotifications(control, assignment.getSoqmLead(),
                    NotificationTemplateService.TemplateType.OPERATOR_TO_SOQM, false);
            case "PROCESS_OWNER_REVIEW" -> notificationService.sendTemplateNotifications(control,
                    assignment.getProcessOwner(), NotificationTemplateService.TemplateType.SOQM_TO_OWNER, false);
            case "COMPLETED" -> {
                Set<String> recipients = new LinkedHashSet<>();
                addAll(recipients, assignment.getFacilitator());
                addAll(recipients, assignment.getControlOperator());
                addAll(recipients, assignment.getSoqmLead());
                notificationService.sendTemplateNotifications(control, new ArrayList<>(recipients),
                        NotificationTemplateService.TemplateType.COMPLETED_ALL, false);
            }
            default -> {
            }
        }
    }

    /** The people assigned to a role's step: the Facilitators, Control Operators, SoQM Team or Process Owners. */
    public static List<String> peopleOf(WorkflowTransition.Actor actor, ControlAssignmentDTO assignment) {
        if (assignment == null || actor == null) {
            return List.of();
        }
        List<String> people = switch (actor) {
            case FACILITATOR -> assignment.getFacilitator();
            case CONTROL_OPERATOR -> assignment.getControlOperator();
            case SOQM_TEAM, COORDINATOR -> assignment.getSoqmLead();
            case PROCESS_OWNER -> assignment.getProcessOwner();
            case SHARED_VIEWER -> List.of();
        };
        Set<String> unique = new LinkedHashSet<>();
        addAll(unique, people);
        return new ArrayList<>(unique);
    }

    private static WorkflowActionType historyType(WorkflowMove move) {
        if (move.isReturn()) {
            return switch (move.to()) {
                case "IN_PROGRESS" -> WorkflowActionType.RETURN_TO_FACILITATOR;
                case "REVIEW" -> WorkflowActionType.RETURN_TO_OPERATOR;
                default -> WorkflowActionType.RETURN;
            };
        }
        return switch (move.to()) {
            case "REVIEW" -> WorkflowActionType.SUBMIT_TO_OPERATOR;
            case "SOQM_HEAD_REVIEW" -> WorkflowActionType.SUBMIT_TO_SOQM_TEAM;
            case "PROCESS_OWNER_REVIEW" -> WorkflowActionType.SUBMIT_TO_PROCESS_OWNER;
            default -> WorkflowActionType.APPROVE;
        };
    }

    /** The history text of a submit without a comment, as the endpoints wrote it before. */
    private static String defaultComment(WorkflowMove move) {
        return switch (move.to()) {
            case "REVIEW" -> "Control submitted to Control Operator for review";
            case "SOQM_HEAD_REVIEW" -> "Control submitted to SoQM Team for review";
            case "PROCESS_OWNER_REVIEW" -> "Control submitted to Process Owner for review";
            case "COMPLETED" -> "Control completed by Process Owner";
            default -> null;
        };
    }

    private static String returnType(String to) {
        return switch (to) {
            case "IN_PROGRESS" -> "RETURN_TO_FACILITATOR";
            case "REVIEW" -> "RETURN_TO_OPERATOR";
            case "SOQM_HEAD_REVIEW" -> "RETURN_TO_SOQM_TEAM";
            default -> "RETURN_TO_PROCESS_OWNER";
        };
    }

    private static boolean isEmpty(List<String> people) {
        return people == null || people.stream().allMatch(person -> person == null || person.isBlank());
    }

    private static void addAll(Set<String> target, List<String> items) {
        if (items == null) {
            return;
        }
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                target.add(item.trim());
            }
        }
    }
}
