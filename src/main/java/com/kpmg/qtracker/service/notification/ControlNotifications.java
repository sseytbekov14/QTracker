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
 * One notification of a control to the people of some of its roles: the values of the control,
 * {RETURNED_BY} when given, the people of the fields (NotificationRecipients, each with their name), then NotificationProducer.
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

    /**
     * What to send: the template, to the people of which roles, {RETURNED_BY}, the key of each e-mail, what goes under
     * the in-app text, and the values of this notification on top of the control's (NotificationValues).
     */
    public record Notice(Template template, List<WorkflowTransition.Actor> roles, String returnedBy,
                         Function<String, String> dedupeKey, String inAppNote, Map<String, String> values,
                         List<WorkflowTransition.Actor> ccRoles) {

        /** Without a copy (every notification but the overdue T5 of In Progress and Review). */
        public Notice(Template template, List<WorkflowTransition.Actor> roles, String returnedBy,
                      Function<String, String> dedupeKey, String inAppNote, Map<String, String> values) {
            this(template, roles, returnedBy, dedupeKey, inAppNote, values, List.of());
        }

        public Notice {
            ccRoles = ccRoles == null ? List.of() : List.copyOf(ccRoles);
        }
    }

    /**
     * @param warn gets what was left out (an address of nobody, a user without an address), for the log
     * @return how many e-mails were queued
     */
    public int send(Control control, ControlAssignmentDTO assignment, Notice notice, Consumer<String> warn) {
        NotificationRecipients.Builder people = recipientsOf(control, assignment, notice.roles());
        people.warnings().forEach(warn);
        List<NotificationRecipients.Person> to = people.people();
        // The copy (2026-11-25): the same rules as the addressees (active, notifications on), never someone already in
        // To; nobody at the step to write to: the copy's people get the e-mail themselves, as before
        List<NotificationRecipients.Person> cc = List.of();
        if (!notice.ccRoles().isEmpty()) {
            NotificationRecipients.Builder copies = recipientsOf(control, assignment, notice.ccRoles());
            copies.warnings().forEach(warn);
            java.util.Set<String> addressed = to.stream().map(NotificationRecipients.Person::email)
                    .collect(java.util.stream.Collectors.toSet());
            cc = copies.people().stream().filter(person -> !addressed.contains(person.email())).toList();
            if (to.isEmpty()) {
                to = cc;
                cc = List.of();
            }
        }
        Map<String, String> placeholders = values.of(control, assignment);
        if (notice.returnedBy() != null) {
            placeholders.put(NotificationValues.RETURNED_BY, notice.returnedBy());
        }
        placeholders.putAll(notice.values());
        return producer.produce(control, notice.template(), placeholders, to, cc, notice.dedupeKey(),
                notice.inAppNote());
    }

    private NotificationRecipients.Builder recipientsOf(Control control, ControlAssignmentDTO assignment,
                                                        List<WorkflowTransition.Actor> roles) {
        NotificationRecipients.Builder people = recipients.forControl(control.getControlId());
        for (WorkflowTransition.Actor role : roles) {
            List<String> addresses = WorkflowMoveService.peopleOf(role, assignment);
            if (role == WorkflowTransition.Actor.SOQM_TEAM) {
                people.addSoqmTeam(addresses);
            } else {
                people.add(roleName(role), addresses);
            }
        }
        return people;
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
