package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.service.WorkflowTransition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

import static com.kpmg.qtracker.service.WorkflowTransition.Actor.CONTROL_OPERATOR;
import static com.kpmg.qtracker.service.WorkflowTransition.Actor.FACILITATOR;
import static com.kpmg.qtracker.service.WorkflowTransition.Actor.PROCESS_OWNER;
import static com.kpmg.qtracker.service.WorkflowTransition.Actor.SOQM_TEAM;

/**
 * The notifications of the workflow, made in the transaction of the step (NOTIFICATIONS_v5 4.2, 6.2; decisions 1 and 4).
 * The template and the people follow the change of status, never who made it: SoQM Admin Team acting on behalf of a
 * role gives what the role itself gives, and no text names it (the actor and "on behalf" are in the history and the
 * audit trail only).
 * <ul>
 * <li>T1 when the control becomes In Progress (Initiate, Create Control): Facilitator, Control Operator, Process Owner;</li>
 * <li>to Review: from In Progress T6, from Process Owner Review T11, from SoQM review or Completed T9 - Control Operator;</li>
 * <li>to In Progress: T7 to the Facilitator, {RETURNED_BY} the role of the step it comes from;</li>
 * <li>to SoQM review: T8 to SoQM Admin Team (also again after a return);</li>
 * <li>to Process Owner Review: T10 to the Process Owner;</li>
 * <li>to Completed: T12 to Facilitator, Control Operator, SoQM Admin Team and Process Owner.</li>
 * </ul>
 * A return's comment is not in the e-mail (the texts send the reader to the control); the in-app notification has it.
 * Each e-mail once per history entry and person.
 */
@Service
@Slf4j
public class WorkflowNotifications {

    private final ControlNotifications notifications;

    public WorkflowNotifications(ControlNotifications notifications) {
        this.notifications = notifications;
    }

    /** What a change of status sends; null for none. */
    public record Event(Template template, List<WorkflowTransition.Actor> roles, String returnedBy) {
    }

    /** The control became In Progress: T1. */
    public int initiated(Control control, ControlAssignmentDTO assignment) {
        return notifications.send(control, assignment, new ControlNotifications.Notice(Template.T1,
                List.of(FACILITATOR, CONTROL_OPERATOR, PROCESS_OWNER), null, null,
                email -> control.getId() + ":T1:" + email, null), WorkflowNotifications::warn);
    }

    /**
     * A move of the workflow, recorded as the history entry given.
     *
     * @param returnComment the comment of a return, for the in-app notification; null otherwise
     */
    public int moved(Control control, ControlAssignmentDTO assignment, String from, String to, Long historyId,
                     String returnComment) {
        Event event = event(from, to);
        if (event == null) {
            return 0;
        }
        String note = returnComment == null || returnComment.isBlank() ? null : "Return Comment:\n" + returnComment.trim();
        return notifications.send(control, assignment, new ControlNotifications.Notice(event.template(), event.roles(),
                null, event.returnedBy(), email -> control.getId() + ":" + event.template() + ":" + email + ":" + historyId,
                note), WorkflowNotifications::warn);
    }

    /** The notification of a change of status, by the statuses alone (decision 4). */
    public static Event event(String from, String to) {
        String source = normalize(from);
        return switch (normalize(to)) {
            case "REVIEW" -> switch (source) {
                case "IN_PROGRESS" -> new Event(Template.T6, List.of(CONTROL_OPERATOR), null);
                case "PROCESS_OWNER_REVIEW" -> new Event(Template.T11, List.of(CONTROL_OPERATOR), null);
                default -> new Event(Template.T9, List.of(CONTROL_OPERATOR), null);
            };
            case "IN_PROGRESS" -> "DRAFT".equals(source) ? null
                    : new Event(Template.T7, List.of(FACILITATOR), returnedBy(source));
            case "SOQM_HEAD_REVIEW" -> new Event(Template.T8, List.of(SOQM_TEAM), null);
            case "PROCESS_OWNER_REVIEW" -> new Event(Template.T10, List.of(PROCESS_OWNER), null);
            case "COMPLETED" -> new Event(Template.T12, List.of(FACILITATOR, CONTROL_OPERATOR, SOQM_TEAM, PROCESS_OWNER), null);
            default -> null;
        };
    }

    /** {RETURNED_BY}: the role of the step the control comes from (Completed: SoQM Admin Team, who returns it). */
    static String returnedBy(String from) {
        return switch (normalize(from)) {
            case "REVIEW" -> NotificationRecipients.CONTROL_OPERATOR;
            case "PROCESS_OWNER_REVIEW" -> NotificationRecipients.PROCESS_OWNER;
            case "IN_PROGRESS" -> NotificationRecipients.FACILITATOR;
            default -> NotificationRecipients.SOQM_ADMIN_TEAM;
        };
    }

    private static String normalize(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    private static void warn(String message) {
        log.warn(message);
    }
}
