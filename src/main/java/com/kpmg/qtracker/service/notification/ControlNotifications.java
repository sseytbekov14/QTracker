package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.service.WorkflowMoveService;
import com.kpmg.qtracker.service.WorkflowTransition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One notification of a control to the people of some of its roles: the values of the control, {ROLE} and
 * {RETURNED_BY} when given, the people of the fields (NotificationRecipients), then NotificationProducer.
 */
@Component
public class ControlNotifications {

    private final NotificationRecipients recipients;
    private final NotificationValues values;
    private final NotificationProducer producer;

    public ControlNotifications(NotificationRecipients recipients, NotificationValues values, NotificationProducer producer) {
        this.recipients = recipients;
        this.values = values;
        this.producer = producer;
    }

    /** What to send: the template, to the people of which roles, the values of the text, the key of each e-mail. */
    public record Notice(Template template, List<WorkflowTransition.Actor> roles, String role, String returnedBy,
                         Function<String, String> dedupeKey, String inAppNote) {
    }

    /**
     * @param warn gets what was left out (an address of nobody, a user without an address), for the log
     * @return how many e-mails were queued
     */
    public int send(Control control, ControlAssignmentDTO assignment, Notice notice, Consumer<String> warn) {
        NotificationRecipients.Builder people = recipients.forControl(control.getControlId());
        for (WorkflowTransition.Actor role : notice.roles()) {
            List<String> addresses = WorkflowMoveService.peopleOf(role, assignment);
            if (role == WorkflowTransition.Actor.SOQM_TEAM) {
                people.addSoqmTeam(addresses);
            } else {
                people.add(roleName(role), addresses);
            }
        }
        people.warnings().forEach(warn);
        Map<String, String> placeholders = values.of(control, assignment);
        if (notice.role() != null) {
            placeholders.put(NotificationValues.ROLE, notice.role());
        }
        if (notice.returnedBy() != null) {
            placeholders.put(NotificationValues.RETURNED_BY, notice.returnedBy());
        }
        return producer.produce(control, notice.template(), placeholders, people.people(), notice.dedupeKey(),
                notice.inAppNote());
    }

    /** The name of a role in the texts: Facilitator, Control Operator, SoQM Admin Team, Process Owner. */
    public static String roleName(WorkflowTransition.Actor role) {
        return switch (role) {
            case FACILITATOR -> NotificationRecipients.FACILITATOR;
            case CONTROL_OPERATOR -> NotificationRecipients.CONTROL_OPERATOR;
            case PROCESS_OWNER -> NotificationRecipients.PROCESS_OWNER;
            case SOQM_TEAM, COORDINATOR -> NotificationRecipients.SOQM_ADMIN_TEAM;
        };
    }
}
